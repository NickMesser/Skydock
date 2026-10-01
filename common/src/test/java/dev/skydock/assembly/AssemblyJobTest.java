package dev.skydock.assembly;

import dev.skydock.data.ShipPattern;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyJobTest {
    @BeforeAll static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void journalRoundTripPreservesExactPlanProgressAndUnspentEscrow() {
        var state = Blocks.IRON_BLOCK.defaultBlockState();
        List<ShipPattern.Block> cells = List.of(
                new ShipPattern.Block(new BlockPos(2, 1, 3), state, false),
                new ShipPattern.Block(new BlockPos(3, 1, 3), state, true));
        ItemStack oversized = new ItemStack(Blocks.IRON_BLOCK);
        oversized.setCount(1024);
        oversized.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved hull plate"));
        java.util.ArrayList<ItemStack> escrow = new java.util.ArrayList<>();
        AssemblyInventory.merge(escrow, oversized);
        assertEquals(16, escrow.size());
        assertTrue(escrow.stream().allMatch(stack -> stack.getCount() <= stack.getMaxStackSize()));
        AssemblyJob job = new AssemblyJob(net.minecraft.resources.ResourceLocation.parse("skydock:test"), true,
                cells, escrow);
        assertTrue(job.spend(Blocks.IRON_BLOCK.asItem()));
        job.placed = 1;
        job.lastStep = 42;

        HolderLookup.Provider registries = HolderLookup.Provider.create(Stream.of(
                BuiltInRegistries.BLOCK.asLookup(), BuiltInRegistries.ITEM.asLookup()));
        AssemblyJob restored = AssemblyJob.load(job.save(registries), registries);

        assertEquals(job.id, restored.id);
        assertEquals(job.pattern, restored.pattern);
        assertEquals(1, restored.placed);
        assertEquals(42, restored.lastStep);
        assertEquals(cells.get(0).pos(), restored.cells.get(0).pos());
        assertEquals(cells.get(1).state(), restored.cells.get(1).state());
        assertTrue(restored.cells.get(1).decoration());
        assertEquals(1023, restored.escrow.stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(1, restored.spent.size());
        assertEquals(Component.literal("Reserved hull plate"), restored.spent.getFirst().get(DataComponents.CUSTOM_NAME));
        assertTrue(restored.escrow.stream().allMatch(stack -> stack.getCount() <= stack.getMaxStackSize()));
        assertEquals(Component.literal("Reserved hull plate"), restored.escrow.getFirst().get(DataComponents.CUSTOM_NAME));
        assertTrue(restored.spend(Blocks.IRON_BLOCK.asItem()));
        assertEquals(1022, restored.escrow.stream().mapToInt(ItemStack::getCount).sum());
    }

    @Test void freeJobSpendsWithoutEscrowAndPersistsFlag() {
        var state = Blocks.OAK_PLANKS.defaultBlockState();
        List<ShipPattern.Block> cells = List.of(new ShipPattern.Block(BlockPos.ZERO, state, false));
        AssemblyJob job = new AssemblyJob(net.minecraft.resources.ResourceLocation.parse("skydock:free"), false, cells, List.of(), true);
        assertTrue(job.free);
        assertTrue(job.spend(Blocks.OAK_PLANKS.asItem()));
        assertTrue(job.escrow.isEmpty());
        assertTrue(job.spent.isEmpty());

        HolderLookup.Provider registries = HolderLookup.Provider.create(Stream.of(
                BuiltInRegistries.BLOCK.asLookup(), BuiltInRegistries.ITEM.asLookup()));
        AssemblyJob restored = AssemblyJob.load(job.save(registries), registries);
        assertTrue(restored.free);
        assertTrue(restored.spend(Blocks.OAK_PLANKS.asItem()));
    }
}
