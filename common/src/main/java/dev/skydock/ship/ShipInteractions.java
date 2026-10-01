package dev.skydock.ship;

import dev.skydock.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

public final class ShipInteractions {
    public record Hit(Ship ship, BlockHitResult localHit, double distance) {}
    private record RemoteMenu(UUID ship, BlockPos pos, BlockState state, AbstractContainerMenu menu) {}
    private static final Map<UUID, RemoteMenu> menus = new HashMap<>();
    private static final Map<UUID, Long> lastUse = new HashMap<>();
    public static void clear() { menus.clear(); lastUse.clear(); dev.skydock.network.ShipNetwork.reset(); }

    public static Hit pick(Player player) {
        Vec3 start = player.getEyePosition(), end = start.add(player.getLookAngle().scale(player.blockInteractionRange()));
        return pick(player, start, end);
    }
    private static Hit pick(Player player, Vec3 start, Vec3 end) {
        BlockHitResult terrain = player.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double nearest = terrain.getType() == HitResult.Type.MISS ? start.distanceToSqr(end) : start.distanceToSqr(terrain.getLocation());
        // A closer entity keeps normal use/attack priority (boarding and combat remain vanilla).
        for (var entity : player.level().getEntities(player, player.getBoundingBox().expandTowards(end.subtract(start)).inflate(1), e -> e.isPickable())) {
            Optional<Vec3> hit = entity.getBoundingBox().inflate(entity.getPickRadius()).clip(start, end);
            if (hit.isPresent()) nearest = Math.min(nearest, start.distanceToSqr(hit.get()));
        }
        Hit result = null;
        for (Ship ship : ShipManager.ships(player.level())) {
            if (!ship.bounds().inflate(1).intersects(new AABB(start, end).inflate(.01))) continue;
            Vec3 localStart = ship.worldToBlock(start), localEnd = ship.worldToBlock(end);
            ShipBlockView view = new ShipBlockView(ship);
            AABB range = new AABB(localStart, localEnd).inflate(1);
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(range.minX, range.minY, range.minZ), BlockPos.containing(range.maxX, range.maxY, range.maxZ))) {
                BlockState state = ship.state(pos); if (state.isAir()) continue;
                BlockHitResult hit = state.getShape(view, pos).clip(localStart, localEnd, pos);
                if (hit == null) continue;
                double d = localStart.distanceToSqr(hit.getLocation());
                if (d < nearest) { nearest = d; result = new Hit(ship, hit.withPosition(pos.immutable()), d); }
            }
        }
        return result;
    }
    public static void useViewed(ServerPlayer player, UUID id, double tick, Vec3 localEye, Vec3 localRay) {
        Ship ship = ShipManager.get(player.getServer(), id);
        if (ship == null || !ship.phase.equals("active") || !ship.dimension.equals(player.level().dimension()) || !ship.history.contains(tick)) return;
        if (!Double.isFinite(tick) || !finite(localEye) || !finite(localRay) || localRay.lengthSqr() < .01
                || localRay.length() > player.blockInteractionRange() + .001) return;
        // Reconstruct the viewed ray using server history, then validate it against the real player.
        ShipPose viewed = ship.history.sample(tick);
        Vec3 viewedEye = viewed.toWorld(localEye.subtract(ship.center()));
        Vec3 viewedDirection = ShipPose.rotate(localRay.normalize(), viewed.yaw());
        if (viewedEye.distanceToSqr(player.getEyePosition()) > 2.25 || viewedDirection.dot(player.getLookAngle()) < .95) return;
        Vec3 start = ship.blockToWorld(localEye), end = start.add(ShipPose.rotate(localRay, ship.pose.yaw()));
        Hit hit = pick(player, start, end);
        if (hit != null && hit.ship.id.equals(id) && acceptUse(player)) use(player, hit);
    }
    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static boolean acceptUse(ServerPlayer player) {
        long tick = player.level().getGameTime();
        if (tick - lastUse.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) < 3) return false;
        lastUse.put(player.getUUID(), tick); return true;
    }
    public static void useFromLook(ServerPlayer player) {
        if (!acceptUse(player)) return;
        Hit hit = pick(player); if (hit == null) return;
        use(player, hit);
    }
    public static void use(ServerPlayer player, Hit hit) {
        Ship ship = hit.ship; BlockPos local = hit.localHit.getBlockPos();
        if (!ShipManager.permitted(player, ship)) { ShipManager.tell(player, "Only the ship owner and their team can operate devices or open storage."); return; }
        BlockState state = ship.state(local);
        DeviceBlock.Kind kind = deviceKind(state);
        if (player.isShiftKeyDown() && kind != null) {
            AbstractContainerMenu before = player.containerMenu;
            dev.skydock.menu.DeviceMenu.openShip(player, ship, local, kind);
            if (player.containerMenu != before) menus.put(player.getUUID(), new RemoteMenu(ship.id, local.immutable(), state, player.containerMenu));
            return;
        }
        if (activate(player, ship, local, kind)) return;
        openRemoteBlockMenu(player, ship, local, state, hit.localHit);
    }
    private static DeviceBlock.Kind deviceKind(BlockState state) {
        if (state.is(SkydockBlocks.HELM.get())) return DeviceBlock.Kind.HELM;
        if (state.is(SkydockBlocks.CLAMP.get())) return DeviceBlock.Kind.CLAMP;
        if (state.is(SkydockBlocks.SEAT.get())) return DeviceBlock.Kind.SEAT;
        if (state.is(SkydockBlocks.LIFT_CELLS)) return DeviceBlock.Kind.LIFT;
        if (state.is(SkydockBlocks.BALLAST.get())) return DeviceBlock.Kind.BALLAST;
        return null;
    }
    private static boolean activate(ServerPlayer player, Ship ship, BlockPos local, DeviceBlock.Kind kind) {
        if (kind == DeviceBlock.Kind.HELM) {
            if (player.getUUID().equals(ship.pilot)) { ShipManager.release(player); return true; }
            if (ship.pilot != null) { ShipManager.tell(player, "Another crew member is at the helm."); return true; }
            ship.pilot = player.getUUID(); ship.pilotHelm = local.immutable(); ship.lastInputTick = player.level().getGameTime(); ship.moored = false;
            ship.pilotAnchor = ship.worldToBlock(player.position());
            ship.seated.remove(player.getUUID());
            if (player.getAbilities().flying) { player.getAbilities().flying = false; player.onUpdateAbilities(); }
            ShipManager.tell(player, Component.translatable("message.skydock.helm_engaged", Component.keybind("key.forward"), Component.keybind("key.back"),
                    Component.keybind("key.left"), Component.keybind("key.right"), Component.keybind("key.jump"), Component.keybind("key.sneak"),
                    Component.keybind("key.skydock.cruise"), Component.keybind("key.skydock.release"))); return true;
        }
        if (kind == DeviceBlock.Kind.CLAMP) {
            Vec3 berth = ship.tier.berthCenter(ship.dock, ship.dockFacing, ship.center());
            if (berth.distanceToSqr(new Vec3(ship.pose.x(), ship.pose.y(), ship.pose.z())) > 25) { ShipManager.tell(player, "Mooring clamps only hold within 5 blocks of the reserved berth."); return true; }
            ship.moored = !ship.moored; ship.velocity = Vec3.ZERO; ship.yawVelocity = 0; ship.cruise = false; ShipManager.tell(player, ship.moored ? "Mooring clamp secured." : "Mooring clamp released."); return true;
        }
        if (kind == DeviceBlock.Kind.SEAT) {
            if (local.equals(ship.seated.get(player.getUUID()))) { ship.seated.remove(player.getUUID()); ShipManager.tell(player, "You stand up."); return true; }
            if (ship.seated.containsValue(local)) { ShipManager.tell(player, "This seat is occupied."); return true; }
            ship.seated.put(player.getUUID(), local.immutable()); ShipManager.tell(player, Component.translatable("message.skydock.seated", Component.keybind("key.skydock.release"))); return true;
        }
        return kind == DeviceBlock.Kind.LIFT || kind == DeviceBlock.Kind.BALLAST;
    }
    public static boolean activateFromMenu(ServerPlayer player, UUID shipId, BlockPos local, DeviceBlock.Kind expected) {
        Ship ship = ShipManager.get(player.getServer(), shipId);
        if (ship == null || !ship.phase.equals("active") || !ship.dimension.equals(player.level().dimension()) || !ShipManager.permitted(player, ship)
                || player.position().distanceToSqr(ship.blockToWorld(Vec3.atCenterOf(local))) >= 64 || deviceKind(ship.state(local)) != expected) return false;
        return activate(player, ship, local, expected);
    }
    private static void openRemoteBlockMenu(ServerPlayer player, Ship ship, BlockPos local, BlockState state, BlockHitResult localHit) {
        ServerLevel yard = player.getServer().getLevel(ShipManager.SHIPYARD);
        BlockPos at = ship.yard.offset(local); BlockState actual = yard.getBlockState(at);
        // The shipyard's dimension type disables beds and anchors, and vanilla answers that by exploding them inside the hull.
        if (actual.getBlock() instanceof net.minecraft.world.level.block.BedBlock || actual.getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock) {
            ShipManager.tell(player, "Beds and respawn anchors only work while the ship is docked."); return;
        }
        BlockHitResult remapped = new BlockHitResult(localHit.getLocation().add(Vec3.atLowerCornerOf(ship.yard)), localHit.getDirection(), at, localHit.isInside());
        AbstractContainerMenu before = player.containerMenu;
        // Use the real block, preserving its menu, slots and BE tick. Item placement is deliberately absent.
        actual.useWithoutItem(yard, player, remapped);
        if (player.containerMenu != before) menus.put(player.getUUID(), new RemoteMenu(ship.id, local.immutable(), actual, player.containerMenu));
    }
    public static boolean validRemoteMenu(Player player, AbstractContainerMenu menu) {
        RemoteMenu remote = menus.get(player.getUUID());
        if (remote == null || remote.menu != menu || player.getServer() == null) return false;
        Ship ship = ShipManager.get(player.getServer(), remote.ship);
        if (ship == null || !ship.phase.equals("active") || !ship.dimension.equals(player.level().dimension()) || !ShipManager.permitted(player, ship)) return false;
        ServerLevel yard = player.getServer().getLevel(ShipManager.SHIPYARD);
        return yard != null && yard.getBlockState(ship.yard.offset(remote.pos)).is(remote.state.getBlock()) && player.isAlive()
                && player.position().distanceToSqr(ship.blockToWorld(Vec3.atCenterOf(remote.pos))) < 64;
    }
    public static boolean menuStillValid(Player player, AbstractContainerMenu menu) {
        RemoteMenu remote = menus.get(player.getUUID());
        return remote != null && remote.menu == menu ? validRemoteMenu(player, menu) : menu.stillValid(player);
    }
    public static void tick(MinecraftServer server) {
        menus.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.containerMenu != entry.getValue().menu) return true;
            if (!validRemoteMenu(player, player.containerMenu)) { player.closeContainer(); return true; } return false;
        });
        for (ServerLevel level : server.getAllLevels()) for (Ship ship : ShipManager.ships(level)) ship.seated.keySet().removeIf(id -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id); return player == null || !player.isAlive() || player.level() != level;
        });
    }
}
