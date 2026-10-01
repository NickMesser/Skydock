package dev.skydock.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.skydock.Skydock;
import dev.skydock.assembly.AssemblyInventory;
import dev.skydock.assembly.AssemblyManager;
import dev.skydock.assembly.AssemblyJob;
import dev.skydock.block.DockBlockEntity;
import dev.skydock.data.ShipPattern;
import dev.skydock.data.ShipPatterns;
import dev.skydock.menu.DockMenu;
import dev.skydock.ship.ShipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Dock menu protocol. Every decision and displayed number is recomputed on the logical server. */
public final class DockNetwork {
    public static final int REQUEST = 0;
    public static final int SELECT = 1;
    public static final int ASSEMBLE = 2;
    public static final int CANCEL = 3;
    public static final int INSPECT = 4;
    public static final int LAUNCH = 5;
    public static final int REDOCK = 6;

    public static Consumer<CompoundTag> clientReceiver = ignored -> {};
    private static final Map<DockBlockEntity, Cached> SNAPSHOT_CACHE = new WeakHashMap<>();

    private record Cached(long tick, int revision, CompoundTag data) {}

    private DockNetwork() {}

    public record Command(int containerId, BlockPos dockPos, int expectedRevision, int action,
                          String patternId, boolean decorations) implements CustomPacketPayload {
        public static final Type<Command> TYPE = new Type<>(Skydock.id("dock_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = StreamCodec.of((buffer, packet) -> {
            buffer.writeVarInt(packet.containerId);
            buffer.writeBlockPos(packet.dockPos);
            buffer.writeVarInt(packet.expectedRevision);
            buffer.writeVarInt(packet.action);
            buffer.writeUtf(packet.patternId, 128);
            buffer.writeBoolean(packet.decorations);
        }, buffer -> new Command(buffer.readVarInt(), buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readUtf(128), buffer.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Skydock.id("dock_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buffer, packet) -> buffer.writeNbt(packet.data), buffer -> new Snapshot(buffer.readNbt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, Snapshot.TYPE, Snapshot.CODEC,
                    (packet, context) -> context.queue(() -> clientReceiver.accept(packet.data)));
        } else NetworkManager.registerS2CPayloadType(Snapshot.TYPE, Snapshot.CODEC);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, Command.TYPE, Command.CODEC,
                (packet, context) -> context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) handle(player, packet);
                }));
    }

    public static void request(int containerId, BlockPos pos, int revision) {
        send(new Command(containerId, pos, revision, REQUEST, "", false));
    }

    public static void select(int containerId, BlockPos pos, int revision, ResourceLocation pattern, boolean decorations) {
        send(new Command(containerId, pos, revision, SELECT, pattern.toString(), decorations));
    }

    public static void assemble(int containerId, BlockPos pos, int revision) {
        send(new Command(containerId, pos, revision, ASSEMBLE, "", false));
    }

    public static void cancel(int containerId, BlockPos pos, int revision) {
        send(new Command(containerId, pos, revision, CANCEL, "", false));
    }

    public static void action(int containerId, BlockPos pos, int revision, int action) {
        send(new Command(containerId, pos, revision, action, "", false));
    }

    private static void send(Command command) { NetworkManager.sendToServer(command); }

    private static void handle(ServerPlayer player, Command command) {
        if (!(player.containerMenu instanceof DockMenu menu) || menu.containerId != command.containerId
                || !menu.pos.equals(command.dockPos) || !menu.stillValid(player)
                || !(player.level().getBlockEntity(command.dockPos) instanceof DockBlockEntity dock)) return;
        if (command.action != REQUEST && command.expectedRevision != dock.revision()) { sendSnapshot(player, menu, dock); return; }
        switch (command.action) {
            case REQUEST -> {}
            case SELECT -> {
                ResourceLocation id = ResourceLocation.tryParse(command.patternId);
                if (id != null) dock.setSelection(id, command.decorations);
            }
            case ASSEMBLE -> AssemblyManager.start(player, dock);
            case CANCEL -> AssemblyManager.cancel(player.serverLevel(), dock, "Assembly cancelled by operator.");
            case INSPECT -> ShipManager.inspect(player, dock.getBlockPos());
            case LAUNCH -> {
                if (dock.assemblyJob() == null) ShipManager.launch(player, dock.getBlockPos());
                else ShipManager.tell(player, "Wait for assembly to finish before launching.");
            }
            case REDOCK -> ShipManager.redockNearest(player, dock.getBlockPos());
            default -> { return; }
        }
        sendSnapshot(player, menu, dock);
    }

    /** Logical IDs used by both vanilla menu packets and Marionette's /screen/button endpoint. */
    public static boolean handleMenuButton(ServerPlayer player, DockMenu menu, int button) {
        if (player.containerMenu != menu || !menu.stillValid(player)
                || !(player.level().getBlockEntity(menu.pos) instanceof DockBlockEntity dock)) return false;
        switch (button) {
            case 0 -> ShipManager.inspect(player, menu.pos);
            case 1 -> {
                if (dock.assemblyJob() == null) ShipManager.launch(player, menu.pos);
                else ShipManager.tell(player, "Wait for assembly to finish before launching.");
            }
            case 2 -> ShipManager.redockNearest(player, menu.pos);
            case 3 -> AssemblyManager.start(player, dock);
            case 4 -> AssemblyManager.cancel(player.serverLevel(), dock, "Assembly cancelled by operator.");
            case 5 -> dock.setSelection(dock.selectedPattern(), !dock.decorations());
            case 10, 11, 12 -> {
                int index = button - 10;
                var patterns = ShipPatterns.forTier(dock.tier());
                if (index >= patterns.size()) return false;
                dock.setSelection(patterns.get(index).id(), dock.decorations());
            }
            default -> { return false; }
        }
        sendSnapshot(player, menu, dock);
        return true;
    }

    public static void sendSnapshot(ServerPlayer player, DockMenu menu, DockBlockEntity dock) {
        long now = player.level().getGameTime();
        Cached cached = SNAPSHOT_CACHE.get(dock);
        if (cached != null && cached.revision == dock.revision() && now - cached.tick < 10) {
            CompoundTag data = cached.data.copy();
            data.putInt("Container", menu.containerId);
            NetworkManager.sendToPlayer(player, new Snapshot(data));
            return;
        }
        ShipPattern pattern = AssemblyManager.selected(dock);
        boolean decorations = dock.assemblyJob() == null ? dock.decorations() : dock.assemblyJob().decorations;
        Map<Item, Integer> cost = pattern.cost(decorations);
        Map<Item, Integer> available = AssemblyInventory.available(player.serverLevel(), dock.getBlockPos());
        AssemblyManager.Check space = AssemblyManager.validate(player.serverLevel(), dock, pattern, decorations);
        if (space.clear() && (dock.assemblyComplete() || dock.manualHull())) dock.clearHullMarker("Ready.");
        boolean resources = player.isCreative()
                || cost.entrySet().stream().allMatch(entry -> available.getOrDefault(entry.getKey(), 0) >= entry.getValue());
        CompoundTag root = new CompoundTag();
        root.putInt("Container", menu.containerId);
        root.putLong("Dock", dock.getBlockPos().asLong());
        root.putInt("Revision", dock.revision());
        root.putString("Tier", dock.tier().key());
        root.putString("Selected", pattern.id().toString());
        root.putBoolean("Decorations", decorations);
        AssemblyJob job = dock.assemblyJob();
        boolean complete = dock.assemblyComplete();
        boolean manualHull = dock.manualHull();
        int total = job == null ? pattern.included(decorations).size() : job.total();
        root.putBoolean("Active", job != null);
        root.putBoolean("Complete", complete);
        root.putBoolean("ManualHull", manualHull);
        root.putInt("HullBlocks", dock.hullBlocks());
        root.putInt("Progress", job != null ? job.placed : complete ? total : 0);
        root.putInt("Total", total);
        root.putString("Status", job != null || complete || manualHull ? dock.status() : !space.clear() ? space.message()
                : !resources ? "The adjacent chest is missing required materials."
                : player.isCreative() ? "Creative mode: materials are not required." : dock.status());
        root.putBoolean("AssemblySpaceClear", space.clear());
        root.putBoolean("SpaceClear", space.clear());
        root.putBoolean("CanAssemble", job == null && space.clear() && resources);

        ListTag patterns = new ListTag();
        for (ShipPattern choice : ShipPatterns.forTier(dock.tier())) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", choice.id().toString());
            entry.putString("Name", choice.name());
            entry.putString("Description", choice.description());
            patterns.add(entry);
        }
        root.put("Patterns", patterns);
        ListTag preview = new ListTag();
        for (ShipPattern.Block cell : pattern.included(decorations)) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", cell.pos().asLong());
            entry.putInt("State", Block.getId(cell.state()));
            preview.add(entry);
        }
        root.put("Preview", preview);
        ListTag costs = new ListTag();
        cost.entrySet().stream().sorted(Comparator.comparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).toString())).forEach(requirement -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Item", BuiltInRegistries.ITEM.getKey(requirement.getKey()).toString());
            entry.putInt("Required", requirement.getValue());
            entry.putInt("Available", available.getOrDefault(requirement.getKey(), 0));
            costs.add(entry);
        });
        root.put("Costs", costs);
        ShipPattern.Stats stats = pattern.stats(decorations);
        CompoundTag statsTag = new CompoundTag();
        statsTag.putInt("Blocks", stats.blocks());
        statsTag.putInt("StructuralBlocks", stats.structuralBlocks());
        statsTag.putDouble("Mass", stats.mass());
        statsTag.putDouble("Lift", stats.lift());
        statsTag.putInt("Cells", stats.cells());
        statsTag.putInt("Engines", stats.engines());
        statsTag.putDouble("Power", stats.power());
        statsTag.putDouble("Speed", stats.maxSpeed());
        statsTag.putInt("Width", stats.width());
        statsTag.putInt("Height", stats.height());
        statsTag.putInt("Length", stats.length());
        root.put("Stats", statsTag);
        SNAPSHOT_CACHE.put(dock, new Cached(now, dock.revision(), root.copy()));
        NetworkManager.sendToPlayer(player, new Snapshot(root));
    }
}
