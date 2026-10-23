package com.lowdragmc.photon.client.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ColliderTargetTest {

    @Test
    void aPointInItsFrameFollowsTheColliderTurningAndScaling() {
        var plane = ColliderTarget.plane(0, null, false, 1, 2, 3, 0, 1, 0)
                .framed(new double[]{2, 0, 0, 0, 1, 0, 0, 0, 1});
        var local = new double[3];
        assertTrue(plane.toLocal(3, 2.5, 3, local));
        assertArrayEquals(new double[]{1, 0.5, 0}, local, 1e-12);
        // a quarter turn about z later, moved to the origin
        var turned = ColliderTarget.plane(0, null, false, 0, 0, 0, -1, 0, 0)
                .framed(new double[]{0, 2, 0, -1, 0, 0, 0, 0, 1});
        var world = new double[3];
        turned.toWorld(local[0], local[1], local[2], world);
        assertArrayEquals(new double[]{-0.5, 2, 0}, world, 1e-12);
    }

    @Test
    void aCollapsedFrameCannotHoldAPoint() {
        var flat = ColliderTarget.sphere(0, null, false, 0, 0, 0, 1).framed(new double[]{1, 0, 0, 0, 0, 0, 0, 0, 1});
        assertFalse(flat.toLocal(1, 1, 1, new double[3]));
        var local = new double[3];
        assertTrue(ColliderTarget.sphere(0, null, false, 1, 1, 1, 1).toLocal(2, 3, 4, local), "unframed: an offset");
        assertArrayEquals(new double[]{1, 2, 3}, local, 1e-12);
    }

    @Test
    void skewedBoxAxesBecomeTheNearestCuboid() {
        // local y leans towards x, as under a rotated child of a non-uniformly scaled parent
        var box = ColliderTarget.box(0, null, false, 0, 0, 0, new double[]{2, 0, 0, 1, 1, 0, 0, 0, 1}, 1, 1, 1);
        assertNotNull(box);
        for (int a = 0; a < 3; a++) {
            assertEquals(1, dot(box.axes, a, a), 1e-12, "unit axis " + a);
            for (int b = a + 1; b < 3; b++) assertEquals(0, dot(box.axes, a, b), 1e-12, "axes " + a + " and " + b);
        }
        var panel = ColliderTarget.box(0, null, false, 0, 0, 0, new double[]{0, 0, 0, 0, 1, 0, 0, 0, 1}, 0, 1, 1);
        assertNotNull(panel, "a flat panel is still a box");
        assertEquals(1, Math.abs(panel.axes[0]), 1e-12, "its collapsed axis runs across the other two");
        assertNull(ColliderTarget.box(0, null, false, 0, 0, 0, new double[]{0, 0, 0, 0, 0, 0, 0, 0, 1}, 0, 0, 1),
                "a line is not");
    }

    @Test
    void aBrokenRadiusIsNoRadius() {
        var sphere = ColliderTarget.sphere(0, null, false, 0, 0, 0, Double.NaN);
        assertEquals(0, sphere.radius);
        var hit = new BoxCast.Hit();
        assertTrue(sphere.cast(3, 0, 0, 0.1, -5, 0, 0, 1e-7, hit), "a point at its centre");
        assertEquals(0.58, hit.t, 1e-9);
    }

    private static double dot(double[] axes, int a, int b) {
        return axes[a * 3] * axes[b * 3] + axes[a * 3 + 1] * axes[b * 3 + 1] + axes[a * 3 + 2] * axes[b * 3 + 2];
    }
}
