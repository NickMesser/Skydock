package dev.skydock.block;

import dev.skydock.Skydock;
import dev.skydock.assembly.AssemblyJob;
import dev.skydock.assembly.AssemblyManager;
import dev.skydock.data.DockTier;
import dev.skydock.data.ShipPatterns;
import dev.skydock.menu.DockMenu;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

public final class DockBlockEntity extends BlockEntity implements MenuProvider {
    public UUID owner;
    public String team = "";
    private ResourceLocation selectedPattern;
    private boolean decorations = true;
    private int revision;
    private String status = "Ready.";
    private AssemblyJob assemblyJob;
    private String hullMarker = "";
    private int hullBlocks;
    public DockBlockEntity(BlockPos pos, BlockState state) { super(SkydockBlocks.DOCK_ENTITY.get(), pos, state); }
    public DockTier tier() { return ((DockControllerBlock) getBlockState().getBlock()).tier; }
    public Direction facing() { return DockControllerBlock.facing(getBlockState()); }
    public void claim(Player player) {
        owner = player.getUUID(); team = player.getTeam() == null ? "" : player.getTeam().getName(); setChanged();
    }
    public boolean canManage(Player player) {
        return owner == null || owner.equals(player.getUUID()) || (!team.isEmpty() && player.getTeam() != null && team.equals(player.getTeam().getName()));
    }
    public ResourceLocation selectedPattern() {
        if (selectedPattern == null) selectedPattern = ShipPatterns.defaultFor(tier()).id();
        return selectedPattern;
    }
    public boolean decorations() { return decorations; }
    public int revision() { return revision; }
    public String status() { return status; }
    public AssemblyJob assemblyJob() { return assemblyJob; }
    public boolean assemblyComplete() { return hullMarker.equals("assembly"); }
    public boolean manualHull() { return hullMarker.equals("manual"); }
    public int hullBlocks() { return hullBlocks; }
    public boolean setSelection(ResourceLocation pattern, boolean decorations) {
        var selected = ShipPatterns.get(pattern);
        if (selected.isEmpty() || selected.get().tier() != tier() || assemblyJob != null) return false;
        if (pattern.equals(selectedPattern()) && this.decorations == decorations) return true;
        selectedPattern = pattern; this.decorations = decorations; hullMarker = ""; hullBlocks = 0;
        revision++; status = "Ready."; setChanged(); return true;
    }
    public void setStatus(String status) { this.status = status; revision++; setChanged(); }
    public void beginAssembly(AssemblyJob job) {
        assemblyJob = job; selectedPattern = job.pattern; decorations = job.decorations;
        hullMarker = ""; hullBlocks = 0; status = "Assembly in progress."; revision++; setChanged();
    }
    public void markAssemblyProgress() { revision++; setChanged(); }
    public void finishAssembly(String status) {
        assemblyJob = null; hullMarker = ""; hullBlocks = 0; this.status = status; revision++; setChanged();
    }
    public void completeAssembly(int blocks) {
        assemblyJob = null; hullMarker = "assembly"; hullBlocks = Math.max(0, blocks);
        status = "Assembly complete. Inspect and launch when ready."; revision++; setChanged();
    }
    public void markManualHull(int blocks) {
        if (!assemblyComplete()) {
            hullMarker = "manual"; hullBlocks = Math.max(0, blocks);
            status = "Manual hull detected. Inspect and launch when ready."; revision++; setChanged();
        }
    }
    public void clearHullMarker(String status) {
        hullMarker = ""; hullBlocks = 0; this.status = status; revision++; setChanged();
    }
    public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, DockBlockEntity dock) {
        AssemblyManager.tick(level, dock);
    }
    @Override public Component getDisplayName() { return Component.translatable("block.skydock." + tier().key() + "_dock_controller"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new DockMenu(id, inventory, worldPosition); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("Team", team);
        tag.putString("Pattern", selectedPattern().toString());
        tag.putBoolean("Decorations", decorations);
        tag.putInt("Revision", revision);
        tag.putString("Status", status);
        tag.putString("HullMarker", hullMarker);
        tag.putInt("HullBlocks", hullBlocks);
        if (assemblyJob != null) tag.put("Assembly", assemblyJob.save(registries));
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        team = tag.getString("Team");
        selectedPattern = ResourceLocation.tryParse(tag.getString("Pattern"));
        decorations = !tag.contains("Decorations") || tag.getBoolean("Decorations");
        revision = Math.max(0, tag.getInt("Revision"));
        status = tag.contains("Status") ? tag.getString("Status") : "Ready.";
        hullMarker = tag.getString("HullMarker");
        if (!hullMarker.equals("assembly") && !hullMarker.equals("manual")) hullMarker = "";
        hullBlocks = Math.max(0, tag.getInt("HullBlocks"));
        assemblyJob = null;
        if (tag.contains("Assembly")) try { assemblyJob = AssemblyJob.load(tag.getCompound("Assembly"), registries); }
        catch (RuntimeException exception) { Skydock.LOGGER.error("Could not restore assembly job at {}", worldPosition, exception); }
    }
}
