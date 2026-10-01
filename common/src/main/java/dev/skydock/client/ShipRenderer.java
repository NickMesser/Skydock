package dev.skydock.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.skydock.Skydock;
import dev.skydock.block.*;
import dev.skydock.ship.*;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class ShipRenderer {
    /** Uploaded geometry for one published hull; {@code ship} is the exact snapshot it was built from. */
    private record Mesh(Ship ship, List<Layer> layers, List<BlockPos> dynamic, Map<BlockPos, BlockEntity> entities) implements AutoCloseable {
        @Override public void close() { layers.forEach(layer -> layer.buffer.close()); }
    }
    private record Layer(RenderType type, VertexBuffer buffer) {}
    /** Tessellated off the render thread; uploaded, or discarded, on it. */
    private record Built(Ship ship, List<BuiltLayer> layers, List<BlockPos> dynamic) {
        void discard() { layers.forEach(BuiltLayer::close); }
    }
    private record BuiltLayer(RenderType type, ByteBufferBuilder bytes, MeshData data) {
        void close() { data.close(); bytes.close(); }
    }
    private static final Map<UUID, Mesh> meshes = new HashMap<>();
    private static final Map<UUID, CompletableFuture<Built>> building = new HashMap<>();
    private record DockOutline(dev.skydock.data.DockTier tier, net.minecraft.core.Direction facing) {}
    private static final Map<BlockPos, DockOutline> docks = new HashMap<>();
    private static boolean workerFailed;

    public static void clear() {
        meshes.values().forEach(Mesh::close); meshes.clear(); docks.clear();
        building.values().forEach(ShipRenderer::abandon); building.clear();
    }
    /** Hull snapshots replace the ship object, which is what triggers a rebuild; the old mesh draws until then. */
    public static void invalidate(UUID id) {}
    public static void retain(Set<UUID> ids) {
        for (UUID id : Set.copyOf(meshes.keySet())) if (!ids.contains(id)) meshes.remove(id).close();
        for (UUID id : Set.copyOf(building.keySet())) if (!ids.contains(id)) abandon(building.remove(id));
    }
    private static void abandon(CompletableFuture<Built> future) {
        future.thenAccept(built -> Minecraft.getInstance().execute(built::discard));
    }
    public static void findDocks(Minecraft mc) {
        docks.clear();
        int cx = mc.player.chunkPosition().x, cz = mc.player.chunkPosition().z;
        for (int x = cx - 6; x <= cx + 6; x++) for (int z = cz - 6; z <= cz + 6; z++) {
            var chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk != null) chunk.getBlockEntities().forEach((p, entity) -> {
                if (entity instanceof DockBlockEntity dock) docks.put(p, new DockOutline(dock.tier(), dock.facing()));
            });
        }
    }
    /** Returns the mesh to draw this frame, starting or finishing a background rebuild when the hull changed. */
    private static Mesh mesh(Minecraft mc, Ship ship) {
        Mesh current = meshes.get(ship.id);
        CompletableFuture<Built> pending = building.get(ship.id);
        if (pending != null && pending.isDone()) {
            building.remove(ship.id);
            Built built = null;
            try { built = pending.join(); }
            catch (RuntimeException ex) {
                if (!workerFailed) Skydock.LOGGER.warn("Background hull meshing failed; building meshes on the render thread instead", ex);
                workerFailed = true;
            }
            if (built != null && built.ship() == ship) {
                if (current != null) current.close();
                current = upload(mc, built);
                meshes.put(ship.id, current);
            } else if (built != null) built.discard();
            pending = null;
        }
        if ((current == null || current.ship() != ship) && pending == null) {
            ShipRenderView view = new ShipRenderView(ship, mc.level);
            if (workerFailed || current == null && ship.blocks.size() < 512) {
                // Small first builds are cheap enough to show on the very frame the hull arrives.
                if (current != null) current.close();
                current = upload(mc, build(ship, view));
                meshes.put(ship.id, current);
            } else building.put(ship.id, CompletableFuture.supplyAsync(() -> build(ship, view), Util.backgroundExecutor()));
        }
        return current;
    }
    private static Built build(Ship ship, ShipRenderView view) {
        Minecraft mc = Minecraft.getInstance();
        Map<RenderType, List<BlockPos>> byType = new LinkedHashMap<>();
        Set<BlockPos> dynamic = new LinkedHashSet<>();
        for (var entry : view.blocks().entrySet()) {
            BlockState state = entry.getValue(); if (state.isAir()) continue;
            RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
            if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED || layer == RenderType.translucent()) dynamic.add(entry.getKey());
            else if (state.getRenderShape() == RenderShape.MODEL) byType.computeIfAbsent(layer, key -> new ArrayList<>()).add(entry.getKey());
        }
        List<BuiltLayer> layers = new ArrayList<>();
        RandomSource random = RandomSource.create();
        PoseStack poses = new PoseStack();
        double cx = ship.center().x, cz = ship.center().z;
        for (var entry : byType.entrySet()) {
            ByteBufferBuilder bytes = new ByteBufferBuilder(Math.clamp(entry.getValue().size() * 512L, 64 * 1024, 2 * 1024 * 1024));
            try {
                BufferBuilder buffer = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                for (BlockPos p : entry.getValue()) {
                    BlockState state = view.getBlockState(p); poses.pushPose(); poses.translate(p.getX() - cx, p.getY(), p.getZ() - cz);
                    mc.getBlockRenderer().getModelRenderer().tesselateBlock(view, mc.getBlockRenderer().getBlockModel(state), state, p, poses, buffer, true, random, state.getSeed(p), OverlayTexture.NO_OVERLAY);
                    poses.popPose();
                }
                MeshData data = buffer.build();
                if (data == null) bytes.close(); else layers.add(new BuiltLayer(entry.getKey(), bytes, data));
            } catch (RuntimeException ex) { bytes.close(); layers.forEach(BuiltLayer::close); throw ex; }
        }
        return new Built(ship, layers, List.copyOf(dynamic));
    }
    private static Mesh upload(Minecraft mc, Built built) {
        List<Layer> layers = new ArrayList<>();
        for (BuiltLayer layer : built.layers()) {
            VertexBuffer vertex = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try { vertex.bind(); vertex.upload(layer.data()); VertexBuffer.unbind(); }
            finally { layer.bytes().close(); }
            layers.add(new Layer(layer.type(), vertex));
        }
        // Only block entities with a renderer need an instance and a per-frame pass; the rest are in the static mesh.
        Ship ship = built.ship();
        Set<BlockPos> dynamic = new LinkedHashSet<>(built.dynamic());
        Map<BlockPos, BlockEntity> entities = new HashMap<>();
        for (var entry : ship.blocks.entrySet()) if (entry.getValue().getBlock() instanceof net.minecraft.world.level.block.EntityBlock block) {
            BlockEntity be = block.newBlockEntity(entry.getKey(), entry.getValue());
            if (be == null) continue;
            be.setLevel(mc.level);
            if (ship.blockEntities.containsKey(entry.getKey())) be.loadWithComponents(ship.blockEntities.get(entry.getKey()), mc.level.registryAccess());
            if (mc.getBlockEntityRenderDispatcher().getRenderer(be) == null) continue;
            entities.put(entry.getKey(), be);
            dynamic.add(entry.getKey());
        }
        return new Mesh(ship, layers, List.copyOf(dynamic), entities);
    }
    public static void render(Matrix4f viewMatrix, Matrix4f projection, Camera camera, Frustum frustum, float partialTick) {
        Minecraft mc = Minecraft.getInstance(); if (mc.level == null || mc.player == null) return;
        Vec3 eye = camera.getPosition();
        for (Ship ship : SkydockClient.SHIPS.values()) {
            if (!ship.dimension.equals(mc.level.dimension()) || ship.bounds().distanceToSqr(eye) > 256 * 256) continue;
            if (frustum != null && !frustum.isVisible(ship.hullWorldBounds().inflate(2))) continue;
            Mesh mesh = mesh(mc, ship);
            if (mesh == null) continue;
            ShipPose a = ship.previousPose, b = ship.pose;
            double x = a.x() + (b.x() - a.x()) * partialTick, y = a.y() + (b.y() - a.y()) * partialTick, z = a.z() + (b.z() - a.z()) * partialTick;
            float yaw = (float) (a.yaw() + net.minecraft.util.Mth.wrapDegrees(b.yaw() - a.yaw()) * partialTick);
            Matrix4f model = new Matrix4f(viewMatrix).translate((float) (x - eye.x), (float) (y - eye.y), (float) (z - eye.z)).rotateY((float) Math.toRadians(-yaw));
            for (Layer layer : mesh.layers) {
                layer.type.setupRenderState();
                var shader = RenderSystem.getShader(); if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0.0f, 0.0f, 0.0f);
                layer.buffer.bind(); layer.buffer.drawWithShader(model, projection, shader); VertexBuffer.unbind(); layer.type.clearRenderState();
            }
            if (mesh.dynamic.isEmpty()) continue;
            // Buffered entity layers already receive the camera view matrix from Minecraft.
            PoseStack poses = new PoseStack(); poses.translate(x - eye.x, y - eye.y, z - eye.z); poses.mulPose(Axis.YP.rotationDegrees(-yaw));
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            ShipPose.Transform transform = ship.transform();
            // The mesh may still be the previous snapshot's while a rebuild runs, so read states from it.
            Ship source = mesh.ship();
            double cx = source.center().x, cz = source.center().z;
            boolean nearEntities = ship.hullWorldBounds().distanceToSqr(eye) < 64 * 64;
            for (BlockPos p : mesh.dynamic) {
                var be = mesh.entities.get(p);
                if (be != null && !nearEntities) continue;
                poses.pushPose(); poses.translate(p.getX() - cx, p.getY(), p.getZ() - cz);
                int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(transform.toWorld(new Vec3(p.getX() + .5 - cx, p.getY() + .5, p.getZ() + .5 - cz))));
                if (be != null) mc.getBlockEntityRenderDispatcher().renderItem(be, poses, buffers, light, OverlayTexture.NO_OVERLAY);
                BlockState state = source.state(p);
                if (be == null && (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED || ItemBlockRenderTypes.getChunkRenderType(state) == RenderType.translucent()))
                    mc.getBlockRenderer().renderSingleBlock(state, poses, buffers, light, OverlayTexture.NO_OVERLAY);
                poses.popPose();
            }
            buffers.endBatch();
        }
        PoseStack outlines = new PoseStack(); outlines.translate(-eye.x, -eye.y, -eye.z);
        var buffers = mc.renderBuffers().bufferSource();
        var nearest = docks.entrySet().stream().min(Comparator.comparingDouble(e -> e.getKey().distToCenterSqr(mc.player.position()))).orElse(null);
        if (nearest != null) LevelRenderer.renderLineBox(outlines, buffers.getBuffer(RenderType.lines()), nearest.getValue().tier().envelope(nearest.getKey(), nearest.getValue().facing()), .35f, .9f, .76f, .8f);
        if (mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem item && item.getBlock() instanceof DockControllerBlock dock
                && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            LevelRenderer.renderLineBox(outlines, buffers.getBuffer(RenderType.lines()), dock.tier.envelope(hit.getBlockPos().relative(hit.getDirection()), mc.player.getDirection()), .5f, .75f, 1, .8f);
        }
        ShipInteractions.Hit hit = ShipInteractions.pick(mc.player);
        if (hit != null) {
            Ship ship = hit.ship(); ShipPose pose = ship.previousPose.interpolate(ship.pose, partialTick); PoseStack selection = new PoseStack();
            selection.translate(pose.x() - eye.x, pose.y() - eye.y, pose.z() - eye.z); selection.mulPose(Axis.YP.rotationDegrees((float) -pose.yaw()));
            selection.translate(-ship.center().x, 0, -ship.center().z);
            LevelRenderer.renderLineBox(selection, buffers.getBuffer(RenderType.lines()), new AABB(hit.localHit().getBlockPos()).inflate(.003), .9f, 1, 1, .9f);
        }
        buffers.endBatch(RenderType.lines());
    }
}
