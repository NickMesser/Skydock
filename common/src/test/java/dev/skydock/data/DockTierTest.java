package dev.skydock.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DockTierTest {
    private static final DockTier TIER = DockTier.SCOUT;
    private static final BlockPos CONTROLLER = new BlockPos(100, 64, 200);

    @Test void southMatchesLegacyEnvelope() {
        AABB box = TIER.envelope(CONTROLLER, Direction.SOUTH);
        assertEquals(new AABB(100 - 16, 65, 201, 100 - 16 + 32, 65 + 20, 201 + 32), box);
        assertEquals(new BlockPos(84, 65, 201), TIER.origin(CONTROLLER, Direction.SOUTH));
    }

    @Test void roundTripLocalWorldForEveryFacing() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (BlockPos local : BlockPos.betweenClosed(0, 0, 0, TIER.width - 1, TIER.height - 1, TIER.length - 1)) {
                if ((local.getX() + local.getY() + local.getZ()) % 17 != 0) continue;
                BlockPos world = TIER.toWorld(CONTROLLER, facing, local);
                assertEquals(local, TIER.fromWorld(CONTROLLER, facing, world), facing + " " + local);
                assertTrue(TIER.envelope(CONTROLLER, facing).contains(Vec3.atCenterOf(world)), facing + " " + world);
            }
        }
    }

    @Test void berthYawMatchesFacing() {
        assertEquals(0f, DockTier.berthYaw(Direction.SOUTH));
        assertEquals(90f, DockTier.berthYaw(Direction.WEST));
        assertEquals(180f, DockTier.berthYaw(Direction.NORTH));
        assertEquals(270f, DockTier.berthYaw(Direction.EAST));
    }

    @Test void rotationFromSouth() {
        assertEquals(Rotation.NONE, DockTier.rotationFromSouth(Direction.SOUTH));
        assertEquals(Rotation.CLOCKWISE_90, DockTier.rotationFromSouth(Direction.WEST));
        assertEquals(Rotation.CLOCKWISE_180, DockTier.rotationFromSouth(Direction.NORTH));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, DockTier.rotationFromSouth(Direction.EAST));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, DockTier.inverse(Rotation.CLOCKWISE_90));
    }

    @Test void eastWestEnvelopesSwapLengthOntoX() {
        AABB east = TIER.envelope(CONTROLLER, Direction.EAST);
        assertEquals(TIER.length, east.getXsize(), 1e-9);
        assertEquals(TIER.width, east.getZsize(), 1e-9);
        assertEquals(CONTROLLER.getX() + 1, east.minX, 1e-9);
        AABB west = TIER.envelope(CONTROLLER, Direction.WEST);
        assertEquals(CONTROLLER.getX() - TIER.length, west.minX, 1e-9);
        assertEquals(CONTROLLER.getX(), west.maxX, 1e-9);
    }

    @Test void berthCenterMatchesSouthLegacyPose() {
        Vec3 center = new Vec3(TIER.width / 2.0, 0, TIER.length / 2.0);
        Vec3 berth = TIER.berthCenter(CONTROLLER, Direction.SOUTH, center);
        BlockPos origin = TIER.origin(CONTROLLER, Direction.SOUTH);
        assertEquals(origin.getX() + center.x, berth.x, 1e-9);
        assertEquals(origin.getY(), berth.y, 1e-9);
        assertEquals(origin.getZ() + center.z, berth.z, 1e-9);
    }
}
