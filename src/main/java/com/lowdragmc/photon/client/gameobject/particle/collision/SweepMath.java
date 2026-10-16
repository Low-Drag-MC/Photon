package com.lowdragmc.photon.client.gameobject.particle.collision;

import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSettings;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SweepMath {
    public record Contact(double time, Vec3 normal) {}

    private SweepMath() {}

    public static boolean overlapsSphere(AABB bounds, Vec3 origin, double radius) {
        if (!(radius > 0) || !Double.isFinite(radius)) return false;
        double deltaX = origin.x - Math.clamp(origin.x, bounds.minX, bounds.maxX);
        double deltaY = origin.y - Math.clamp(origin.y, bounds.minY, bounds.maxY);
        double deltaZ = origin.z - Math.clamp(origin.z, bounds.minZ, bounds.maxZ);
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= radius * radius;
    }

    public static Contact sweep(AABB moving, Vec3 movement, AABB target) {
        double[] center = {(moving.minX + moving.maxX) * 0.5, (moving.minY + moving.maxY) * 0.5, (moving.minZ + moving.maxZ) * 0.5};
        double[] minimum = {target.minX - moving.getXsize() * 0.5, target.minY - moving.getYsize() * 0.5, target.minZ - moving.getZsize() * 0.5};
        double[] maximum = {target.maxX + moving.getXsize() * 0.5, target.maxY + moving.getYsize() * 0.5, target.maxZ + moving.getZsize() * 0.5};
        double[] velocity = {movement.x, movement.y, movement.z};
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        int axis = -1;
        double sign = 0;
        boolean inside = true;
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            inside &= center[coordinate] > minimum[coordinate] + 1e-9 && center[coordinate] < maximum[coordinate] - 1e-9;
            if (Math.abs(velocity[coordinate]) < 1e-12) {
                if (center[coordinate] <= minimum[coordinate] || center[coordinate] >= maximum[coordinate]) return null;
                continue;
            }
            double first = (minimum[coordinate] - center[coordinate]) / velocity[coordinate];
            double second = (maximum[coordinate] - center[coordinate]) / velocity[coordinate];
            double near = Math.min(first, second);
            if (near > enter) {
                enter = near;
                axis = coordinate;
                sign = velocity[coordinate] > 0 ? -1 : 1;
            }
            exit = Math.min(exit, Math.max(first, second));
            if (enter > exit) return null;
        }
        if (exit < 0 || enter > 1 || (!inside && exit <= 1e-10 && enter < 0)) return null;
        if (axis < 0) return inside ? new Contact(0, new Vec3(0, 1, 0)) : null;
        return new Contact(Math.max(0, enter), new Vec3(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0));
    }
}

