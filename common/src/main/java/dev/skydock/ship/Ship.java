package dev.skydock.ship;

import dev.skydock.data.DockTier;
import dev.skydock.block.FenceConnections;
import dev.skydock.block.SkydockBlocks;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

public final class Ship {
    public UUID id = UUID.randomUUID(), owner;
    public String team = "";
    public DockTier tier;
    public ResourceKey<Level> dimension = Level.OVERWORLD;
    public BlockPos dock, yard;
    public Direction dockFacing = Direction.SOUTH;
    public ShipPose pose, previousPose;
    public Vec3 velocity = Vec3.ZERO;
    public final ShipMotion history = new ShipMotion();
    public final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    public final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
    public double mass, lift;
    public int cells, engines, revision;
    public UUID pilot;
    public BlockPos pilotHelm;
    public float throttle, steering, climb;
    public double appliedThrottle, appliedClimb, yawVelocity;
    public boolean cruise;
    public double cruiseSpeed;
    public Vec3 pilotAnchor;
    private Vec3 pivot;
    private AABB cachedHullBounds;
    private int hullRevision = -1;
    // Derived views keyed on the pose instance or the block revision; poses are immutable records.
    private ShipPose transformPose, envelopePose, hullWorldPose;
    private ShipPose.Transform cachedTransform;
    private AABB cachedEnvelope, cachedHullWorld, hullWorldSource;
    private List<BlockPos> cachedEngines;
    private List<Surface> cachedSurface;
    private int enginesRevision = -1, surfaceRevision = -1;
    public long lastInputTick;
    public boolean moored = true, blocked;
    public String phase = "active";
    public ResourceKey<Level> transferDimension;
    public BlockPos transferOrigin, transferDock;
    public Direction transferFacing;
    public DockTier transferBerthTier;
    public BlockPos transferShift = BlockPos.ZERO;
    public final Map<UUID, BlockPos> seated = new HashMap<>();

    public Vec3 center() { return pivot == null ? legacyCenter() : pivot; }
    private Vec3 legacyCenter() { return new Vec3(tier.width / 2.0, 0, tier.length / 2.0); }
    void initializePivot() {
        if (pivot != null) return;
        Vec3 old = legacyCenter(), next = occupiedCenter();
        pivot = next; cachedHullBounds = null; hullRevision = -1; cachedSurface = null;
        if (pose != null) pose = rebase(pose, old, next);
        if (previousPose != null) previousPose = rebase(previousPose, old, next);
    }
    private Vec3 occupiedCenter() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var entry : blocks.entrySet()) if (!entry.getValue().isAir() && !entry.getValue().is(SkydockBlocks.DECORATIONS)) {
            minX = Math.min(minX, entry.getKey().getX()); maxX = Math.max(maxX, entry.getKey().getX());
            minZ = Math.min(minZ, entry.getKey().getZ()); maxZ = Math.max(maxZ, entry.getKey().getZ());
        }
        return minX == Integer.MAX_VALUE ? legacyCenter() : new Vec3((minX + maxX + 1) / 2.0, 0, (minZ + maxZ + 1) / 2.0);
    }
    static ShipPose rebase(ShipPose pose, Vec3 oldCenter, Vec3 newCenter) {
        Vec3 position = ShipPose.rotate(newCenter.subtract(oldCenter), pose.yaw()).add(pose.x(), pose.y(), pose.z());
        return new ShipPose(position.x, pose.y(), position.z, pose.yaw());
    }
    public Vec3 blockToWorld(Vec3 block) { return pose.toWorld(block.subtract(center())); }
    public Vec3 worldToBlock(Vec3 world) { return pose.toLocal(world).add(center()); }
    public AABB localBounds() { return new AABB(0, 0, 0, tier.width, tier.height, tier.length).move(center().scale(-1)); }
    public AABB bounds() {
        if (envelopePose != pose) { cachedEnvelope = transform().toWorld(localBounds()); envelopePose = pose; }
        return cachedEnvelope;
    }
    public ShipPose.Transform transform() {
        if (transformPose != pose) { cachedTransform = pose.transform(); transformPose = pose; }
        return cachedTransform;
    }
    /** World box of the occupied hull at the current pose; most hulls are far smaller than their dock envelope. */
    public AABB hullWorldBounds() {
        AABB hull = hullBounds();
        if (hullWorldPose != pose || hullWorldSource != hull) { cachedHullWorld = transform().toWorld(hull); hullWorldPose = pose; hullWorldSource = hull; }
        return cachedHullWorld;
    }
    public List<BlockPos> enginePositions() {
        if (cachedEngines == null || enginesRevision != revision) {
            List<BlockPos> engines = new ArrayList<>();
            for (var entry : blocks.entrySet()) if (entry.getValue().is(SkydockBlocks.ENGINES)) engines.add(entry.getKey());
            cachedEngines = List.copyOf(engines); enginesRevision = revision;
        }
        return cachedEngines;
    }
    /** A collision box of a surface cell, pivot-relative, with the cell it belongs to. */
    public record Surface(BlockPos cell, AABB box) {}
    /**
     * Collision boxes of every cell with a face not covered by a full neighbouring collision block.
     * Moving in steps under a block, a hull can only first touch terrain through one of these.
     */
    public List<Surface> surfaceCollisionBoxes() {
        if (cachedSurface == null || surfaceRevision != revision) {
            ShipBlockView view = new ShipBlockView(this);
            Vec3 center = center();
            List<Surface> boxes = new ArrayList<>();
            for (var entry : blocks.entrySet()) {
                BlockState state = entry.getValue(); BlockPos p = entry.getKey();
                if (state.isAir()) continue;
                var shape = state.getCollisionShape(view, p);
                if (shape.isEmpty()) continue;
                boolean exposed = false;
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = p.relative(direction);
                    if (!state(neighbor).isCollisionShapeFullBlock(view, neighbor)) { exposed = true; break; }
                }
                if (exposed) for (AABB box : shape.toAabbs()) boxes.add(new Surface(p, box.move(p.getX() - center.x, p.getY(), p.getZ() - center.z)));
            }
            cachedSurface = List.copyOf(boxes); surfaceRevision = revision;
        }
        return cachedSurface;
    }
    public AABB hullBounds() {
        if (cachedHullBounds == null || hullRevision != revision) {
            AABB box = null;
            for (var entry : blocks.entrySet()) if (!entry.getValue().isAir()) {
                AABB cell = new AABB(entry.getKey()).move(center().scale(-1));
                box = box == null ? cell : box.minmax(cell);
            }
            cachedHullBounds = box == null ? localBounds() : box;
            hullRevision = revision;
        }
        return cachedHullBounds;
    }
    public BlockState state(BlockPos p) { return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()); }

    boolean refreshRailingConnections() {
        var railing = SkydockBlocks.TIMBER_RAILING.getOrNull();
        if (railing == null) return false;
        boolean changed = false;
        Map<BlockPos, BlockState> resolved = new LinkedHashMap<>();
        for (var entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            if (state.is(railing))
                state = FenceConnections.resolve(state, entry.getKey(), this::state, net.minecraft.world.level.EmptyBlockGetter.INSTANCE);
            resolved.put(entry.getKey(), state);
            changed |= state != entry.getValue();
        }
        if (changed) {
            blocks.clear();
            blocks.putAll(resolved);
            revision++;
        }
        return changed;
    }

    public CompoundTag save(HolderLookup.Provider registries, boolean includeBlocks) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id); if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("Team", team); tag.putString("Tier", tier.name()); tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Dock", dock.asLong()); tag.putLong("Yard", yard.asLong());
        tag.putString("DockFacing", dockFacing.getSerializedName());
        tag.putDouble("X", pose.x()); tag.putDouble("Y", pose.y()); tag.putDouble("Z", pose.z()); tag.putDouble("Yaw", pose.yaw());
        Vec3 center = center(); tag.putDouble("PivotX", center.x); tag.putDouble("PivotZ", center.z);
        tag.putDouble("Vx", velocity.x); tag.putDouble("Vy", velocity.y); tag.putDouble("Vz", velocity.z);
        tag.putDouble("YawVelocity", yawVelocity); tag.putBoolean("Cruise", cruise); tag.putDouble("CruiseSpeed", cruiseSpeed);
        tag.putDouble("Mass", mass); tag.putDouble("Lift", lift); tag.putBoolean("Moored", moored);
        tag.putBoolean("Blocked", blocked); tag.putInt("Revision", revision); tag.putInt("Cells", cells); tag.putInt("Engines", engines);
        tag.putString("Phase", phase);
        if (transferOrigin != null) {
            tag.putLong("TransferOrigin", transferOrigin.asLong()); tag.putLong("TransferDock", transferDock.asLong());
            tag.putString("TransferDimension", transferDimension.location().toString());
            if (transferFacing != null) tag.putString("TransferFacing", transferFacing.getSerializedName());
            if (transferBerthTier != null) tag.putString("TransferBerthTier", transferBerthTier.name());
            if (transferShift != null && !transferShift.equals(BlockPos.ZERO)) tag.putLong("TransferShift", transferShift.asLong());
        }
        if (pilot != null) tag.putUUID("Pilot", pilot);
        if (pilot != null && pilotAnchor != null) {
            tag.putDouble("AnchorX", pilotAnchor.x); tag.putDouble("AnchorY", pilotAnchor.y); tag.putDouble("AnchorZ", pilotAnchor.z);
        }
        if (includeBlocks) {
            // One state compound per distinct state instead of per block keeps saves of large hulls small.
            Map<BlockState, Integer> palette = new HashMap<>();
            ListTag paletteTag = new ListTag();
            long[] positions = new long[blocks.size()];
            int[] states = new int[blocks.size()];
            int i = 0;
            for (var entry : blocks.entrySet()) {
                positions[i] = entry.getKey().asLong();
                states[i++] = palette.computeIfAbsent(entry.getValue(), state -> { paletteTag.add(NbtUtils.writeBlockState(state)); return paletteTag.size() - 1; });
            }
            tag.put("Palette", paletteTag); tag.putLongArray("BlockPositions", positions); tag.putIntArray("BlockStates", states);
            ListTag data = new ListTag();
            blockEntities.forEach((p, entity) -> { CompoundTag e = new CompoundTag(); e.putLong("Pos", p.asLong()); e.put("Data", entity.copy()); data.add(e); });
            tag.put("BlockData", data);
        }
        return tag;
    }

    public static Ship load(CompoundTag tag, HolderLookup.Provider registries) { return load(tag, registries, false); }
    static Ship loadPersisted(CompoundTag tag, HolderLookup.Provider registries) { return load(tag, registries, true); }
    private static Ship load(CompoundTag tag, HolderLookup.Provider registries, boolean migrateLegacy) {
        Ship ship = new Ship();
        ship.id = tag.getUUID("Id"); ship.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ship.team = tag.getString("Team"); ship.tier = DockTier.valueOf(tag.getString("Tier"));
        ship.dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("Dimension")));
        ship.dock = BlockPos.of(tag.getLong("Dock")); ship.yard = BlockPos.of(tag.getLong("Yard"));
        ship.dockFacing = tag.contains("DockFacing")
                ? Direction.byName(tag.getString("DockFacing")) : Direction.SOUTH;
        if (ship.dockFacing == null || !ship.dockFacing.getAxis().isHorizontal()) ship.dockFacing = Direction.SOUTH;
        ship.pose = new ShipPose(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"), tag.getDouble("Yaw")); ship.previousPose = ship.pose;
        if (tag.contains("PivotX", Tag.TAG_DOUBLE) && tag.contains("PivotZ", Tag.TAG_DOUBLE))
            ship.pivot = new Vec3(tag.getDouble("PivotX"), 0, tag.getDouble("PivotZ"));
        ship.velocity = new Vec3(tag.getDouble("Vx"), tag.getDouble("Vy"), tag.getDouble("Vz"));
        ship.yawVelocity = tag.getDouble("YawVelocity"); ship.cruise = tag.getBoolean("Cruise");
        ship.cruiseSpeed = Math.clamp(tag.getDouble("CruiseSpeed"), -FlightDynamics.MAX_TIER_SPEED, FlightDynamics.MAX_TIER_SPEED);
        ship.mass = tag.getDouble("Mass"); ship.lift = tag.getDouble("Lift"); ship.moored = tag.getBoolean("Moored");
        ship.blocked = tag.getBoolean("Blocked"); ship.revision = tag.getInt("Revision"); ship.cells = tag.getInt("Cells"); ship.engines = tag.getInt("Engines");
        ship.phase = tag.getString("Phase");
        if (tag.contains("TransferOrigin")) {
            ship.transferOrigin = BlockPos.of(tag.getLong("TransferOrigin")); ship.transferDock = BlockPos.of(tag.getLong("TransferDock"));
            ship.transferDimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("TransferDimension")));
            if (tag.contains("TransferFacing")) {
                Direction facing = Direction.byName(tag.getString("TransferFacing"));
                ship.transferFacing = facing != null && facing.getAxis().isHorizontal() ? facing : Direction.SOUTH;
            }
            if (tag.contains("TransferBerthTier")) ship.transferBerthTier = DockTier.valueOf(tag.getString("TransferBerthTier"));
            ship.transferShift = tag.contains("TransferShift") ? BlockPos.of(tag.getLong("TransferShift")) : BlockPos.ZERO;
        }
        if (tag.hasUUID("Pilot")) ship.pilot = tag.getUUID("Pilot");
        if (tag.contains("AnchorX")) ship.pilotAnchor = new Vec3(tag.getDouble("AnchorX"), tag.getDouble("AnchorY"), tag.getDouble("AnchorZ"));
        var lookup = registries.lookupOrThrow(Registries.BLOCK);
        if (tag.contains("Palette", Tag.TAG_LIST)) {
            ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) palette[i] = NbtUtils.readBlockState(lookup, paletteTag.getCompound(i));
            long[] positions = tag.getLongArray("BlockPositions");
            int[] states = tag.getIntArray("BlockStates");
            for (int i = 0; i < Math.min(positions.length, states.length); i++)
                if (states[i] >= 0 && states[i] < palette.length) ship.blocks.put(BlockPos.of(positions[i]), palette[states[i]]);
            for (Tag e : tag.getList("BlockData", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) e;
                ship.blockEntities.put(BlockPos.of(entry.getLong("Pos")), entry.getCompound("Data"));
            }
        } else for (Tag e : tag.getList("Blocks", Tag.TAG_COMPOUND)) {          // saves from before the palette format
            CompoundTag entry = (CompoundTag) e; BlockPos p = BlockPos.of(entry.getLong("Pos"));
            ship.blocks.put(p, NbtUtils.readBlockState(lookup, entry.getCompound("State")));
            if (entry.contains("Data")) ship.blockEntities.put(p, entry.getCompound("Data"));
        }
        if (migrateLegacy) ship.refreshRailingConnections();
        if (migrateLegacy && ship.pivot == null) ship.initializePivot();
        return ship;
    }
}
