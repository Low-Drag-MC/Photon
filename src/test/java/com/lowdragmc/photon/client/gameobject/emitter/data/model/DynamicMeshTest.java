package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The injection contract: a provider hands over a topology once and a pose per revision, and everything
 * downstream keys off those two facts. Both halves are load-bearing and neither fails loudly —
 * a topology that changes identity per frame rebuilds every GL buffer once a frame, and a revision that
 * does not change when the geometry did leaves the old pose on screen forever.
 *
 * <p>These drive {@link DynamicMeshCache} rather than {@link DynamicMeshSource}: constructing an
 * {@code IModelSource} needs a frozen Minecraft registry (its dispatch codec is a static field on the
 * interface), which a headless test has none of. The source's own contract — equality by provider
 * identity, so emitters batch together iff they read the same geometry — is asserted in-client by
 * {@code DynamicMeshScenario}.</p>
 */
class DynamicMeshTest {

    /** A provider whose one triangle slides along +Y, one unit per revision. */
    private static final class SlidingTriangle implements IDynamicMesh {
        private final PhotonMesh topology = new PhotonMesh.Builder()
                .triangle(corner(0, 0), corner(1, 0), corner(0, 1))
                .build();
        private long revision = 1;
        private int drawn;
        @Nullable
        private float[] geometry = pose(0f);

        @Override
        public PhotonMesh topology() {
            return topology;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        @Nullable
        public float[] geometry() {
            return geometry;
        }

        @Override
        public void onDrawn() {
            drawn++;
        }

        void advance() {
            geometry = pose(revision++);
        }

        /** geometry stream at offset y: three vertices of x,y,z,nx,ny,nz. */
        private static float[] pose(float y) {
            return new float[]{
                    0, y, 0, 0, 0, 1,
                    1, y, 0, 0, 0, 1,
                    0, y + 1, 0, 0, 0, 1,
            };
        }

        private static float[] corner(float x, float y) {
            return new float[]{x, y, 0f, x, y, 0f, 0f, 1f};
        }
    }

    @Test
    void withGeometrySharesTheTopologyAndTheStreamsThatDoNotDeform() {
        var base = new PhotonMesh.Builder()
                .triangle(new float[]{0, 0, 0, 0, 0, 0, 0, 1},
                        new float[]{1, 0, 0, 1, 0, 0, 0, 1},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1})
                .build();
        var posed = base.withGeometry(new float[]{
                0, 5, 0, 0, 0, 1,
                1, 5, 0, 0, 0, 1,
                0, 6, 0, 0, 0, 1,
        }, null, 7);

        assertSame(base.topology(), posed.topology(), "a pose is the same model");
        assertNotSame(base, posed, "but not the same instance — the immutability everything else assumes");
        assertSame(base.indices(), posed.indices(), "indices shared, not copied");
        assertSame(base.attributes(), posed.attributes(), "uv/shade shared, not copied");
        assertEquals(7, posed.geometryRevision());
        assertEquals(0, base.geometryRevision(), "the base is its own revision 0");
        assertEquals(5f, posed.geometry()[1], 1e-6f, "the new pose is what it reads");
        assertEquals(0f, base.geometry()[1], 1e-6f, "the base pose is untouched");
        assertEquals(base.triangleCount(), posed.triangleCount());
    }

    @Test
    void aGeometryStreamOfTheWrongLengthIsRejected() {
        var base = new PhotonMesh.Builder()
                .triangle(new float[]{0, 0, 0, 0, 0, 0, 0, 1},
                        new float[]{1, 0, 0, 1, 0, 0, 0, 1},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1})
                .build();
        // silently accepting a short array would read past the end of it in the upload, or draw
        // whatever happened to follow
        var e = assertThrows(IllegalArgumentException.class,
                () -> base.withGeometry(new float[]{0, 0, 0}, null, 1));
        assertTrue(e.getMessage().contains("3 vertices"), e.getMessage());
    }

    @Test
    void anUnchangedRevisionHandsBackTheIdenticalMesh() {
        var provider = new SlidingTriangle();
        var cache = new DynamicMeshCache();

        var first = cache.resolve(provider);
        assertSame(first, cache.resolve(provider), "identity is what the downstream caches compare");
        assertSame(provider.topology().topology(), first.topology());

        provider.advance();
        var second = cache.resolve(provider);
        assertNotSame(first, second, "a new pose has to be visible");
        assertSame(first.topology(), second.topology(), "still the same model, though");
        assertNotEquals(first.geometryRevision(), second.geometryRevision());
        assertSame(second, cache.resolve(provider), "and stable again until the next one");
    }

    /** A provider that only has the data on the GPU never produces a pose on this side. */
    @Test
    void aGpuOnlyProviderHandsBackItsTopology() {
        var topology = new PhotonMesh.Builder()
                .triangle(new float[]{0, 0, 0, 0, 0, 0, 0, 1},
                        new float[]{1, 0, 0, 1, 0, 0, 0, 1},
                        new float[]{0, 1, 0, 0, 1, 0, 0, 1})
                .build();
        var cache = new DynamicMeshCache();
        var provider = new IDynamicMesh() {
            @Override
            public PhotonMesh topology() {
                return topology;
            }

            @Override
            public long revision() {
                return 42;
            }

            @Override
            public int glBuffer() {
                return 9;
            }
        };
        assertSame(topology, cache.resolve(provider), "nothing to derive: the buffer is the renderer's business");
        assertSame(topology, cache.resolve(provider));
    }

    @Test
    void theProviderIsToldWhenItsGeometryWasDrawnFrom() {
        var provider = new SlidingTriangle();
        assertEquals(0, provider.drawn);
        provider.onDrawn();
        assertEquals(1, provider.drawn, "the hook a pooled provider pins its allocation with");
    }

}
