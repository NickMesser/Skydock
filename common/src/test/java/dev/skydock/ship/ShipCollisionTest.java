package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipCollisionTest {
    @BeforeAll static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Walls at local x∈[-1,0) and [1,2) leave a 1-block corridor at x∈[0,1). */
    private static Ship corridorShip() {
        Ship ship = new Ship();
        ship.tier = DockTier.SCOUT;
        ship.blocks.put(new BlockPos(15, 1, 16), Blocks.STONE.defaultBlockState());
        ship.blocks.put(new BlockPos(17, 1, 16), Blocks.STONE.defaultBlockState());
        ship.blocks.put(new BlockPos(16, 0, 16), Blocks.STONE.defaultBlockState());
        ship.pose = new ShipPose(0, 0, 0, 0);
        ship.previousPose = ship.pose;
        return ship;
    }

    /** 0.6-wide player standing in the corridor, about to walk along +Z. */
    private static AABB playerInCorridor(Ship ship, double yaw) {
        ShipPose pose = new ShipPose(0, 0, 0, yaw);
        // Block-space center of the gap cell, player width 0.6.
        AABB blockSpace = new AABB(16.2, 1.01, 16.2, 16.8, 2.81, 16.8);
        return pose.toWorld(blockSpace.move(ship.center().scale(-1)));
    }

    @Test void oneBlockCorridorStaysWalkableAtCardinalYaw() {
        Ship ship = corridorShip();
        AABB box = playerInCorridor(ship, 0);
        Vec3 clipped = ShipCollision.collideShip(ship, new ShipPose(0, 0, 0, 0), box, new Vec3(0, 0, .4));
        assertTrue(clipped.z > .2, "expected forward motion through corridor at yaw 0, got " + clipped);
    }

    @Test void oneBlockCorridorStaysWalkableAtDiagonalYaw() {
        Ship ship = corridorShip();
        double yaw = 45;
        ShipPose pose = new ShipPose(0, 0, 0, yaw);
        ship.pose = pose;
        AABB box = playerInCorridor(ship, yaw);
        Vec3 clipped = ShipCollision.collideShip(ship, pose, box, ShipPose.rotate(new Vec3(0, 0, .4), yaw));
        double alongCorridor = ShipPose.rotate(clipped, -yaw).z;
        assertTrue(alongCorridor > .2, "expected forward motion through corridor at yaw 45, got " + clipped + " localZ=" + alongCorridor);
    }

    @Test void enclosingWorldBoxesWouldSealTheDiagonalCorridor() {
        Ship ship = corridorShip();
        ship.pose = new ShipPose(0, 0, 0, 45);
        AABB box = playerInCorridor(ship, 45);
        Vec3 motion = ShipPose.rotate(new Vec3(0, 0, .4), 45);
        AABB query = box.expandTowards(motion).inflate(.05);
        // Fat enclosing shapes from the old world-space path overlap inside the gap.
        assertTrue(ShipCollision.shapes(ship, ship.pose, query).stream()
                .anyMatch(shape -> shape.bounds().intersects(box.inflate(-1e-3))),
                "enclosing world boxes should still overlap the player at yaw 45 (documenting the bug the local path avoids)");
        Vec3 clipped = ShipCollision.collideShip(ship, ship.pose, box, motion);
        assertTrue(ShipPose.rotate(clipped, -45).z > .2, "local collide must still allow passage");
    }
}
