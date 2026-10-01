package dev.skydock.assembly;

import dev.skydock.data.ShipPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The durable receipt for an in-progress build: exact cells, spent prefix, and unspent escrow. */
public final class AssemblyJob {
    public record Cell(BlockPos pos, BlockState state, boolean decoration) {
        public Cell { pos = pos.immutable(); }
    }

    public final UUID id;
    public final ResourceLocation pattern;
    public final boolean decorations;
    public final boolean free;
    public final List<Cell> cells;
    public final List<ItemStack> escrow;
    public final List<ItemStack> spent;
    public int placed;
    public long lastStep;

    public AssemblyJob(ResourceLocation pattern, boolean decorations, List<ShipPattern.Block> blocks, List<ItemStack> escrow) {
        this(pattern, decorations, blocks, escrow, false);
    }

    public AssemblyJob(ResourceLocation pattern, boolean decorations, List<ShipPattern.Block> blocks, List<ItemStack> escrow, boolean free) {
        this(UUID.randomUUID(), pattern, decorations, free,
                blocks.stream().map(cell -> new Cell(cell.pos(), cell.state(), cell.decoration())).toList(),
                new ArrayList<>(escrow), new ArrayList<>(), 0, 0);
    }

    private AssemblyJob(UUID id, ResourceLocation pattern, boolean decorations, boolean free, List<Cell> cells,
                        List<ItemStack> escrow, List<ItemStack> spent, int placed, long lastStep) {
        this.id = id;
        this.pattern = pattern;
        this.decorations = decorations;
        this.free = free;
        this.cells = List.copyOf(cells);
        this.escrow = escrow;
        this.spent = spent;
        this.placed = Math.clamp(placed, 0, cells.size());
        this.lastStep = lastStep;
    }

    public int total() { return cells.size(); }
    public boolean complete() { return placed >= cells.size(); }

    public boolean spend(Item item) {
        if (free) return true;
        for (int i = 0; i < escrow.size(); i++) {
            ItemStack stack = escrow.get(i);
            if (!stack.is(item)) continue;
            ItemStack used = stack.copyWithCount(1);
            stack.shrink(1);
            if (stack.isEmpty()) escrow.remove(i);
            spent.add(used);
            return true;
        }
        return false;
    }

    public ItemStack undoLastSpend() {
        return spent.isEmpty() ? ItemStack.EMPTY : spent.removeLast();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Pattern", pattern.toString());
        tag.putBoolean("Decorations", decorations);
        tag.putBoolean("Free", free);
        tag.putInt("Placed", placed);
        tag.putLong("LastStep", lastStep);
        ListTag cellTags = new ListTag();
        for (Cell cell : cells) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", cell.pos().asLong());
            entry.put("State", NbtUtils.writeBlockState(cell.state()));
            entry.putBoolean("Decoration", cell.decoration());
            cellTags.add(entry);
        }
        tag.put("Cells", cellTags);
        tag.put("Escrow", saveStacks(escrow, registries));
        tag.put("Spent", saveStacks(spent, registries));
        return tag;
    }

    public static AssemblyJob load(CompoundTag tag, HolderLookup.Provider registries) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        ResourceLocation pattern = ResourceLocation.parse(tag.getString("Pattern"));
        List<Cell> cells = new ArrayList<>();
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        for (Tag raw : tag.getList("Cells", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            cells.add(new Cell(BlockPos.of(entry.getLong("Pos")), NbtUtils.readBlockState(blockLookup, entry.getCompound("State")),
                    entry.getBoolean("Decoration")));
        }
        List<ItemStack> items = loadStacks(tag.getList("Escrow", Tag.TAG_COMPOUND), registries);
        List<ItemStack> spent = loadStacks(tag.getList("Spent", Tag.TAG_COMPOUND), registries);
        return new AssemblyJob(id, pattern, tag.getBoolean("Decorations"), tag.getBoolean("Free"), cells, items, spent,
                tag.getInt("Placed"), tag.getLong("LastStep"));
    }

    private static ListTag saveStacks(List<ItemStack> stacks, HolderLookup.Provider registries) {
        ListTag result = new ListTag();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            result.add(stack.save(registries));
        }
        return result;
    }

    private static List<ItemStack> loadStacks(ListTag tags, HolderLookup.Provider registries) {
        List<ItemStack> result = new ArrayList<>();
        for (Tag raw : tags) {
            ItemStack stack = ItemStack.parseOptional(registries, (CompoundTag) raw);
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }
}
