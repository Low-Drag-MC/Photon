package com.lowdragmc.photon.client.collision;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The particle step as TileParticle runs it in World space — solve, store the position as a float, then
 * gravity and friction — over the scenarios that let the old vanilla-based step drop about half of all
 * particles through floors and walls.
 */
final class ContactSolverTest {

    interface Solid {
        boolean at(int x, int y, int z);
    }

    /** Full cubes wherever {@code solid} says so, plus water surfaces where {@code water} does. */
    record Cubes(Solid solid, @Nullable Solid water) implements BlockBoxSource {
        Cubes(Solid solid) {
            this(solid, null);
        }

        @Override
        public boolean collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                               int mask, @Nullable BlockFilter filter, BoxBuffer out) {
            for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++)
                for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY); y++)
                    for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
                        if ((mask & BLOCKS) != 0 && solid.at(x, y, z)
                                && x < maxX && x + 1 > minX && y < maxY && y + 1 > minY && z < maxZ && z + 1 > minZ) {
                            out.add(x, y, z, x + 1, y + 1, z + 1, BoxBuffer.BLOCK, ((long) x << 40) ^ ((long) y << 20) ^ z, null);
                        }
                        if ((mask & FLUIDS) != 0 && water != null && water.at(x, y, z)) {
                            out.add(x, y, z, x + 1, y + 0.875, z + 1, BoxBuffer.FLUID, ((long) x << 40) ^ ((long) y << 20) ^ z, null);
                        }
                    }
            return true;
        }
    }

    static final class Particle {
        float x, y, z, vx, vy, vz, size;
        int axes;
        long target = -1;
        boolean dead;
        boolean stuck;
        int impacts;
        int enters;
        int bounces, hits;
    }

    static final class World {
        CollisionScene scene;
        final ContactSolver.Params params = new ContactSolver.Params();
        float gravity = 1, friction = 1, contactFriction = 0.7f, grow = 1;
        final ContactSolver.Body body = new ContactSolver.Body();
        final Contact contact = new Contact();
        final double[] scratch = new double[3];
        final RandomSource random = RandomSource.create(7);

        World(BlockBoxSource blocks, int mask) {
            scene = new CollisionScene(blocks, mask, null, CollisionScene.NO_ENTITIES, CollisionScene.NO_COLLIDERS,
                    new CollisionScene.Stats());
        }

        World(BlockBoxSource blocks) {
            this(blocks, BlockBoxSource.BLOCKS);
        }

        World(CollisionScene scene) {
            this.scene = scene;
        }

        void tick(Particle p) {
            if (p.dead) return;
            var b = body;
            float half = p.size / 2;
            b.cx = p.x;
            b.cy = p.y;
            b.cz = p.z;
            b.hx = b.hy = b.hz = half;
            b.vx = b.svx = p.vx;
            b.vy = b.svy = p.vy;
            b.vz = b.svz = p.vz;
            b.previousAxes = p.axes;
            b.previousTarget = p.target;
            b.bounces = p.bounces;
            b.impacts = p.hits;
            params.gravityStep = 0.04 * gravity;
            ContactSolver.step(CollisionQuery.get(), scene, b, params, 1, random, new ContactSolver.Events() {
                @Override
                public void impact(Contact contact, double speed) {
                    p.impacts++;
                }

                @Override
                public void killed(Contact contact) {
                }

                @Override
                public void triggerEnter(Contact contact) {
                    p.enters++;
                }
            }, contact, scratch);
            // stored positions are floats, exactly as TileParticle keeps them
            p.x = p.x + (float) b.moveX;
            p.y = p.y + (float) b.moveY;
            p.z = p.z + (float) b.moveZ;
            p.vx = (float) b.svx;
            p.vy = (float) b.svy;
            p.vz = (float) b.svz;
            p.axes = b.axes;
            p.target = b.target;
            p.dead = b.killed;
            p.bounces = b.bounces;
            p.hits = b.impacts;
            p.stuck = b.stuck;
            if (p.stuck) return;
            p.vy -= gravity * 0.04f;
            p.vx *= friction;
            p.vy *= friction;
            p.vz *= friction;
            if (b.touched) {
                double rx = p.vx - b.contactVx, ry = p.vy - b.contactVy, rz = p.vz - b.contactVz;
                double normal = rx * b.contactNx + ry * b.contactNy + rz * b.contactNz;
                p.vx = (float) (b.contactVx + normal * b.contactNx + (rx - normal * b.contactNx) * contactFriction);
                p.vy = (float) (b.contactVy + normal * b.contactNy + (ry - normal * b.contactNy) * contactFriction);
                p.vz = (float) (b.contactVz + normal * b.contactNz + (rz - normal * b.contactNz) * contactFriction);
            }
            p.size *= grow;
        }
    }

    static List<Particle> drop(int count, double x0, int floorTop, long seed) {
        var random = new Random(seed);
        var list = new ArrayList<Particle>();
        for (int i = 0; i < count; i++) {
            var p = new Particle();
            p.size = 0.04f + random.nextFloat() * 0.56f;
            p.x = (float) (x0 + random.nextDouble() * 64);
            p.y = (float) (floorTop + 1.5 + random.nextDouble() * 2);
            p.z = (float) (random.nextDouble() * 64);
            p.vx = (random.nextFloat() - 0.5f) * 0.1f;
            p.vz = (random.nextFloat() - 0.5f) * 0.1f;
            list.add(p);
        }
        return list;
    }

    private static void assertNoneFellThrough(float bounceChance, float bounceRate) {
        for (int top : new int[]{64, 200, 300}) {
            for (double x0 : new double[]{0, 1000, 10000}) {
                var world = new World(new Cubes((x, y, z) -> y == top - 1));
                world.params.bounceChance = bounceChance;
                world.params.bounceRate = bounceRate;
                int fell = 0;
                for (var p : drop(200, x0, top, (long) (top * 7 + x0))) {
                    for (int t = 0; t < 100; t++) world.tick(p);
                    if (p.y < top - 1) fell++;
                }
                assertEquals(0, fell, "fell through a 1-block platform at y=" + top + ", x~" + x0);
            }
        }
    }

    @Test
    void nonBouncingParticlesStayOnAOneBlockPlatform() {
        assertNoneFellThrough(0, 1);
    }

    @Test
    void settlingBouncersStayOnAOneBlockPlatform() {
        assertNoneFellThrough(1, 0.5f);
    }

    @Test
    void elasticBouncersStayOnAOneBlockPlatform() {
        assertNoneFellThrough(1, 1);
    }

    @Test
    void particlesThrownAtAWallStayOutOfIt() {
        for (double x0 : new double[]{0, 1000, 10000}) {
            int wallX = (int) x0 + 70;
            var world = new World(new Cubes((x, y, z) -> x == wallX));
            world.gravity = 0;
            world.params.bounceChance = 0;
            world.contactFriction = 1;
            var random = new Random((long) x0 + 5);
            for (int i = 0; i < 200; i++) {
                var p = new Particle();
                p.size = 0.04f + random.nextFloat() * 0.56f;
                p.x = (float) (wallX - 1.5 - random.nextDouble() * 2);
                p.y = (float) (64 + random.nextDouble() * 8);
                p.z = (float) (random.nextDouble() * 64);
                p.vx = 0.2f;
                for (int t = 0; t < 100; t++) world.tick(p);
                assertTrue(p.x + p.size / 2 <= wallX + 1e-3, "pushed into the wall at x~" + x0);
            }
        }
    }

    @Test
    void growingParticlesAreKeptOutOfAWall() {
        int wallX = 70;
        var world = new World(new Cubes((x, y, z) -> x == wallX));
        world.gravity = 0;
        world.params.bounceChance = 0;
        world.contactFriction = 1;
        world.grow = 1.02f;
        var random = new Random(9);
        for (int i = 0; i < 200; i++) {
            var p = new Particle();
            p.size = 0.1f + random.nextFloat() * 0.3f;
            p.x = (float) (wallX - 1.0 - random.nextDouble());
            p.y = (float) (64 + random.nextDouble() * 8);
            p.z = (float) (random.nextDouble() * 64);
            p.vx = 0.05f;
            for (int t = 0; t < 30; t++) world.tick(p);
            assertTrue(p.x < wallX, "grew through the wall");
        }
    }

    @Test
    void stoppedParticlesSlideDownAWallInsteadOfHangingOnIt() {
        for (double face : new double[]{11, 1001, 10001}) {
            var world = new World(new Cubes((x, y, z) -> (x == (int) face && y >= 60 && y < 70) || (y == 59 && x < face)));
            world.params.bounceChance = 0;
            for (int i = 0; i < 50; i++) {
                var p = new Particle();
                p.size = 0.06f + i * 0.0074f;
                p.x = (float) (face - 0.5 - i * 0.0131);
                p.y = 65.3f;
                p.z = 0.37f;
                p.vx = 0.2f;
                for (int t = 0; t < 100; t++) world.tick(p);
                assertTrue(p.y < 61, "hung on the wall at x=" + face + ": y=" + p.y);
            }
        }
    }

    @Test
    void aParticleSpawnedInTheGroundIsLiftedOut() {
        var world = new World(new Cubes((x, y, z) -> y < 64));
        var p = new Particle();
        p.size = 0.4f;
        p.x = 1.3f;
        p.y = 63.9f;
        p.z = 2.1f;
        world.tick(p);
        assertTrue(p.y - p.size / 2 >= 64 - 1e-4, "still inside the ground: " + p.y);
    }

    @Test
    void aBouncerReportsEveryImpactUntilItRestsAndThenStaysQuiet() {
        var world = new World(new Cubes((x, y, z) -> y < 64));
        world.params.bounceRate = 0.5;
        var p = new Particle();
        p.size = 0.2f;
        p.x = 0.5f;
        p.y = 70;
        p.z = 0.5f;
        for (int t = 0; t < 200; t++) world.tick(p);
        int impacts = p.impacts;
        assertTrue(impacts >= 3 && impacts <= 12, "impacts while settling: " + impacts);
        for (int t = 0; t < 200; t++) world.tick(p);
        assertEquals(impacts, p.impacts, "a resting particle keeps reporting impacts");
        assertEquals(64.1, p.y, 1e-3);
    }

    @Test
    void particlesLandOnAWaterSurfaceButSinkWhenStartingUnderIt() {
        var cubes = new Cubes((x, y, z) -> y < 60, (x, y, z) -> y == 63);
        var world = new World(cubes, BlockBoxSource.BLOCKS | BlockBoxSource.FLUIDS);
        world.params.bounceChance = 0;
        var above = new Particle();
        above.size = 0.2f;
        above.x = 0.5f;
        above.y = 66;
        above.z = 0.5f;
        var below = new Particle();
        below.size = 0.2f;
        below.x = 0.5f;
        below.y = 63.3f;
        below.z = 0.5f;
        for (int t = 0; t < 100; t++) {
            world.tick(above);
            world.tick(below);
        }
        assertEquals(63.875 + 0.1, above.y, 1e-3, "rests on the surface");
        assertEquals(60.1, below.y, 1e-3, "sinks to the floor");
    }

    @Test
    void aFastParticleDoesNotTunnelThroughAThinWall() {
        var world = new World(new Cubes((x, y, z) -> x == 300));
        world.gravity = 0;
        var p = new Particle();
        p.size = 0.1f;
        p.x = 0.5f;
        p.y = 0.5f;
        p.z = 0.5f;
        p.vx = 450; // far past the old 100 blocks/tick cut-off
        world.params.bounceChance = 0;
        world.tick(p);
        assertTrue(p.x < 300, "tunnelled: x=" + p.x);
        assertEquals(300 - 0.05, p.x, 1e-3);
    }

    @Test
    void movingEntitiesAreHitInTheirOwnFrame() {
        // an entity box sweeps 3 blocks across a slow particle within one tick, ending on top of it
        var entity = new EntityTarget("mob", 1, 1, 0, -0.5, 2, 2, 0.5, -3, 0, 0, false);
        var scene = new CollisionScene(null, 0, null, new EntityTarget[]{entity}, CollisionScene.NO_COLLIDERS,
                new CollisionScene.Stats());
        var world = new World(scene);
        world.gravity = 0;
        var p = new Particle();
        p.size = 0.2f;
        p.x = 1.5f;
        p.y = 1;
        p.vx = 0.1f;
        world.tick(p);
        assertEquals(1, p.impacts, "the passing entity was missed");
    }

    @Test
    void anEntityWalkingIntoAParticlePushesItInsteadOfSwallowingIt() {
        var world = new World(new Cubes((x, y, z) -> y == -1));
        world.gravity = 0;
        world.params.bounceChance = 0;
        var p = new Particle();
        p.size = 0.2f;
        p.x = 2.2f;
        p.y = 0.1f;
        for (int t = 0; t < 20; t++) {
            double maxX = 2 + 0.3 * (t + 1);
            var entity = new EntityTarget("mob", 1, maxX - 1, 0, -0.5, maxX, 2, 0.5, 0.3, 0, 0, false);
            world.scene = new CollisionScene(world.scene.blocks, BlockBoxSource.BLOCKS, null, new EntityTarget[]{entity},
                    CollisionScene.NO_COLLIDERS, new CollisionScene.Stats());
            world.tick(p);
            assertTrue(p.x - 0.1 >= maxX - 1e-4, "inside the entity at tick " + t + ": x=" + p.x + ", entity to " + maxX);
        }
        assertEquals(1, p.impacts, "pushed along, it was hit once");
    }

    @Test
    void aRisingPlaneLiftsWhatRestsOnIt() {
        var world = new World(new CollisionScene(null, 0, null, CollisionScene.NO_ENTITIES, CollisionScene.NO_COLLIDERS,
                new CollisionScene.Stats()));
        world.params.bounceChance = 0;
        var p = new Particle();
        p.size = 0.2f;
        p.y = 10.1f;
        for (int t = 0; t < 40; t++) {
            double planeY = 10 + 0.1 * (t + 1);
            var plane = ColliderTarget.plane(0, null, false, 0, planeY, 0, 0, 1, 0).moving(0, 0.1, 0);
            world.scene = new CollisionScene(null, 0, null, CollisionScene.NO_ENTITIES, new ColliderTarget[]{plane},
                    new CollisionScene.Stats());
            world.tick(p);
            assertTrue(p.y >= planeY + 0.1 - 1e-3, "fell behind the plane at tick " + t + ": y=" + p.y);
        }
    }

    @Test
    void absurdSpeedsAreNotCollidedButDoNotHang() {
        var world = new World(new Cubes((x, y, z) -> x == 300));
        world.gravity = 0;
        for (float speed : new float[]{1e9f, Float.POSITIVE_INFINITY}) {
            var p = new Particle();
            p.size = 0.1f;
            p.vx = speed;
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                world.tick(p);
                world.tick(p);
            }, "speed " + speed + ", and a tick from wherever that took it");
        }
    }

    @Test
    void farAwayOrBrokenPositionsDoNotThrow() {
        var world = new World(new Cubes((x, y, z) -> y == 63 || x == 1_000_005));
        world.params.bounceChance = 0;
        for (float x : new float[]{1e5f, 1e6f, 1e7f, 2.9e7f, -2.9e7f}) {
            var p = new Particle();
            p.size = 0.2f;
            p.x = x;
            p.y = 66;
            for (int t = 0; t < 60; t++) world.tick(p);
            assertEquals(64.1, p.y, 1e-3, "landed at x " + x);
        }
        // a wall where floats step by 1/16: what rounding leaves inside its face is pushed out
        var w = new Particle();
        w.size = 0.2f;
        w.x = 1e6f;
        w.y = 64.1f;
        w.vx = 0.3f;
        world.contactFriction = 1;
        for (int t = 0; t < 60; t++) {
            world.tick(w);
            assertTrue(w.x + 0.1 <= 1_000_005 + 0.0625, "kept out of the wall: " + w.x);
        }
        assertEquals(1_000_005 - 0.1, w.x, 0.07, "against it");
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY}) {
            var p = new Particle();
            p.size = 0.2f;
            p.x = bad;
            p.y = 66;
            assertDoesNotThrow(() -> {
                for (int t = 0; t < 3; t++) world.tick(p);
            }, "at x " + bad);
        }
    }

    @Test
    void outOfContactsTheRestOfTheMotionGoesOnToTheNextSurface() {
        var world = new World(new Cubes((x, y, z) -> y == 63 || x == 10));
        world.params.maxContacts = 1;
        world.contactFriction = 1;
        var p = new Particle();
        p.size = 0.2f;
        p.x = 5;
        p.y = 64.1f;
        p.vx = 0.3f;
        for (int t = 0; t < 40; t++) world.tick(p);
        assertEquals(9.9, p.x, 1e-3, "slid on, its one contact a step spent on the floor, and stopped at the wall");
        assertEquals(64.1, p.y, 1e-3);
    }

    @Test
    void crossingATriggerDoesNotUseUpTheContactBudget() {
        var trigger = ColliderTarget.sphere(1, null, true, 5, 0, 0, 1);
        var world = new World(new CollisionScene(null, 0, null, CollisionScene.NO_ENTITIES, new ColliderTarget[]{trigger},
                new CollisionScene.Stats()));
        world.gravity = 0;
        world.params.maxContacts = 1;
        var p = new Particle();
        p.size = 0.2f;
        p.vx = 10;
        world.tick(p);
        assertEquals(1, p.enters, "the trigger was entered");
        assertEquals(10, p.x, 1e-4, "the motion stopped at the trigger");
    }

    @Test
    void pointsAndTinyParticlesStayUpOnBlockSeams() {
        for (double x : new double[]{5, 1000, 100_000}) {
            var world = new World(new Cubes((bx, by, bz) -> by == 63));
            world.params.bounceChance = 0;
            for (float size : new float[]{0, 0.01f}) {
                var p = new Particle();
                p.size = size;
                // on the corner where four floor blocks meet
                p.x = (float) x;
                p.z = 5;
                p.y = 64.5f;
                for (int t = 0; t < 100; t++) world.tick(p);
                assertTrue(p.y > 63.999, "size " + size + " fell through the seam at x=" + x + ": y=" + p.y);
            }
        }
    }

    @Test
    void aParticleSunkNearASeamIsPushedUpNotSideways() {
        var world = new World(new Cubes((x, y, z) -> y == 63));
        world.gravity = 0;
        for (float x : new float[]{0.85f, 0.95f, 1.05f}) {
            var p = new Particle();
            p.size = 0.2f;
            p.x = x;
            p.y = 63.85f;
            p.z = 0.5f;
            world.tick(p);
            assertEquals(64.1, p.y, 1e-4, "not freed at x=" + x);
            assertEquals(x, p.x, 1e-6, "pushed sideways at x=" + x);
        }
    }

    @Test
    void aGrowingParticleStaysOnAPlaneCollider() {
        var plane = ColliderTarget.plane(0, null, false, 0, 10, 0, 0, 1, 0);
        var world = new World(new CollisionScene(null, 0, null, CollisionScene.NO_ENTITIES, new ColliderTarget[]{plane},
                new CollisionScene.Stats()));
        world.params.bounceChance = 0;
        world.grow = 1.02f;
        var p = new Particle();
        p.size = 0.2f;
        p.y = 10.1f;
        for (int t = 0; t < 60; t++) world.tick(p);
        assertEquals(10 + p.size / 2, p.y, 0.05, "sank into the plane");
    }

    @Test
    void aSecondContactInOneStepMeetsTheTurnedVelocity() {
        // floor top at 64, ceiling bottom at 65: a fast drop bounces off both within one step
        var world = new World(new Cubes((x, y, z) -> y == 63 || y == 65));
        world.gravity = 0;
        var p = new Particle();
        p.size = 0.2f;
        p.y = 64.5f;
        p.vy = -1.5f;
        world.tick(p);
        assertEquals(2, p.impacts, "the ceiling contact was taken for resting");
        assertTrue(p.vy < 0, "bounced off the ceiling, so heading down again: vy=" + p.vy);
    }

    @Test
    void minImpactSpeedOnlySilencesEvents() {
        var world = new World(new Cubes((x, y, z) -> y == 63));
        world.params.dampen = 0.5;
        world.params.minImpactSpeed = 0.3;
        var p = new Particle();
        p.size = 0.2f;
        p.y = 70;
        for (int t = 0; t < 300; t++) world.tick(p);
        assertEquals(64.1, p.y, 1e-3, "slow impacts were left undamped");
    }

    @Test
    void particlesSlideOffASphereCollider() {
        var sphere = ColliderTarget.sphere(0, null, false, 0, 0, 0, 2);
        var world = new World(new CollisionScene(null, 0, null, CollisionScene.NO_ENTITIES, new ColliderTarget[]{sphere},
                new CollisionScene.Stats()));
        world.params.bounceChance = 0;
        world.contactFriction = 1;
        var p = new Particle();
        p.size = 0.2f;
        p.x = 0.3f;
        p.y = 2.11f;
        for (int t = 0; t < 60; t++) world.tick(p);
        assertTrue(p.y < 0, "stalled on the sphere: x=" + p.x + " y=" + p.y);
    }

    @Test
    void negativeGravityRestsUnderACeiling() {
        var world = new World(new Cubes((x, y, z) -> y == 70));
        world.gravity = -1;
        world.params.bounceChance = 0;
        var p = new Particle();
        p.size = 0.2f;
        p.y = 68;
        for (int t = 0; t < 100; t++) world.tick(p);
        int settled = p.impacts;
        for (int t = 0; t < 50; t++) world.tick(p);
        assertEquals(69.9, p.y, 1e-3, "not under the ceiling");
        assertEquals(settled, p.impacts, "keeps hitting the ceiling while resting against it");
    }

    @Test
    void bouncingStopsAfterMaxBounces() {
        var world = new World(new Cubes((x, y, z) -> y == 63));
        world.params.maxBounces = 2;
        var p = new Particle();
        p.size = 0.2f;
        p.y = 70;
        for (int t = 0; t < 300; t++) world.tick(p);
        assertEquals(2, p.bounces);
        assertEquals(3, p.impacts, "two bounces, then a landing that stays");
        assertEquals(64.1, p.y, 1e-3);
    }

    @Test
    void killedOnTheNthImpact() {
        var world = new World(new Cubes((x, y, z) -> y == 63));
        world.params.killAfterImpacts = 2;
        var p = new Particle();
        p.size = 0.2f;
        p.y = 70;
        for (int t = 0; t < 300 && !p.dead; t++) world.tick(p);
        assertTrue(p.dead, "outlived its second impact");
        assertEquals(2, p.impacts);
    }

    @Test
    void stickingStopsAtTheContactAndMovesWithTheSurface() {
        var wall = new World(new Cubes((x, y, z) -> x == 5));
        wall.gravity = 0;
        wall.params.stick = true;
        var p = new Particle();
        p.size = 0.2f;
        p.x = 4.5f;
        p.y = 3;
        p.vx = 1;
        wall.tick(p);
        assertTrue(p.stuck);
        assertEquals(4.9, p.x, 1e-4, "stuck where it met the wall");
        assertEquals(0, p.vx, 1e-9);

        // an entity sweeps into a still particle: it sticks and is carried for the rest of the step
        var entity = new EntityTarget("mob", 1, 1.3, 0, -0.5, 2.3, 2, 0.5, 0.3, 0, 0, false);
        var world = new World(new CollisionScene(null, 0, null, new EntityTarget[]{entity}, CollisionScene.NO_COLLIDERS,
                new CollisionScene.Stats()));
        world.gravity = 0;
        world.params.stick = true;
        var q = new Particle();
        q.size = 0.2f;
        q.x = 2.2f;
        q.y = 1;
        world.tick(q);
        assertTrue(q.stuck);
        assertEquals(Contact.ENTITY, world.body.stuckKind);
        assertEquals(0.3, q.vx, 1e-6, "moves with the entity");
        assertEquals(2.4, q.x, 1e-4, "on the entity's face where the step ends");
    }

    @Test
    void collidersBlockAndTriggersOnlyReport() {
        var plane = ColliderTarget.plane(0, null, false, 0, 10, 0, 0, 1, 0);
        var trigger = ColliderTarget.sphere(1, null, true, 0, 15, 0, 1);
        var scene = new CollisionScene(null, 0, null, CollisionScene.NO_ENTITIES,
                new ColliderTarget[]{plane, trigger}, new CollisionScene.Stats());
        var world = new World(scene);
        world.params.bounceChance = 0;
        var entered = new int[1];
        var p = new Particle();
        p.size = 0.2f;
        p.y = 20;
        for (int t = 0; t < 60; t++) {
            var b = world.body;
            b.cx = p.x;
            b.cy = p.y;
            b.cz = p.z;
            b.hx = b.hy = b.hz = 0.1;
            b.vx = b.svx = p.vx;
            b.vy = b.svy = p.vy;
            b.vz = b.svz = p.vz;
            b.previousAxes = p.axes;
            b.previousTarget = p.target;
            world.params.gravityStep = 0.04;
            ContactSolver.step(CollisionQuery.get(), scene, b, world.params, 1, world.random, new ContactSolver.Events() {
                @Override
                public void impact(Contact contact, double speed) {
                }

                @Override
                public void killed(Contact contact) {
                }

                @Override
                public void triggerEnter(Contact contact) {
                    entered[0]++;
                }
            }, world.contact, world.scratch);
            p.y += (float) b.moveY;
            p.vy = (float) b.svy - 0.04f;
            p.axes = b.axes;
            p.target = b.target;
        }
        assertEquals(1, entered[0], "the trigger sphere was entered once on the way down");
        assertEquals(10.1, p.y, 1e-3, "rests on the plane");
    }
}
