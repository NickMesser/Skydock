package dev.skydock.assembly;

import dev.skydock.block.DockBlockEntity;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.data.DockTier;
import dev.skydock.data.MassTable;
import dev.skydock.data.ShipPattern;
import dev.skydock.data.ShipPatterns;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-authoritative assembly validation and the staged placement transaction. */
public final class AssemblyManager {
    public static final int BLOCKS_PER_STEP = 4;
    public static final int STEP_TICKS = 2;
    private static final Map<ServerLevel, Map<BlockPos, DockBlockEntity>> ACTIVE = new IdentityHashMap<>();

    private AssemblyManager() {}

    public record Check(boolean clear, String message) {}

    public static ShipPattern selected(DockBlockEntity dock) {
        if (dock.assemblyJob() != null) {
            AssemblyJob job = dock.assemblyJob();
            ShipPattern catalog = ShipPatterns.get(job.pattern).orElse(null);
            String name = catalog == null ? job.pattern.getPath() : catalog.name();
            String description = catalog == null ? "Assembly restored from its persisted build journal." : catalog.description();
            return new ShipPattern(job.pattern, name, description, dock.tier(), job.cells.stream()
                    .map(cell -> new ShipPattern.Block(cell.pos(), cell.state(), cell.decoration())).toList());
        }
        return ShipPatterns.get(dock.selectedPattern()).filter(pattern -> pattern.tier() == dock.tier())
                .orElseGet(() -> ShipPatterns.defaultFor(dock.tier()));
    }

    private static BlockPos worldPos(DockBlockEntity dock, BlockPos local) {
        return dock.tier().toWorld(dock.getBlockPos(), dock.facing(), local);
    }

    private static BlockState worldState(DockBlockEntity dock, BlockState local) {
        return local.rotate(DockTier.rotationFromSouth(dock.facing()));
    }

    private static AABB footprint(DockBlockEntity dock, ShipPattern pattern, boolean decorations) {
        return dock.tier().toWorld(dock.getBlockPos(), dock.facing(), pattern.bounds(decorations));
    }

    public static Check validate(ServerLevel level, DockBlockEntity dock, ShipPattern pattern, boolean decorations) {
        if (pattern.tier() != dock.tier()) return new Check(false, "That pattern does not fit this dock tier.");
        if (dock.assemblyJob() == null && ShipManager.berthReserved(level, dock.getBlockPos()))
            return new Check(false, "This berth is reserved by its launched ship.");
        List<ShipPattern.Block> cells = pattern.included(decorations);
        if (cells.size() > MassTable.cap(dock.tier())) return new Check(false, "This pattern exceeds the dock's block limit.");
        ShipPattern.Stats stats = pattern.stats(decorations);
        long helms = cells.stream().filter(cell -> cell.state().is(SkydockBlocks.HELM.get())).count();
        if (helms != 1) return new Check(false, "The pattern must contain exactly one helm.");
        if (stats.engines() < 1) return new Check(false, "The pattern requires at least one engine.");
        if (stats.cells() < 1) return new Check(false, "The pattern requires lift cells.");
        int requiredCells = (stats.structuralBlocks() + MassTable.blocksPerCell() - 1) / MassTable.blocksPerCell();
        if (stats.cells() < requiredCells) return new Check(false, "The pattern needs more lift cells for its structural block count.");
        if (stats.mass() > stats.lift()) return new Check(false, "The current mass rules make this pattern too heavy to launch.");
        AABB footprint = footprint(dock, pattern, decorations);
        if (footprint.minY < level.getMinBuildHeight() || footprint.maxY > level.getMaxBuildHeight())
            return new Check(false, "The ship would cross the world build height.");
        if (!level.getWorldBorder().isWithinBounds(footprint)) return new Check(false, "The ship would cross the world border.");
        int minChunkX = ((int) Math.floor(footprint.minX)) >> 4;
        int maxChunkX = ((int) Math.ceil(footprint.maxX) - 1) >> 4;
        int minChunkZ = ((int) Math.floor(footprint.minZ)) >> 4;
        int maxChunkZ = ((int) Math.ceil(footprint.maxZ) - 1) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++)
            if (!level.hasChunk(x, z)) return new Check(false, "Load the entire assembly footprint before building.");
        String occupant = occupant(level, footprint);
        if (occupant != null) return new Check(false, occupant);

        Map<BlockPos, BlockState> allowed = new HashMap<>();
        if (dock.assemblyJob() != null) {
            AssemblyJob job = dock.assemblyJob();
            for (int i = 0; i < job.placed; i++)
                allowed.put(worldPos(dock, job.cells.get(i).pos()), worldState(dock, job.cells.get(i).state()));
        }
        int minX = (int) Math.floor(footprint.minX), minY = (int) Math.floor(footprint.minY), minZ = (int) Math.floor(footprint.minZ);
        int maxX = (int) Math.ceil(footprint.maxX) - 1, maxY = (int) Math.ceil(footprint.maxY) - 1, maxZ = (int) Math.ceil(footprint.maxZ) - 1;
        for (BlockPos worldPos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockState state = level.getBlockState(worldPos);
            if (state.isAir()) continue;
            BlockState expected = allowed.get(worldPos);
            if (expected == null || !state.equals(expected))
                return new Check(false, "Clear every block from the ship's assembly footprint.");
        }
        return new Check(true, "Space is clear.");
    }

    /** Things that can move out of the way on their own, so a running assembly waits for them rather than failing. */
    private static String occupant(ServerLevel level, AABB footprint) {
        for (Entity entity : level.getEntities((Entity) null, footprint, entity -> !entity.isSpectator())) {
            if (entity instanceof Player) continue;
            if (entity instanceof Mob mob) {
                mob.discard();
                continue;
            }
            return "Move vehicles out of the assembly footprint.";
        }
        for (Ship ship : ShipManager.ships(level)) if (ship.pose.toWorld(ship.hullBounds()).intersects(footprint))
            return "Another launched ship overlaps the assembly footprint.";
        return null;
    }

    public static boolean start(ServerPlayer player, DockBlockEntity dock) {
        ServerLevel level = player.serverLevel();
        if (dock.assemblyJob() != null) { ShipManager.tell(player, "This dock is already assembling a ship."); return false; }
        ShipPattern pattern = selected(dock);
        Check check = validate(level, dock, pattern, dock.decorations());
        if (!check.clear()) { dock.setStatus(check.message()); ShipManager.tell(player, check.message()); return false; }
        boolean free = player.isCreative();
        List<ItemStack> escrow;
        if (free) {
            escrow = List.of();
        } else {
            Map<Item, Integer> cost = pattern.cost(dock.decorations());
            Map<Item, Integer> available = AssemblyInventory.available(level, dock.getBlockPos());
            for (Map.Entry<Item, Integer> requirement : cost.entrySet()) if (available.getOrDefault(requirement.getKey(), 0) < requirement.getValue()) {
                dock.setStatus("The adjacent chest is missing required materials.");
                ShipManager.tell(player, dock.status());
                return false;
            }
            escrow = AssemblyInventory.extract(level, dock.getBlockPos(), cost);
            if (escrow.isEmpty() && !cost.isEmpty()) {
                dock.setStatus("Chest contents changed before assembly could start.");
                ShipManager.tell(player, dock.status());
                return false;
            }
        }
        dock.beginAssembly(new AssemblyJob(pattern.id(), dock.decorations(), pattern.included(dock.decorations()), escrow, free));
        register(level, dock);
        ShipManager.tell(player, free
                ? "Creative assembly started: " + pattern.name() + "."
                : "Assembly started: " + pattern.name() + ".");
        return true;
    }

    public static void tick(ServerLevel level, DockBlockEntity dock) {
        AssemblyJob job = dock.assemblyJob();
        if (job == null) { unregister(level, dock); return; }
        register(level, dock);
        if (level.getGameTime() - job.lastStep < STEP_TICKS) return;
        job.lastStep = level.getGameTime();
        ShipPattern pattern = selected(dock);
        AABB footprint = footprint(dock, pattern, job.decorations);
        boolean loaded = chunksLoaded(level, footprint);
        String occupant = loaded ? occupant(level, footprint) : null;
        String paused = !loaded ? "Assembly paused until its footprint is loaded." : occupant != null ? "Assembly paused. " + occupant : null;
        if (paused != null) {
            if (!dock.status().equals(paused)) dock.setStatus(paused);
            return;
        }
        if (dock.status().startsWith("Assembly paused")) dock.setStatus("Assembly in progress.");
        boolean recovered = false;
        // A world chunk may have saved just ahead of the dock journal. Adopt only the contiguous
        // exact states from the already-cleared plan so recovery cannot refund duplicate blocks.
        while (!job.complete()) {
            AssemblyJob.Cell cell = job.cells.get(job.placed);
            if (!level.getBlockState(worldPos(dock, cell.pos())).equals(worldState(dock, cell.state()))) break;
            if (!job.spend(Item.byBlock(cell.state().getBlock()))) { fail(level, dock, "The assembly escrow is incomplete."); return; }
            job.placed++;
            recovered = true;
        }
        if (recovered) dock.markAssemblyProgress();
        Check check = validate(level, dock, pattern, job.decorations);
        if (!check.clear()) { fail(level, dock, check.message()); return; }
        int placedThisStep = 0;
        while (!job.complete() && placedThisStep++ < BLOCKS_PER_STEP) {
            AssemblyJob.Cell cell = job.cells.get(job.placed);
            BlockPos at = worldPos(dock, cell.pos());
            BlockState placed = worldState(dock, cell.state());
            if (!level.getBlockState(at).isAir()) { fail(level, dock, "The assembly footprint became obstructed."); return; }
            if (!job.spend(Item.byBlock(cell.state().getBlock()))) { fail(level, dock, "The assembly escrow is incomplete."); return; }
            if (!level.setBlock(at, placed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                AssemblyInventory.merge(job.escrow, job.undoLastSpend());
                fail(level, dock, "A ship block could not be placed."); return;
            }
            job.placed++;
            level.sendParticles(ParticleTypes.END_ROD, at.getX() + .5, at.getY() + .5, at.getZ() + .5,
                    3, .22, .22, .22, .015);
        }
        dock.markAssemblyProgress();
        if (job.complete()) {
            for (AssemblyJob.Cell cell : job.cells)
                if (!level.getBlockState(worldPos(dock, cell.pos())).equals(worldState(dock, cell.state()))) {
                    fail(level, dock, "The completed ship did not match its assembly plan."); return;
                }
            dock.completeAssembly(job.total());
            unregister(level, dock);
        } else {
            AssemblyJob.Cell next = job.cells.get(job.placed);
            BlockPos at = worldPos(dock, next.pos());
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.getX() + .5, at.getY() + .5, at.getZ() + .5,
                    6, .45, .45, .45, .025);
        }
    }

    public static void cancel(ServerLevel level, DockBlockEntity dock, String reason) {
        if (dock.assemblyJob() == null) return;
        rollback(level, dock, reason);
    }

    private static void fail(ServerLevel level, DockBlockEntity dock, String reason) {
        rollback(level, dock, "Assembly cancelled: " + reason);
    }

    private static void rollback(ServerLevel level, DockBlockEntity dock, String reason) {
        AssemblyJob job = dock.assemblyJob();
        if (job == null) return;
        List<ItemStack> refund = new ArrayList<>();
        List<Map.Entry<BlockPos, Block>> removed = new ArrayList<>();
        if (!job.free) for (ItemStack stack : job.escrow) AssemblyInventory.merge(refund, stack);
        for (int i = 0; i < job.placed; i++) {
            AssemblyJob.Cell cell = job.cells.get(i);
            BlockPos at = worldPos(dock, cell.pos());
            if (!level.getBlockState(at).equals(worldState(dock, cell.state()))) continue;
            if (level.getBlockEntity(at) instanceof Container container) Containers.dropContents(level, at, container);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            removed.add(Map.entry(at.immutable(), cell.state().getBlock()));
            if (!job.free && i < job.spent.size()) AssemblyInventory.merge(refund, job.spent.get(i));
        }
        // Notify the surrounding world only after every exact-state ownership check is finished.
        for (Map.Entry<BlockPos, Block> entry : removed) level.updateNeighborsAt(entry.getKey(), entry.getValue());
        dock.finishAssembly(reason);
        unregister(level, dock);
        if (!job.free) AssemblyInventory.refund(level, dock.getBlockPos(), refund);
    }

    public static boolean protects(ServerLevel level, BlockPos pos) {
        Map<BlockPos, DockBlockEntity> docks = ACTIVE.get(level);
        if (docks == null) return false;
        docks.entrySet().removeIf(entry -> level.getBlockEntity(entry.getKey()) != entry.getValue() || entry.getValue().assemblyJob() == null);
        for (DockBlockEntity dock : docks.values()) {
            AssemblyJob job = dock.assemblyJob();
            if (job.cells.stream().anyMatch(cell -> worldPos(dock, cell.pos()).equals(pos))) return true;
        }
        return false;
    }

    /** True when a natural/spawner spawn at this position would land inside an in-progress assembly footprint. */
    public static boolean suppressesSpawns(ServerLevel level, double x, double y, double z) {
        Map<BlockPos, DockBlockEntity> docks = ACTIVE.get(level);
        if (docks == null || docks.isEmpty()) return false;
        docks.entrySet().removeIf(entry -> level.getBlockEntity(entry.getKey()) != entry.getValue() || entry.getValue().assemblyJob() == null);
        for (DockBlockEntity dock : docks.values()) {
            AssemblyJob job = dock.assemblyJob();
            if (job == null) continue;
            ShipPattern pattern = selected(dock);
            if (footprint(dock, pattern, job.decorations).inflate(.5).contains(x, y, z)) return true;
        }
        return false;
    }

    private static void register(ServerLevel level, DockBlockEntity dock) {
        ACTIVE.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).put(dock.getBlockPos().immutable(), dock);
    }

    private static boolean chunksLoaded(ServerLevel level, AABB footprint) {
        int minChunkX = ((int) Math.floor(footprint.minX)) >> 4;
        int maxChunkX = ((int) Math.ceil(footprint.maxX) - 1) >> 4;
        int minChunkZ = ((int) Math.floor(footprint.minZ)) >> 4;
        int maxChunkZ = ((int) Math.ceil(footprint.maxZ) - 1) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++)
            if (!level.hasChunk(x, z)) return false;
        return true;
    }

    private static void unregister(ServerLevel level, DockBlockEntity dock) {
        Map<BlockPos, DockBlockEntity> docks = ACTIVE.get(level);
        if (docks != null) {
            docks.remove(dock.getBlockPos());
            if (docks.isEmpty()) ACTIVE.remove(level);
        }
    }

    public static void stop(MinecraftServer server) {
        ACTIVE.keySet().removeIf(level -> level.getServer() == server);
    }
}
