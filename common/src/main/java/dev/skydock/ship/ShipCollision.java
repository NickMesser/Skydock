package dev.skydock.ship;

import net.minecraft.core.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/**
 * Entity walking uses ship-local unrotated voxel shapes so one-block corridors stay open at any yaw.
 * {@link #shapes} still returns enclosing world boxes for ship–ship / impact broad phase.
 */
public final class ShipCollision {
    public static final double ATTACHMENT_HEIGHT = 2.5;

    /** Conservative world-space enclosure of nearby hull voxels; used by physics/damage, not player walking. */
    public static List<VoxelShape> shapes(Ship ship, ShipPose pose, AABB worldQuery) {
        ShipPose.Transform transform = pose == ship.pose ? ship.transform() : pose.transform();
        AABB hull = pose == ship.pose ? ship.hullWorldBounds() : transform.toWorld(ship.hullBounds());
        if (!hull.inflate(.05).intersects(worldQuery)) return List.of();
        List<VoxelShape> result = new ArrayList<>();
        AABB local = transform.toLocal(worldQuery).move(ship.center()).inflate(1);
        ShipBlockView view = new ShipBlockView(ship);
        for (BlockPos p : BlockPos.betweenClosed(
                new BlockPos(Math.max(0, Mth.floor(local.minX)), Math.max(0, Mth.floor(local.minY)), Math.max(0, Mth.floor(local.minZ))),
                new BlockPos(Math.min(ship.tier.width - 1, Mth.floor(local.maxX)), Math.min(ship.tier.height - 1, Mth.floor(local.maxY)), Math.min(ship.tier.length - 1, Mth.floor(local.maxZ))))) {
            BlockState state = ship.state(p); if (state.isAir()) continue;
            for (AABB box : state.getCollisionShape(view, p).toAabbs()) {
                AABB rotated = transform.toWorld(box.move(p.getX() - ship.center().x, p.getY(), p.getZ() - ship.center().z));
                if (rotated.intersects(worldQuery)) result.add(Shapes.create(rotated));
            }
        }
        return result;
    }

    /** Unrotated hull collision boxes in pivot-relative local space. */
    static List<VoxelShape> localShapes(Ship ship, AABB localQuery) {
        AABB cells = localQuery.move(ship.center()).inflate(1);
        if (cells.maxX < 0 || cells.maxY < 0 || cells.maxZ < 0
                || cells.minX >= ship.tier.width || cells.minY >= ship.tier.height || cells.minZ >= ship.tier.length)
            return List.of();
        List<VoxelShape> result = new ArrayList<>();
        ShipBlockView view = new ShipBlockView(ship);
        Vec3 center = ship.center();
        for (BlockPos p : BlockPos.betweenClosed(
                new BlockPos(Math.max(0, Mth.floor(cells.minX)), Math.max(0, Mth.floor(cells.minY)), Math.max(0, Mth.floor(cells.minZ))),
                new BlockPos(Math.min(ship.tier.width - 1, Mth.floor(cells.maxX)), Math.min(ship.tier.height - 1, Mth.floor(cells.maxY)), Math.min(ship.tier.length - 1, Mth.floor(cells.maxZ))))) {
            BlockState state = ship.state(p); if (state.isAir()) continue;
            for (AABB box : state.getCollisionShape(view, p).toAabbs()) {
                AABB local = box.move(p.getX() - center.x, p.getY(), p.getZ() - center.z);
                if (local.intersects(localQuery)) result.add(Shapes.create(local));
            }
        }
        return result;
    }

    public static boolean supported(Ship ship, ShipPose pose, AABB entity) {
        ShipPose.Transform transform = pose == ship.pose ? ship.transform() : pose.transform();
        AABB hull = pose == ship.pose ? ship.hullWorldBounds() : transform.toWorld(ship.hullBounds());
        AABB feet = new AABB(entity.minX + .03, entity.minY - .08, entity.minZ + .03, entity.maxX - .03, entity.minY + .001, entity.maxZ - .03);
        if (!hull.inflate(.05).intersects(feet)) return false;
        return !localShapes(ship, transform.toLocal(feet)).isEmpty();
    }

    /** Keeps an airborne entity in the ship frame only while real hull collision remains below its footprint. */
    public static boolean aboveHull(Ship ship, ShipPose pose, AABB entity) {
        ShipPose.Transform transform = pose == ship.pose ? ship.transform() : pose.transform();
        AABB hull = pose == ship.pose ? ship.hullWorldBounds() : transform.toWorld(ship.hullBounds());
        AABB column = new AABB(entity.minX + .03, entity.minY - ATTACHMENT_HEIGHT, entity.minZ + .03,
                entity.maxX - .03, entity.minY + .08, entity.maxZ - .03);
        if (!hull.inflate(.05).intersects(column)) return false;
        AABB localEntity = transform.toLocal(entity);
        for (VoxelShape shape : localShapes(ship, transform.toLocal(column))) {
            double top = shape.bounds().maxY;
            if (top <= localEntity.minY + .08 && top >= localEntity.minY - ATTACHMENT_HEIGHT) return true;
        }
        return false;
    }

    public static Vec3 collide(Entity entity, Vec3 requested) {
        if (entity.noPhysics || entity.isSpectator() || requested.lengthSqr() == 0) return requested;
        AABB box = entity.getBoundingBox();
        AABB area = box.expandTowards(requested).inflate(entity.maxUpStep() + .05);
        List<Ship> nearby = new ArrayList<>();
        for (Ship ship : ShipManager.ships(entity.level()))
            if (ship.hullWorldBounds().inflate(.05).intersects(area)) nearby.add(ship);
        if (nearby.isEmpty()) return requested;

        Vec3 motion = requested;
        for (Ship ship : nearby) motion = collideShip(ship, ship.pose, box, motion);

        boolean wall = motion.x != requested.x || motion.z != requested.z;
        if (wall && entity.maxUpStep() > 0 && (entity.onGround() || requested.y < 0 && motion.y != requested.y)) {
            Vec3 stepRequest = new Vec3(requested.x, entity.maxUpStep(), requested.z);
            Vec3 step = stepRequest;
            for (Ship ship : nearby) step = collideShip(ship, ship.pose, box, step);
            List<VoxelShape> world = worldShapes(entity, box.expandTowards(stepRequest).inflate(.05));
            if (!world.isEmpty()) step = resolve(box, step, world);
            if (step.horizontalDistanceSqr() > motion.horizontalDistanceSqr()) {
                Vec3 downRequest = new Vec3(0, requested.y - step.y, 0);
                Vec3 down = downRequest;
                AABB stepped = box.move(step);
                for (Ship ship : nearby) down = collideShip(ship, ship.pose, stepped, down);
                List<VoxelShape> worldDown = worldShapes(entity, stepped.expandTowards(downRequest).inflate(.05));
                if (!worldDown.isEmpty()) down = resolve(stepped, down, worldDown);
                motion = step.add(down);
            }
        }
        return motion;
    }

    /** Clip world motion against one ship's unrotated local voxels. */
    static Vec3 collideShip(Ship ship, ShipPose pose, AABB worldBox, Vec3 worldMotion) {
        ShipPose.Transform transform = pose == ship.pose ? ship.transform() : pose.transform();
        AABB hull = pose == ship.pose ? ship.hullWorldBounds() : transform.toWorld(ship.hullBounds());
        AABB swept = worldBox.expandTowards(worldMotion).inflate(.05);
        if (!hull.inflate(.05).intersects(swept)) return worldMotion;
        AABB localBox = transform.toLocal(worldBox);
        Vec3 localMotion = rotateMotion(worldMotion, transform.cos(), -transform.sin());
        List<VoxelShape> shapes = localShapes(ship, localBox.expandTowards(localMotion).inflate(.05));
        if (shapes.isEmpty()) return worldMotion;
        return rotateMotion(resolve(localBox, localMotion, shapes), transform.cos(), transform.sin());
    }

    private static Vec3 rotateMotion(Vec3 motion, double cos, double sin) {
        return new Vec3(cos * motion.x - sin * motion.z, motion.y, sin * motion.x + cos * motion.z);
    }

    private static List<VoxelShape> worldShapes(Entity entity, AABB area) {
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : entity.level().getBlockCollisions(entity, area)) shapes.add(shape);
        return shapes;
    }

    private static Vec3 resolve(AABB box, Vec3 motion, List<VoxelShape> shapes) {
        double y = Shapes.collide(Direction.Axis.Y, box, shapes, motion.y); box = box.move(0, y, 0);
        double x, z;
        if (Math.abs(motion.x) < Math.abs(motion.z)) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, motion.z); box = box.move(0, 0, z);
            x = Shapes.collide(Direction.Axis.X, box, shapes, motion.x);
        } else {
            x = Shapes.collide(Direction.Axis.X, box, shapes, motion.x); box = box.move(x, 0, 0);
            z = Shapes.collide(Direction.Axis.Z, box, shapes, motion.z);
        }
        return new Vec3(x, y, z);
    }

    public static Vec3 sneak(Entity entity, Vec3 input) {
        Ship support = ShipManager.ships(entity.level()).stream().filter(s -> supported(s, s.pose, entity.getBoundingBox())).findFirst().orElse(null);
        if (support == null) return null;
        double x = input.x, z = input.z;
        while (x != 0 && !supported(support, support.pose, entity.getBoundingBox().move(x, 0, 0))) x = shrink(x);
        while (z != 0 && !supported(support, support.pose, entity.getBoundingBox().move(0, 0, z))) z = shrink(z);
        while (x != 0 && z != 0 && !supported(support, support.pose, entity.getBoundingBox().move(x, 0, z))) { x = shrink(x); z = shrink(z); }
        return new Vec3(x, input.y, z);
    }

    private static double shrink(double v) { return Math.abs(v) < .05 ? 0 : v - Math.copySign(.05, v); }
}
