package dev.skydock.ship;

import dev.skydock.Skydock;
import dev.skydock.block.*;
import dev.skydock.data.*;
import dev.skydock.network.ShipNetwork;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

public final class ShipManager {
    public static final ResourceKey<Level> SHIPYARD = ResourceKey.create(Registries.DIMENSION, Skydock.id("shipyard"));
    private static final TicketType<UUID> TICKET = TicketType.create("skydock_ship", UUID::compareTo, 300);
    private static final Map<MinecraftServer, ShipSavedData> SERVERS = new IdentityHashMap<>();
    private static final Map<UUID, Set<ChunkPos>> WORLD_TICKETS = new HashMap<>();
    public record Boarding(UUID ship, Vec3 localFeet) {}
    private static final Map<UUID, Boarding> BOARDING = new HashMap<>();
    private static final Set<UUID> JOINED = new HashSet<>();
    /** Active ships per level. Entity collision asks for these on every move, so the list is kept until the fleet changes. */
    private static final Map<Level, List<Ship>> ACTIVE = new IdentityHashMap<>();
    private static final Map<GlobalPos, Long> LAST_TRANSFER = new HashMap<>();
    public static Collection<Ship> ships(Level level) {
        if (level.isClientSide) return ClientShipAccess.ships(level);
        ShipSavedData data = SERVERS.get(level.getServer());
        if (data == null) return List.of();
        return ACTIVE.computeIfAbsent(level, key -> data.ships.values().stream().filter(s -> s.phase.equals("active") && s.dimension.equals(key.dimension())).toList());
    }
    /** Call whenever a ship is added, removed, or changes phase. */
    public static void fleetChanged() { ACTIVE.clear(); }
    public static Ship get(MinecraftServer server, UUID id) { return ShipSavedData.get(server).ships.get(id); }
    public static boolean berthReserved(Level level, BlockPos dock) {
        if (level.isClientSide || level.getServer() == null) return false;
        return ShipSavedData.get(level.getServer()).ships.values().stream()
                .anyMatch(ship -> ship.dimension.equals(level.dimension()) && ship.dock.equals(dock));
    }
    public static void start(MinecraftServer server) {
        ShipSavedData data = ShipSavedData.get(server); SERVERS.put(server, data); fleetChanged();
        for (Ship ship : List.copyOf(data.ships.values())) {
            try { ShipTransfer.complete(server, ship); }
            catch (Exception e) { Skydock.LOGGER.error("Ship {} transfer remains in its recovery journal", ship.id, e); }
        }
    }
    public static void stop(MinecraftServer server) { SERVERS.remove(server); WORLD_TICKETS.clear(); BOARDING.clear(); JOINED.clear(); LAST_TRANSFER.clear(); fleetChanged(); ShipDamage.clear(); ShipInteractions.clear(); }
    /** Launch and redock each flush the journal and chunks to disk, so one berth cannot be cycled every tick. */
    private static boolean settling(ServerPlayer player, BlockPos pos) {
        Long last = LAST_TRANSFER.get(GlobalPos.of(player.level().dimension(), pos));
        if (last != null && player.level().getGameTime() - last < 100) { tell(player, "The berth is still settling; try again in a few seconds."); return true; }
        return false;
    }
    private static void transferred(ServerPlayer player, BlockPos pos) { LAST_TRANSFER.put(GlobalPos.of(player.level().dimension(), pos.immutable()), player.level().getGameTime()); }
    public static Boarding boarding(Player player) { return BOARDING.get(player.getUUID()); }
    public static void prepareBoarding(ServerPlayer player) {
        if (!JOINED.add(player.getUUID()) || !player.isAlive() || player.isSpectator()) return;
        // A newly joined client may not have received the hull before vanilla starts gravity.
        for (Ship ship : ships(player.level())) {
            AABB feet = player.getBoundingBox().inflate(.05, 1, .05);
            for (var shape : ShipCollision.shapes(ship, ship.pose, feet)) {
                AABB surface = shape.bounds();
                if (Math.abs(surface.maxY - player.getY()) > .6 || surface.maxY > player.getY() + .3) continue;
                Vec3 at = new Vec3(player.getX(), surface.maxY, player.getZ());
                BOARDING.put(player.getUUID(), new Boarding(ship.id, ship.worldToBlock(at)));
                return;
            }
        }
    }
    public static void completeBoarding(ServerPlayer player, UUID id) {
        Boarding boarding = BOARDING.get(player.getUUID());
        if (boarding == null || !boarding.ship.equals(id)) return;
        Ship ship = get(player.getServer(), id);
        BOARDING.remove(player.getUUID());
        if (ship == null || !ship.dimension.equals(player.level().dimension())) return;
        Vec3 feet = ship.blockToWorld(boarding.localFeet);
        player.connection.teleport(feet.x, feet.y, feet.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true); player.fallDistance = 0;
        attachment(player).attach(ship.id);
    }

    private static ShipAttachment attachment(Entity entity) {
        return ((ShipAttachmentAccess) entity).skydock$attachment();
    }
    private static boolean attachable(Entity entity) {
        return entity.isAlive() && !entity.isSpectator() && !entity.noPhysics && !entity.isPassenger()
                && (!(entity instanceof Player player) || !player.getAbilities().flying);
    }
    private static Ship findShip(Level level, UUID id) {
        if (id == null) return null;
        return ships(level).stream().filter(ship -> ship.id.equals(id)).findFirst().orElse(null);
    }
    public static void attach(Entity entity, Ship ship) { attachment(entity).attach(ship.id); }
    public static Ship attachedShip(Entity entity) {
        ShipAttachment attachment = attachment(entity);
        Ship ship = findShip(entity.level(), attachment.ship());
        if (ship == null || !attachable(entity)) {
            attachment.detach(entity, false);
            return null;
        }
        return ship;
    }
    /** Called immediately before LocalPlayer emits its vanilla movement packet. */
    public static ShipMovementFrame.Frame movementFrame(Entity entity, double shipTime) {
        ShipAttachment attachment = attachment(entity);
        Ship ship = attachedShip(entity);
        if (ship == null) {
            ship = ships(entity.level()).stream()
                    .filter(candidate -> ShipCollision.supported(candidate, candidate.pose, entity.getBoundingBox()))
                    .findFirst().orElse(null);
            if (ship == null) return ShipMovementFrame.Frame.none(0);
            attachment.attach(ship.id);
        }
        Vec3 localFeet = ship.worldToBlock(entity.position());
        boolean departing = !ShipCollision.aboveHull(ship, ship.pose, entity.getBoundingBox());
        ShipMovementFrame.Frame frame = new ShipMovementFrame.Frame(0, ship.id, shipTime, localFeet, departing);
        if (departing) attachment.detach(entity, true);
        return frame;
    }
    public static void receiveMovementFrame(ServerPlayer player, ShipMovementFrame.Frame frame) {
        attachment(player).receive(frame);
    }
    /** Re-expresses a verified client-frame target in the server's current ship pose. */
    public static ServerboundMovePlayerPacket rebaseMovement(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        ShipAttachment attachment = attachment(player);
        if (!packet.hasPosition()) return packet;
        ShipMovementFrame.Frame frame = attachment.consume();
        if (frame == null || frame.ship() == null || !attachable(player)) return packet;
        Ship ship = findShip(player.level(), frame.ship());
        if (ship == null) return packet;
        Vec3 packetPosition = new Vec3(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()));
        UUID attached = attachment.ship();
        Vec3 rebased;
        if (attached == null) {
            rebased = ShipMovementFrame.rebase(frame, frame.ship(), attachment.lastAcceptedSequence(),
                    ship.history, ship.pose, ship.center(), packetPosition);
            if (rebased == null) return packet;
            ShipPose historical = ship.history.sample(frame.shipTime());
            AABB historicalBox = player.getBoundingBox().move(packetPosition.subtract(player.position()));
            AABB currentBox = player.getBoundingBox().move(rebased.subtract(player.position()));
            if (!ShipCollision.supported(ship, historical, historicalBox)
                    || !ShipCollision.supported(ship, ship.pose, currentBox)) return packet;
            attachment.attach(ship.id);
        } else {
            rebased = ShipMovementFrame.rebase(frame, attached, attachment.lastAcceptedSequence(),
                    ship.history, ship.pose, ship.center(), packetPosition);
            if (rebased == null) return packet;
        }
        attachment.accepted(frame.sequence());
        attachment.detachAfterMovement(frame.departing());
        attachment.acceptedMovementTarget(rebased);
        if (packet instanceof ServerboundMovePlayerPacket.PosRot)
            return new ServerboundMovePlayerPacket.PosRot(rebased.x, rebased.y, rebased.z,
                    packet.getYRot(player.getYRot()), packet.getXRot(player.getXRot()), packet.isOnGround());
        return new ServerboundMovePlayerPacket.Pos(rebased.x, rebased.y, rebased.z, packet.isOnGround());
    }
    public static void finishMovement(ServerPlayer player) {
        ShipAttachment attachment = attachment(player);
        Vec3 target = attachment.consumeAcceptedMovementTarget();
        boolean departing = attachment.consumeDetachAfterMovement();
        if (target != null && player.position().distanceToSqr(target) > 1e-6) {
            Ship ship = findShip(player.level(), attachment.ship());
            if (ship != null) ShipNetwork.correctDeck(player, ship, ship.worldToBlock(player.position()));
            return;
        }
        if (departing) attachment.detach(player, true);
    }
    public static void tell(Player player, String text) { player.displayClientMessage(Component.literal(text), false); }
    public static void tell(Player player, Component text) { player.displayClientMessage(text, false); }
    private static DockBlockEntity dock(ServerPlayer player, BlockPos pos) {
        if (player.level().dimension().equals(SHIPYARD)) { tell(player, "Docks cannot operate inside the shipyard."); return null; }
        if (!(player.level().getBlockEntity(pos) instanceof DockBlockEntity dock)) { tell(player, "No dock controller at that position."); return null; }
        if (!canReachDock(player, dock)) { tell(player, "Stand within 8 blocks of the dock controller, or use a helm inside its envelope."); return null; }
        if (dock.owner == null) dock.claim(player);
        if (!dock.canManage(player)) { tell(player, "Only the dock owner or their team can manage this berth."); return null; }
        return dock;
    }
    public static boolean permitted(Player player, Ship ship) {
        return ship.owner != null && (ship.owner.equals(player.getUUID()) || (!ship.team.isEmpty() && player.getTeam() != null && ship.team.equals(player.getTeam().getName())));
    }
    public static void inspect(ServerPlayer player, BlockPos pos) {
        DockBlockEntity dock = dock(player, pos); if (dock == null) return;
        DockValidation result = DockValidation.scan(player.serverLevel(), dock);
        if (!result.blocks().isEmpty()) dock.markManualHull(result.blocks().size());
        else if (dock.manualHull() || dock.assemblyComplete()) dock.clearHullMarker("Ready.");
        tell(player, result.summary());
        Direction facing = dock.facing();
        BlockPos min = dock.tier().origin(pos, facing);
        BlockPos max = dock.tier().toWorld(pos, facing, new BlockPos(dock.tier().width - 1, dock.tier().height - 1, dock.tier().length - 1));
        tell(player, "Interior: " + min.toShortString() + " through " + max.toShortString());
    }
    public static void launch(ServerPlayer player, BlockPos pos) {
        DockBlockEntity dock = dock(player, pos); if (dock == null) return;
        if (dock.assemblyJob() != null) { tell(player, "Wait for assembly to finish before launching."); return; }
        if (settling(player, pos)) return;
        MinecraftServer server = player.getServer(); ShipSavedData data = ShipSavedData.get(server);
        if (data.ships.values().stream().anyMatch(s -> s.dimension.equals(player.level().dimension()) && s.dock.equals(pos))) { tell(player, "This berth is reserved by its launched ship."); return; }
        DockValidation result = DockValidation.scan(player.serverLevel(), dock);
        if (!result.valid()) { tell(player, result.summary()); return; }
        if (server.getLevel(SHIPYARD) == null) { tell(player, "Shipyard dimension is missing. Restore the Skydock datapack and restart."); return; }
        Direction facing = dock.facing();
        Ship ship = new Ship(); ship.owner = dock.owner; ship.team = dock.team; ship.tier = dock.tier();
        ship.dimension = player.level().dimension(); ship.dock = pos.immutable(); ship.dockFacing = facing;
        ship.blocks.putAll(result.blocks()); ship.mass = result.mass(); ship.lift = result.lift(); ship.cells = result.cells(); ship.engines = result.engines();
        ship.initializePivot();
        Vec3 berth = ship.tier.berthCenter(pos, facing, ship.center());
        ship.pose = new ShipPose(berth.x, berth.y, berth.z, DockTier.berthYaw(facing));
        ship.previousPose = ship.pose;
        if (ships(player.level()).stream().anyMatch(s -> s.bounds().intersects(ship.bounds()))) { tell(player, "Another ship occupies the launch envelope."); return; }
        int region = data.nextRegion++;
        if (region >= 1_000_000) { tell(player, "Shipyard region capacity reached."); return; }
        ship.yard = new BlockPos((region % 1000) * 256, 64, (region / 1000) * 256);
        ship.transferDock = pos; ship.transferFacing = facing; ship.transferBerthTier = ship.tier; ship.transferShift = BlockPos.ZERO;
        ShipTransfer.snapshotBerth(ship, player.serverLevel(), pos, facing, ship.tier, BlockPos.ZERO);
        ship.phase = "launching"; ship.transferOrigin = ship.tier.origin(pos, facing); ship.transferDimension = ship.dimension;
        data.ships.put(ship.id, ship); ShipTransfer.flush(server);
        try {
            forceTickets(server, ship); ShipTransfer.complete(server, ship); transferred(player, pos); ShipNetwork.resync(server);
            dock.clearHullMarker("Ship launched.");
            tell(player, "Launched " + ship.blocks.size() + " real blocks. Use the helm to take control; your berth remains reserved.");
        } catch (Exception ex) {
            Skydock.LOGGER.error("Launch retained in journal for {}", ship.id, ex);
            tell(player, "Launch paused for recovery. The transfer journal is saved; see the server log.");
        }
    }
    public static void redockNearest(ServerPlayer player, BlockPos pos) {
        DockBlockEntity dock = dock(player, pos); if (dock == null) return;
        if (dock.assemblyJob() != null) { tell(player, "Cancel or finish the active assembly before redocking."); return; }
        if (settling(player, pos)) return;
        Ship ship = ships(player.level()).stream().filter(s -> permitted(player, s))
                .min(Comparator.comparingDouble(s -> s.bounds().getCenter().distanceToSqr(Vec3.atCenterOf(pos)))).orElse(null);
        if (ship == null) { tell(player, "No owned ship is available in this dimension."); return; }
        if (dock.tier().ordinal() < ship.tier.ordinal()) { tell(player, "The berth must be the same tier or larger."); return; }
        ShipSavedData data = ShipSavedData.get(player.getServer());
        if (data.ships.values().stream().anyMatch(s -> !s.id.equals(ship.id) && s.dimension.equals(ship.dimension) && s.dock.equals(pos))) { tell(player, "This berth is reserved by another ship."); return; }
        Direction facing = dock.facing();
        BlockPos shift = dock.tier().centerOffset(ship.tier);
        Vec3 destination = dock.tier().berthCenter(pos, facing, Vec3.atLowerCornerOf(shift).add(ship.center()));
        float berthYaw = DockTier.berthYaw(facing);
        double yawError = Math.abs(net.minecraft.util.Mth.wrapDegrees(ship.pose.yaw() - berthYaw));
        if (destination.distanceToSqr(new Vec3(ship.pose.x(), ship.pose.y(), ship.pose.z())) > 16 || yawError > 10 || ship.velocity.length() > .08) {
            tell(player, "Align within 4 blocks of the berth center, yaw within 10 degrees of the berth facing, and slow below 1.6 blocks/second."); return;
        }
        ServerLevel world = player.serverLevel();
        for (BlockPos p : ship.blocks.keySet()) {
            BlockPos at = dock.tier().toWorld(pos, facing, shift.offset(p));
            if (!world.getBlockState(at).isAir()) { tell(player, "The berth is obstructed at " + at.toShortString() + ". Clear it first."); return; }
        }
        refresh(player.getServer(), ship); ShipTransfer.snapshot(ship, player.getServer().getLevel(SHIPYARD), ship.yard);
        ShipPose old = ship.pose; ship.pose = new ShipPose(destination.x, destination.y, destination.z, berthYaw);
        carry(world, ship, old);
        ship.velocity = Vec3.ZERO; ship.pilot = null; ship.seated.clear();
        ship.dock = pos.immutable(); ship.dockFacing = facing;
        ship.phase = "redocking"; ship.transferOrigin = dock.tier().toWorld(pos, facing, shift);
        ship.transferDock = pos; ship.transferFacing = facing; ship.transferBerthTier = dock.tier();
        ship.transferShift = shift; ship.transferDimension = world.dimension();
        fleetChanged();
        ShipTransfer.flush(player.getServer());
        try {
            ShipTransfer.complete(player.getServer(), ship); transferred(player, pos); ShipNetwork.resync(player.getServer());
            dock.markManualHull(ship.blocks.size());
            tell(player, "Redocked. Real blocks and inventories are back in the berth; rebuilding is enabled.");
        }
        catch (Exception ex) { Skydock.LOGGER.error("Redock retained in journal for {}", ship.id, ex); tell(player, "Redock paused for recovery. The transfer journal is saved."); }
    }
    public static boolean canReachDock(Player player, DockBlockEntity dock) {
        if (player.distanceToSqr(Vec3.atCenterOf(dock.getBlockPos())) <= 64) return true;
        HitResult hit = player.pick(player.blockInteractionRange(), 1, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && player.level().getBlockState(block.getBlockPos()).is(SkydockBlocks.HELM.get())
                && dock.tier().envelope(dock.getBlockPos(), dock.facing()).contains(Vec3.atCenterOf(block.getBlockPos()));
    }
    public static void deviceOnGround(ServerPlayer player, DeviceBlock.Kind kind, BlockPos pos) {
        if (kind != DeviceBlock.Kind.HELM) { tell(player, "This device operates aboard a launched ship."); return; }
        if (player.level().dimension().equals(SHIPYARD)) return;
        List<DockBlockEntity> candidates = new ArrayList<>();
        ChunkPos center = new ChunkPos(pos);
        for (int x = center.x - 7; x <= center.x + 7; x++) for (int z = center.z - 7; z <= center.z + 7; z++) {
            var chunk = player.serverLevel().getChunkSource().getChunkNow(x, z);
            if (chunk != null) for (var entity : chunk.getBlockEntities().values())
                if (entity instanceof DockBlockEntity dock && dock.tier().envelope(dock.getBlockPos(), dock.facing()).contains(Vec3.atCenterOf(pos))) candidates.add(dock);
        }
        if (candidates.isEmpty()) { tell(player, "Build this helm inside a dock envelope before launching."); return; }
        if (candidates.size() > 1) { tell(player, "Several dock envelopes cover this helm. Use the intended dock controller."); return; }
        DockBlockEntity dock = dock(player, candidates.getFirst().getBlockPos());
        if (dock != null) dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(player, dock, buffer -> buffer.writeBlockPos(dock.getBlockPos()));
    }
    public static void control(ServerPlayer player, float throttle, float steering, float climb) {
        for (Ship ship : ships(player.level())) if (player.getUUID().equals(ship.pilot)) {
            if (!Float.isFinite(throttle) || !Float.isFinite(steering) || !Float.isFinite(climb)) return;
            ship.throttle = Math.clamp(throttle, -1, 1); ship.steering = Math.clamp(steering, -1, 1); ship.climb = Math.clamp(climb, -1, 1);
            ship.lastInputTick = player.level().getGameTime();
        }
    }
    public static void release(ServerPlayer player) {
        for (Ship ship : ships(player.level())) {
            if (player.getUUID().equals(ship.pilot)) {
                ship.pilot = null; ship.pilotAnchor = null; ship.throttle = ship.steering = ship.climb = 0;
                if (ship.cruise) tell(player, Component.translatable("message.skydock.helm_released_cruise", Component.keybind("key.skydock.cruise")));
                else tell(player, "Helm released.");
            }
            ship.seated.remove(player.getUUID());
        }
    }
    public static void toggleCruise(ServerPlayer player) {
        Ship ship = ships(player.level()).stream().filter(s -> permitted(player, s))
                .filter(s -> player.getUUID().equals(s.pilot) || s.pose.toWorld(s.hullBounds()).inflate(1).contains(player.position()))
                .min(Comparator.comparingDouble(s -> player.getUUID().equals(s.pilot) ? -1 : s.pose.toWorld(s.hullBounds()).distanceToSqr(player.position()))).orElse(null);
        if (ship == null) { tell(player, "Board your ship to operate cruise control."); return; }
        if (ship.cruise) { ship.cruise = false; tell(player, "Cruise disengaged. The ship will coast to a stop."); }
        else {
            if (ship.moored) { tell(player, "Release the mooring clamp or engage the helm first."); return; }
            if (ship.engines == 0) { tell(player, "Cruise control needs a fueled engine."); return; }
            double speed = ship.velocity.dot(ShipPose.rotate(new Vec3(0, 0, -1), ship.pose.yaw()));
            double speedLimit = ShipPhysics.installedMaxSpeed(ship);
            ship.cruiseSpeed = Math.abs(speed) < .03 ? .18 : Math.clamp(speed, -speedLimit, speedLimit);
            ship.cruise = true;
            tell(player, Component.translatable("message.skydock.cruise_engaged", Component.keybind("key.forward"), Component.keybind("key.back"),
                    Component.keybind("key.skydock.release"), Component.keybind("key.skydock.cruise")));
        }
        ShipNetwork.syncPose(player.getServer(), ship);
    }
    public static void tick(MinecraftServer server) {
        ShipSavedData data = SERVERS.get(server); if (data == null) return;
        JOINED.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        BOARDING.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) prepareBoarding(player);
        ServerLevel yard = server.getLevel(SHIPYARD);
        // Region tickets load chunks, but an empty dimension otherwise stops BE ticks after 300 ticks.
        if (yard != null && data.ships.values().stream().anyMatch(s -> s.phase.equals("active"))) yard.resetEmptyTime();
        long tick = server.overworld().getGameTime();
        for (Ship ship : List.copyOf(data.ships.values())) {
            if (!ship.phase.equals("active")) continue;
            ServerLevel level = server.getLevel(ship.dimension); if (level == null) continue;
            // Spread the once-a-second hull refresh across ticks so a large fleet does not rescan all at once.
            if ((tick + Math.floorMod(ship.id.hashCode(), 20)) % 20 == 0) { forceTickets(server, ship); refresh(server, ship); }
            ship.previousPose = ship.pose;
            if (ship.pilot != null) {
                ServerPlayer pilot = server.getPlayerList().getPlayer(ship.pilot);
                if (pilot == null || !pilot.isAlive() || !permitted(pilot, ship) || pilot.level() != level || ship.pilotHelm == null ||
                        pilot.position().distanceToSqr(ship.blockToWorld(Vec3.atCenterOf(ship.pilotHelm))) > 36) ship.pilot = null;
            }
            if (ship.pilot == null) ship.pilotAnchor = null;
            if (ship.pilot == null || tick - ship.lastInputTick > 10) ship.throttle = ship.steering = ship.climb = 0;
            ShipPhysics.step(server, level, ship);
            ship.history.accept(tick, ship.pose, ship.velocity, ship.yawVelocity);
            carry(level, ship, ship.previousPose);
            ShipDamage.ram(level, ship);
            if (tick % 2 == 0) ShipNetwork.syncPose(server, ship, false);
        }
        ShipInteractions.tick(server);
        if (tick % 20 == 0) ShipNetwork.syncPlayers(server);
        if (!data.ships.isEmpty()) data.setDirty();
    }
    static void refresh(MinecraftServer server, Ship ship) {
        ServerLevel yard = server.getLevel(SHIPYARD); if (yard == null) return;
        ship.mass = 0; ship.cells = 0; ship.engines = 0; boolean changed = false;
        for (var entry : ship.blocks.entrySet()) {
            BlockPos worldPos = ship.yard.offset(entry.getKey());
            BlockState now = yard.getBlockState(worldPos);
            if (now.is(SkydockBlocks.TIMBER_RAILING.get())) {
                BlockState connected = FenceConnections.resolve(now, worldPos, yard::getBlockState, yard);
                if (connected != now) {
                    yard.setBlock(worldPos, connected, net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
                    now = connected;
                }
            }
            if (now != entry.getValue()) { entry.setValue(now); changed = true; }
            if (now.isAir()) continue;
            ship.mass += MassTable.mass(now);
            if (now.is(SkydockBlocks.LIFT_CELLS) && !now.is(SkydockBlocks.DECORATIONS)) ship.cells++;
            if (now.is(SkydockBlocks.ENGINES)) ship.engines++;
        }
        ship.lift = ship.cells * MassTable.liftPerCell();
        // syncPlayers later this tick resends only hulls whose revision moved; resync would resend every hull.
        if (changed) ship.revision++;
    }
    public static void carry(Level level, Ship ship, ShipPose previous) {
        List<Entity> riders = new ArrayList<>(level.getEntities((Entity) null,
                previous.toWorld(ship.hullBounds()).inflate(ShipCollision.ATTACHMENT_HEIGHT + 1)));
        if (!level.isClientSide) BOARDING.forEach((id, boarding) -> {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (boarding.ship.equals(ship.id) && player != null && player.level() == level && !riders.contains(player)) riders.add(player);
        });
        for (Entity entity : riders) {
            ShipAttachment attachment = attachment(entity);
            if (!attachable(entity)) { attachment.detach(entity, false); continue; }
            boolean seated = ship.seated.containsKey(entity.getUUID());
            boolean piloting = entity.getUUID().equals(ship.pilot) && ship.pilotAnchor != null;
            Boarding boarding = level.isClientSide ? null : BOARDING.get(entity.getUUID());
            boolean joining = boarding != null && boarding.ship.equals(ship.id);
            boolean attached = attachment.is(ship.id);
            if (!seated && !piloting && !joining) {
                if (attachment.ship() != null && !attached && findShip(level, attachment.ship()) == null)
                    attachment.detach(entity, false);
                if (attachment.ship() != null && !attached) continue;
                if (!attached && ShipCollision.supported(ship, previous, entity.getBoundingBox())) {
                    attachment.attach(ship.id);
                    attached = true;
                }
                if (!attached) continue;
                if (!ShipCollision.aboveHull(ship, previous, entity.getBoundingBox())) {
                    attachment.detach(entity, true);
                    continue;
                }
            } else attachment.attach(ship.id);
            Vec3 local = previous.toLocal(entity.position());
            Vec3 next = ship.pose.toWorld(local);
            if (seated) next = ship.blockToWorld(Vec3.atLowerCornerOf(ship.seated.get(entity.getUUID())).add(.5, .5, .5));
            if (piloting) next = ship.blockToWorld(ship.pilotAnchor);
            if (joining) next = ship.blockToWorld(boarding.localFeet);
            Vec3 carrierVelocity = next.subtract(entity.position());
            entity.setPos(next);
            attachment.carried(carrierVelocity);
            if (attached && !seated && !piloting && !joining) {
                double turn = net.minecraft.util.Mth.wrapDegrees(ship.pose.yaw() - previous.yaw());
                Vec3 velocity = entity.getDeltaMovement();
                Vec3 horizontal = ShipPose.rotate(new Vec3(velocity.x, 0, velocity.z), turn);
                entity.setDeltaMovement(horizontal.x, velocity.y, horizontal.z);
            }
            if (level.isClientSide && entity instanceof Player player && player.isLocalPlayer()) {
                float turn = (float) net.minecraft.util.Mth.wrapDegrees(ship.pose.yaw() - previous.yaw());
                entity.setYRot(entity.getYRot() + turn);
            }
            if (seated || piloting || joining) {
                entity.fallDistance = 0;
                entity.setDeltaMovement(Vec3.ZERO);
                entity.setOnGround(true);
            }
        }
    }
    private static Set<ChunkPos> chunks(AABB box) {
        Set<ChunkPos> result = new HashSet<>();
        for (int x = net.minecraft.util.Mth.floor(box.minX) >> 4; x <= net.minecraft.util.Mth.floor(box.maxX) >> 4; x++)
            for (int z = net.minecraft.util.Mth.floor(box.minZ) >> 4; z <= net.minecraft.util.Mth.floor(box.maxZ) >> 4; z++) result.add(new ChunkPos(x, z));
        return result;
    }
    static void forceTickets(MinecraftServer server, Ship ship) {
        ServerLevel yard = server.getLevel(SHIPYARD), world = server.getLevel(ship.dimension); if (yard == null || world == null) return;
        for (ChunkPos cp : chunks(new AABB(ship.yard).expandTowards(ship.tier.width, ship.tier.height, ship.tier.length))) yard.getChunkSource().addRegionTicket(TICKET, cp, 2, ship.id);
        // Lead along the velocity so a cruising hull finds its path already loaded.
        Set<ChunkPos> next = chunks(ship.bounds().inflate(16).expandTowards(ship.velocity.scale(40)));
        Set<ChunkPos> old = WORLD_TICKETS.getOrDefault(ship.id, Set.of());
        for (ChunkPos cp : old) if (!next.contains(cp)) world.getChunkSource().removeRegionTicket(TICKET, cp, 2, ship.id);
        for (ChunkPos cp : next) world.getChunkSource().addRegionTicket(TICKET, cp, 2, ship.id);
        WORLD_TICKETS.put(ship.id, next);
    }
    public static void releaseTickets(MinecraftServer server, Ship ship) {
        ServerLevel yard = server.getLevel(SHIPYARD), world = server.getLevel(ship.dimension);
        if (yard != null) for (ChunkPos cp : chunks(new AABB(ship.yard).expandTowards(ship.tier.width, ship.tier.height, ship.tier.length))) yard.getChunkSource().removeRegionTicket(TICKET, cp, 2, ship.id);
        if (world != null) for (ChunkPos cp : WORLD_TICKETS.getOrDefault(ship.id, Set.of())) world.getChunkSource().removeRegionTicket(TICKET, cp, 2, ship.id);
        WORLD_TICKETS.remove(ship.id);
    }
    // No client class references in the dedicated-server class loader.
    public static final class ClientShipAccess {
        public static java.util.function.Function<Level, Collection<Ship>> provider = l -> List.of();
        static Collection<Ship> ships(Level level) { return provider.apply(level); }
    }
}
