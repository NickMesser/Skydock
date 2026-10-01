package dev.skydock.neoforge;

import dev.skydock.Skydock;
import dev.skydock.assembly.AssemblyManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

@Mod(Skydock.ID)
public final class SkydockNeoForge {
    public SkydockNeoForge() {
        Skydock.init();
        NeoForge.EVENT_BUS.register(SkydockNeoForge.class);
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) SkydockNeoForgeClient.init();
    }

    private static boolean playerDriven(MobSpawnType type) {
        return type == MobSpawnType.SPAWN_EGG || type == MobSpawnType.COMMAND || type == MobSpawnType.BUCKET
                || type == MobSpawnType.DISPENSER || type == MobSpawnType.BREEDING || type == MobSpawnType.CONVERSION;
    }

    @SubscribeEvent
    public static void onSpawnPlacement(MobSpawnEvent.SpawnPlacementCheck event) {
        if (playerDriven(event.getSpawnType())) return;
        ServerLevel level = event.getLevel().getLevel();
        var pos = event.getPos();
        if (AssemblyManager.suppressesSpawns(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5))
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }

    @SubscribeEvent
    public static void onSpawnPosition(MobSpawnEvent.PositionCheck event) {
        if (playerDriven(event.getSpawnType())) return;
        ServerLevel level = event.getLevel().getLevel();
        if (AssemblyManager.suppressesSpawns(level, event.getX(), event.getY(), event.getZ()))
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (playerDriven(event.getSpawnType())) return;
        ServerLevel level = event.getLevel().getLevel();
        if (AssemblyManager.suppressesSpawns(level, event.getX(), event.getY(), event.getZ()))
            event.setSpawnCancelled(true);
    }
}
