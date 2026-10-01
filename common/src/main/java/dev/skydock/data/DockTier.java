package dev.skydock.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public enum DockTier {
    SCOUT(32, 20, 32, 2048), BRIG(48, 28, 48, 6144),
    CRUISER(64, 36, 64, 12288), DREADNOUGHT(96, 48, 96, 24576);

    public final int width, height, length, defaultCap;
    DockTier(int width, int height, int length, int cap) {
        this.width = width; this.height = height; this.length = length; this.defaultCap = cap;
    }
    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }

    /** Controller is centered on the front edge, one block below the interior floor. Envelope extends along {@code facing}. */
    public BlockPos origin(BlockPos controller, Direction facing) {
        return toWorld(controller, facing, BlockPos.ZERO);
    }

    public AABB envelope(BlockPos controller, Direction facing) {
        BlockPos a = toWorld(controller, facing, BlockPos.ZERO);
        BlockPos b = toWorld(controller, facing, new BlockPos(width - 1, height - 1, length - 1));
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        return new AABB(minX, minY, minZ,
                Math.max(a.getX(), b.getX()) + 1,
                Math.max(a.getY(), b.getY()) + 1,
                Math.max(a.getZ(), b.getZ()) + 1);
    }

    /** Maps canonical dock-local cell coords into the world berth for {@code facing}. */
    public BlockPos toWorld(BlockPos controller, Direction facing, BlockPos local) {
        int ox = local.getX() - width / 2;
        int oy = 1 + local.getY();
        int oz = 1 + local.getZ();
        return switch (horizontal(facing)) {
            case WEST -> controller.offset(-oz, oy, ox);
            case NORTH -> controller.offset(-ox, oy, -oz);
            case EAST -> controller.offset(oz, oy, -ox);
            default -> controller.offset(ox, oy, oz);
        };
    }

    /** Maps a fractional local point (for example the ship pivot) into world space. */
    public Vec3 toWorld(BlockPos controller, Direction facing, Vec3 local) {
        double ox = local.x - width / 2.0;
        double oy = 1 + local.y;
        double oz = 1 + local.z;
        return switch (horizontal(facing)) {
            case WEST -> new Vec3(controller.getX() - oz, controller.getY() + oy, controller.getZ() + ox);
            case NORTH -> new Vec3(controller.getX() - ox, controller.getY() + oy, controller.getZ() - oz);
            case EAST -> new Vec3(controller.getX() + oz, controller.getY() + oy, controller.getZ() - ox);
            default -> new Vec3(controller.getX() + ox, controller.getY() + oy, controller.getZ() + oz);
        };
    }

    public BlockPos fromWorld(BlockPos controller, Direction facing, BlockPos world) {
        int dx = world.getX() - controller.getX();
        int dy = world.getY() - controller.getY() - 1;
        int dz = world.getZ() - controller.getZ();
        return switch (horizontal(facing)) {
            case WEST -> new BlockPos(dz + width / 2, dy, -dx - 1);
            case NORTH -> new BlockPos(-dx + width / 2, dy, -dz - 1);
            case EAST -> new BlockPos(-dz + width / 2, dy, dx - 1);
            default -> new BlockPos(dx + width / 2, dy, dz - 1);
        };
    }

    /** World AABB covering a dock-local AABB (used for pattern footprints). */
    public AABB toWorld(BlockPos controller, Direction facing, AABB local) {
        AABB result = null;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = toWorld(controller, facing, new Vec3(
                    (i & 1) == 0 ? local.minX : local.maxX,
                    (i & 2) == 0 ? local.minY : local.maxY,
                    (i & 4) == 0 ? local.minZ : local.maxZ));
            AABB point = new AABB(corner, corner);
            result = result == null ? point : result.minmax(point);
        }
        return result;
    }

    /** Rotation that takes south-canonical blockstates into the world berth facing. */
    public static Rotation rotationFromSouth(Direction facing) {
        return switch (horizontal(facing)) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Rotation inverse(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }

    /** Launch/redock yaw so local -Z (bow) points toward the controller. */
    public static float berthYaw(Direction facing) {
        return horizontal(facing).toYRot();
    }

    public Vec3 berthCenter(BlockPos controller, Direction facing, Vec3 shipCenter) {
        return toWorld(controller, facing, shipCenter);
    }

    /** Local offset that centers a smaller ship tier inside this dock's envelope. */
    public BlockPos centerOffset(DockTier ship) {
        return new BlockPos((width - ship.width) / 2, 0, (length - ship.length) / 2);
    }

    public static Direction horizontal(Direction facing) {
        return facing.getAxis().isHorizontal() ? facing : Direction.SOUTH;
    }
}
