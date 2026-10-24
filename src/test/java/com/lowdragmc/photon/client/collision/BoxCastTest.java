package com.lowdragmc.photon.client.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BoxCastTest {
    private final BoxCast.Hit hit = new BoxCast.Hit();
    private static final double TOL = BoxCast.tolerance(64);

    /** A floor block [0,1]x[63,64]x[0,1], body of half extent 0.1. */
    private boolean floor(double cy, double dx, double dy) {
        return BoxCast.boxVsBox(0.5, cy, 0.5, 0.1, 0.1, 0.1, dx, dy, 0, 0, 63, 0, 1, 64, 1, TOL, TOL, TOL, hit);
    }

    @Test
    void fallingOntoAFaceHitsAtTheRightTime() {
        assertTrue(floor(64.6, 0, -1));
        assertEquals(0.5, hit.t, 1e-12);
        assertEquals(1, hit.ny);
    }

    @Test
    void roundedIntoTheFaceStillBlocksMotionIntoIt() {
        // float storage put the body 3e-6 into the floor: it must still stand on it
        assertTrue(floor(64.1 - 3e-6, 0, -0.04));
        assertEquals(0, hit.t);
        assertEquals(1, hit.ny);
    }

    @Test
    void roundedIntoTheFaceDoesNotSnagSliding() {
        assertFalse(floor(64.1 - 3e-6, 0.3, 0));
    }

    @Test
    void touchingAndLeavingIsNoContact() {
        assertFalse(floor(64.1, 0, 0.5));
        assertFalse(floor(64.1 - 3e-6, 0.2, 0.5));
    }

    @Test
    void deepOverlapIsLeftToDepenetration() {
        assertFalse(floor(63.9, 0, -0.04));
    }

    @Test
    void slidingAlongATopFaceIsNoContact() {
        assertFalse(BoxCast.boxVsBox(-0.1, 64.1, 0.5, 0.1, 0.1, 0.1, 1.2, 0, 0, 0, 63, 0, 1, 64, 1, TOL, TOL, TOL, hit));
    }

    @Test
    void fastMotionDoesNotTunnel() {
        assertTrue(BoxCast.boxVsBox(0, 0, 0, 0.05, 0.05, 0.05, 1000, 0, 0, 400, -1, -1, 401, 1, 1, 1e-7, 1e-7, 1e-7, hit));
        assertEquals((400 - 0.05) / 1000, hit.t, 1e-12);
        assertEquals(-1, hit.nx);
    }

    @Test
    void pushesOutOfABoxAlongEveryAxis() {
        var pushes = new double[6];
        assertTrue(BoxCast.pushes(0.5, 63.95, 0.5, 0.1, 0.1, 0.1, 0, 63, 0, 1, 64, 1, TOL, TOL, TOL, pushes));
        assertEquals(0.15, pushes[2], 1e-12, "+y");
        assertEquals(0.6, pushes[0], 1e-12, "+x");
        assertFalse(BoxCast.pushes(0.5, 64.1, 0.5, 0.1, 0.1, 0.1, 0, 63, 0, 1, 64, 1, TOL, TOL, TOL, pushes));
    }

    @Test
    void slidingAlongASphereIsNoContact() {
        // tangent to the sphere's top, as after a contact took the normal motion away
        assertFalse(BoxCast.sphereVsSphere(0, 1.5, 0, 0.5, 0.1, 1e-17, 0, 0, 0, 0, 1, 1e-7, hit));
    }

    @Test
    void sphereMeetsPlaneFromTheFrontOnly() {
        assertTrue(BoxCast.sphereVsPlane(0, 2, 0, 0.5, 0, -3, 0, 0, 0, 0, 0, 1, 0, 1e-7, hit));
        assertEquals(0.5, hit.t, 1e-12);
        assertFalse(BoxCast.sphereVsPlane(0, -2, 0, 0.5, 0, 3, 0, 0, 0, 0, 0, 1, 0, 1e-7, hit));
    }

    @Test
    void sphereMeetsSphere() {
        assertTrue(BoxCast.sphereVsSphere(-3, 0, 0, 0.5, 4, 0, 0, 0, 0, 0, 1, 1e-7, hit));
        assertEquals(1.5 / 4, hit.t, 1e-12);
        assertEquals(-1, hit.nx, 1e-12);
        assertFalse(BoxCast.sphereVsSphere(-3, 2, 0, 0.5, 4, 0, 0, 0, 0, 0, 1, 1e-7, hit));
    }

    @Test
    void rayHitsAndStartsInside() {
        assertTrue(BoxCast.rayVsBox(-1, 0.5, 0.5, 4, 0, 0, 0, 0, 0, 1, 1, 1, hit));
        assertEquals(0.25, hit.t, 1e-12);
        assertTrue(BoxCast.rayVsBox(0.5, 0.5, 0.5, 4, 0, 0, 0, 0, 0, 1, 1, 1, hit));
        assertEquals(0, hit.t);
        assertEquals(-1, hit.nx);
    }
}
