package dev.skydock.ship;

import dev.skydock.Skydock;
import dev.skydock.data.DockTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Journal is flushed before either world is edited. Interrupted transfers can be replayed. */
public final class ShipTransfer {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    public static boolean changing;

    public static void snapshot(Ship ship, ServerLevel level, BlockPos origin) {
        ship.blockEntities.clear();
        for (BlockPos p : ship.blocks.keySet()) {
            BlockEntity entity = level.getBlockEntity(origin.offset(p));
            if (entity != null) ship.blockEntities.put(p, entity.saveWithFullMetadata(level.registryAccess()));
        }
    }

    public static void snapshotBerth(Ship ship, ServerLevel level, BlockPos controller, Direction facing, DockTier berth, BlockPos shift) {
        ship.blockEntities.clear();
        for (BlockPos p : ship.blocks.keySet()) {
            BlockEntity entity = level.getBlockEntity(berth.toWorld(controller, facing, shift.offset(p)));
            if (entity != null) ship.blockEntities.put(p, entity.saveWithFullMetadata(level.registryAccess()));
        }
    }

    /** Writes the journal, then the chunks of the levels a phase edited, before the next phase begins. */
    public static void flush(MinecraftServer server, ServerLevel... edited) {
        ShipSavedData.get(server).setDirty();
        server.overworld().getDataStorage().save();
        for (ServerLevel level : edited) level.getChunkSource().save(true);
    }
    public static void complete(MinecraftServer server, Ship ship) {
        if (ship.phase.equals("active")) return;
        ServerLevel yard = server.getLevel(ShipManager.SHIPYARD);
        ServerLevel destination = server.getLevel(ship.transferDimension);
        if (yard == null || destination == null) throw new IllegalStateException("Transfer dimension missing; journal retained");
        changing = true;
        try {
            if (ship.phase.equals("launching")) {
                paste(ship, yard, ship.yard);
                flush(server, yard);
                clearBerth(ship, destination);
                ship.phase = "active";
                ship.transferOrigin = null;
                ship.transferFacing = null;
                ship.transferBerthTier = null;
                ship.transferShift = BlockPos.ZERO;
                // The yard now holds the real block entities; keeping the journal copy would bloat every save.
                ship.blockEntities.clear();
                flush(server, destination);
            } else if (ship.phase.equals("redocking")) {
                pasteBerth(ship, destination);
                flush(server, destination);
                clear(ship, yard, ship.yard);
                ShipSavedData.get(server).ships.remove(ship.id);
                ShipManager.releaseTickets(server, ship);
                flush(server, yard);
            } else throw new IllegalStateException("Unknown transfer phase " + ship.phase);
        } finally { changing = false; ShipManager.fleetChanged(); }
    }
    private static void clear(Ship ship, ServerLevel level, BlockPos origin) {
        for (BlockPos p : ship.blocks.keySet()) {
            BlockPos at = origin.offset(p);
            // Removing the BE before its block avoids Container.onRemove duplicating inventory drops.
            level.removeBlockEntity(at);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(origin.offset(p), Blocks.AIR);
    }
    private static void paste(Ship ship, ServerLevel level, BlockPos origin) {
        ship.blocks.forEach((p, state) -> {
            BlockPos at = origin.offset(p); level.removeBlockEntity(at);
            level.setBlock(at, state, FLAGS);
        });
        ship.blockEntities.forEach((p, data) -> {
            BlockPos at = origin.offset(p); CompoundTag tag = data.copy();
            tag.putInt("x", at.getX()); tag.putInt("y", at.getY()); tag.putInt("z", at.getZ());
            BlockEntity entity = BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
            if (entity == null) throw new IllegalStateException("Could not restore block entity at " + at);
            level.setBlockEntity(entity); entity.setChanged();
        });
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(origin.offset(p), ship.state(p).getBlock());
    }

    private static BlockPos berthPos(Ship ship, BlockPos local) {
        DockTier berth = ship.transferBerthTier != null ? ship.transferBerthTier : ship.tier;
        Direction facing = ship.transferFacing != null ? ship.transferFacing : Direction.SOUTH;
        BlockPos shift = ship.transferShift == null ? BlockPos.ZERO : ship.transferShift;
        return berth.toWorld(ship.transferDock, facing, shift.offset(local));
    }

    private static void clearBerth(Ship ship, ServerLevel level) {
        for (BlockPos p : ship.blocks.keySet()) {
            BlockPos at = berthPos(ship, p);
            level.removeBlockEntity(at);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(berthPos(ship, p), Blocks.AIR);
    }

    private static void pasteBerth(Ship ship, ServerLevel level) {
        Direction facing = ship.transferFacing != null ? ship.transferFacing : Direction.SOUTH;
        Rotation rotation = DockTier.rotationFromSouth(facing);
        ship.blocks.forEach((p, state) -> {
            BlockPos at = berthPos(ship, p); level.removeBlockEntity(at);
            level.setBlock(at, state.rotate(rotation), FLAGS);
        });
        ship.blockEntities.forEach((p, data) -> {
            BlockPos at = berthPos(ship, p); CompoundTag tag = data.copy();
            tag.putInt("x", at.getX()); tag.putInt("y", at.getY()); tag.putInt("z", at.getZ());
            BlockEntity entity = BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
            if (entity == null) throw new IllegalStateException("Could not restore block entity at " + at);
            level.setBlockEntity(entity); entity.setChanged();
        });
        for (BlockPos p : ship.blocks.keySet()) {
            BlockState placed = ship.state(p).rotate(rotation);
            level.updateNeighborsAt(berthPos(ship, p), placed.getBlock());
        }
    }
}
