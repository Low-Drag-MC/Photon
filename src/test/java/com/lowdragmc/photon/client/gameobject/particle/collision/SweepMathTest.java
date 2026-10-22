package com.lowdragmc.photon.client.gameobject.particle.collision;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class SweepMathTest {
    private final AABB probe = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);

    @Test void sourceSafetyUsesEntityBoundsRatherThanOnlyTheFeetPosition() {
        AABB body = new AABB(0.4, 0.4, 0.2, 1.0, 2.2, 0.8);
        assertTrue(SweepMath.overlapsSphere(body, Vec3.ZERO, 1.25));
        assertTrue(SweepMath.overlapsSphere(body.move(-100, 30, 70), new Vec3(-100, 30, 70), 1.25));
        assertFalse(SweepMath.overlapsSphere(body.move(5, 0, 0), Vec3.ZERO, 1.25));
        assertFalse(SweepMath.overlapsSphere(body.move(0, 5, 0), Vec3.ZERO, 1.25));
    }

    @Test void sourceSafetyCanBeDisabledAndRejectsInvalidRadius() {
        assertFalse(SweepMath.overlapsSphere(probe, Vec3.ZERO, 0));
        assertFalse(SweepMath.overlapsSphere(probe, Vec3.ZERO, -1));
        assertFalse(SweepMath.overlapsSphere(probe, Vec3.ZERO, Double.NaN));
        assertFalse(SweepMath.overlapsSphere(probe, Vec3.ZERO, Double.POSITIVE_INFINITY));
    }

    @Test void fastProjectilesDoNotTunnel() {
        var hit = SweepMath.sweep(probe, new Vec3(10000, 0, 0), new AABB(500, -1, -1, 501, 1, 1));
        assertNotNull(hit);
        assertEquals(0.04995, hit.time(), 1e-12);
        assertEquals(new Vec3(-1, 0, 0), hit.normal());
    }

    @Test void diagonalNearMissDoesNotUseTheSweptBoundingBoxAsAnActualHit() {
        assertNull(SweepMath.sweep(probe, new Vec3(10, 0, 10), new AABB(0, -1, 8, 1, 1, 9)));
    }

    @Test void negativeMotionAndNegativeWorldCoordinatesWork() {
        var hit = SweepMath.sweep(probe.move(-100, 0, 0), new Vec3(-20, 0, 0), new AABB(-110, -1, -1, -109, 1, 1));
        assertNotNull(hit);
        assertEquals(0.425, hit.time(), 1e-12);
        assertEquals(new Vec3(1, 0, 0), hit.normal());
    }

    @Test void initiallyOverlappingGeometryReportsImmediateContact() {
        assertEquals(0, SweepMath.sweep(probe, new Vec3(1, 0, 0), new AABB(-1, -1, -1, 1, 1, 1)).time());
    }

    @Test void touchingSurfaceCanMoveAwayWithoutAFalseBounce() {
        assertNull(SweepMath.sweep(probe, new Vec3(-1, 0, 0), new AABB(0.5, -1, -1, 2, 1, 1)));
        assertNotNull(SweepMath.sweep(probe, new Vec3(1, 0, 0), new AABB(0.5, -1, -1, 2, 1, 1)));
    }

    @Test void touchingSurfaceCanSlideTangentially() {
        assertNull(SweepMath.sweep(probe, new Vec3(0, 0, 10), new AABB(0.5, -1, -1, 2, 1, 20)));
    }

    @Test void pointRaysAndThinCollisionShapesWork() {
        var hit = SweepMath.sweep(new AABB(Vec3.ZERO, Vec3.ZERO), new Vec3(10, 0, 0), new AABB(5, -1, -1, 5.001, 1, 1));
        assertNotNull(hit);
        assertEquals(0.5, hit.time(), 1e-12);
    }

    @Test void stationaryObjectsDoNotHitDistantGeometry() {
        assertNull(SweepMath.sweep(probe, Vec3.ZERO, new AABB(2, -1, -1, 3, 1, 1)));
        assertNotNull(SweepMath.sweep(probe, Vec3.ZERO, new AABB(-1, -1, -1, 1, 1, 1)));
    }

    @Test void reversedTraversalHasTheComplementaryExitContact() {
        Random random = new Random(7731);
        for (int sample = 0; sample < 2000; sample++) {
            double distance = 2 + random.nextDouble() * 100;
            double length = 0.1 + random.nextDouble() * 5;
            AABB target = new AABB(distance, -2, -2, distance + length, 2, 2);
            Vec3 motion = new Vec3(distance + length + 4, 0, 0);
            var forward = SweepMath.sweep(probe, motion, target);
            var backward = SweepMath.sweep(probe.move(motion), motion.scale(-1), target);
            assertNotNull(forward);
            assertNotNull(backward);
            assertEquals((distance - 0.5) / motion.x, forward.time(), 1e-10);
            assertEquals((motion.x - distance - length - 0.5) / motion.x, backward.time(), 1e-10);
        }
    }
}

