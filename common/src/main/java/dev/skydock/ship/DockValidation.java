package dev.skydock.ship;

import dev.skydock.block.*;
import dev.skydock.data.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public record DockValidation(Map<BlockPos, BlockState> blocks, double mass, double lift, int cells, int engines, String error) {
    public boolean valid() { return error.isEmpty(); }
    public String summary() {
        return String.format(java.util.Locale.ROOT, "%d blocks | %.0f kg | %.0f kg lift | %d lift cells | %d engines. %s",
                blocks.size(), mass, lift, cells, engines, valid() ? "Ready to launch." : error);
    }
    public static DockValidation scan(ServerLevel level, DockBlockEntity dock) {
        DockTier tier = dock.tier();
        BlockPos controller = dock.getBlockPos();
        Direction facing = dock.facing();
        Rotation toLocal = DockTier.inverse(DockTier.rotationFromSouth(facing));
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        double mass = 0; int structural = 0, cells = 0, engines = 0, helms = 0; BlockPos helm = null;
        String error = "";
        for (int x = 0; x < tier.width; x += 16) for (int z = 0; z < tier.length; z += 16) {
            BlockPos a = tier.toWorld(controller, facing, new BlockPos(x, 0, z));
            BlockPos b = tier.toWorld(controller, facing, new BlockPos(Math.min(x + 15, tier.width - 1), 0, Math.min(z + 15, tier.length - 1)));
            if (!level.hasChunkAt(a) || !level.hasChunkAt(b))
                return new DockValidation(blocks, 0, 0, 0, 0, "Load the entire dock before inspecting or launching.");
        }
        BlockPos floor = tier.toWorld(controller, facing, BlockPos.ZERO);
        if (floor.getY() < level.getMinBuildHeight() || floor.getY() + tier.height > level.getMaxBuildHeight())
            return new DockValidation(blocks, 0, 0, 0, 0, "The envelope crosses the world build height.");
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(tier.width - 1, tier.height - 1, tier.length - 1))) {
            BlockPos world = tier.toWorld(controller, facing, p);
            BlockState state = level.getBlockState(world);
            if (state.isAir() || state.is(SkydockBlocks.NOT_SHIP)) continue;
            if (state.is(SkydockBlocks.FORBIDDEN) || state.getDestroySpeed(level, world) < 0) error = "Remove unmovable blocks (bedrock, portals, or moving pistons).";
            if (!state.getFluidState().isEmpty()) error = "Drain water and lava, including waterlogged blocks, before launching.";
            BlockPos local = p.immutable();
            BlockState canonical = state.rotate(toLocal);
            blocks.put(local, canonical);
            boolean decoration = canonical.is(SkydockBlocks.DECORATIONS);
            if (!decoration) { mass += MassTable.mass(canonical); structural++; }
            if (canonical.is(SkydockBlocks.HELM.get())) { helms++; helm = local; }
            if (!decoration && canonical.is(SkydockBlocks.LIFT_CELLS)) cells++;
            if (canonical.is(SkydockBlocks.ENGINES)) engines++;
            if (blocks.size() > MassTable.cap(tier)) return new DockValidation(blocks, mass, cells * MassTable.liftPerCell(), cells, engines, "Too many blocks for this dock tier.");
        }
        double lift = cells * MassTable.liftPerCell();
        if (blocks.isEmpty()) error = "Build a ship inside the outlined envelope first.";
        else if (helms != 1) error = "The ship must have exactly one connected helm (found " + helms + ").";
        else if (error.isEmpty()) {
            Set<BlockPos> reached = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(helm); reached.add(helm);
            while (!queue.isEmpty()) {
                BlockPos p = queue.remove();
                for (Direction face : Direction.values()) {
                    BlockPos q = p.relative(face);
                    if (blocks.containsKey(q) && reached.add(q)) queue.add(q);
                    if (q.getX() < 0 || q.getX() >= tier.width || q.getY() < 0 || q.getY() >= tier.height || q.getZ() < 0 || q.getZ() >= tier.length) {
                        BlockState outside = level.getBlockState(tier.toWorld(controller, facing, q));
                        if (!outside.isAir() && !outside.is(SkydockBlocks.NOT_SHIP)) error = "The hull connects outside the envelope. Separate it from terrain or use scaffolding.";
                    }
                }
            }
            if (reached.size() != blocks.size()) error = "Disconnected blocks: every ship block must connect face-to-face to the helm.";
        }
        if (error.isEmpty() && cells == 0) error = "Install lift cells before launching.";
        if (error.isEmpty() && structural > cells * MassTable.blocksPerCell()) error = "More lift cells are needed for this structural block count.";
        if (error.isEmpty() && lift < mass) error = "Overweight: add lift cells or remove ballast.";
        return new DockValidation(blocks, mass, lift, cells, engines, error);
    }
}
