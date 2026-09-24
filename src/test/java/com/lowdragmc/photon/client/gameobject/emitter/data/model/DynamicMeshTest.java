package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The injection contract: a topology once, a pose per revision. Neither half fails loudly — a topology
 * that changes identity per frame rebuilds every GL buffer once a frame, and a revision that does not
 * change when the geometry did leaves the old pose on screen forever.
 *
 * <p>Drives {@link DynamicMeshCache} rather than {@link DynamicMeshSource}, which needs a frozen
 * registry; the source's own contract is asserted by {@code DynamicMeshScenario}.</p>
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

    /**
     * Deriving tangents rebuilds a weld map over the whole mesh, so it must not happen for the passes —
     * nearly all of them — that never draw with tangents.
     */
    @Test
    void aPoseDoesNotAskForTangentsUntilSomethingDrawsWithThem() {
        var provider = new SlidingTriangle();
        var asked = new int[1];
        var mesh = provider.topology().withGeometry(provider.geometry(), () -> {
            asked[0]++;
            return null;
        }, 1);

        assertEquals(0, asked[0], "building the pose asked already");
        mesh.geometry();
        mesh.indices();
        assertEquals(0, asked[0], "drawing without tangents asked anyway");

        mesh.tangents();
        assertEquals(1, asked[0]);
        mesh.tangents();
        assertEquals(1, asked[0], "asked twice for one pose");
    }

    /** A provider handing over the wrong number of tangents would be read past the end of in an upload. */
    @Test
    void tangentsOfTheWrongLengthAreDerivedInstead() {
        var provider = new SlidingTriangle();
        var mesh = provider.topology().withGeometry(provider.geometry(),
                () -> new float[]{1, 0, 0, 1}, 1);
        assertEquals(mesh.vertexCount() * PhotonMesh.FLOATS_PER_TANGENT, mesh.tangents().length);
    }

    /** These indices go into a GL element buffer, where out of range reads off the end on the GPU. */
    @Test
    void aFaceNamingAVertexThatIsNotThereIsDropped() {
        var builder = new PhotonMesh.Builder();
        for (int i = 0; i < 3; i++) {
            builder.vertex(i, 0, 0, 0, 0, 0, 0, 1, 1);
        }
        var mesh = builder
                .triangle(0, 1, 2)
                .triangle(0, 1, 9)
                .triangle(0, -1, 2)
                .build();
        assertEquals(1, mesh.triangleCount(), "only the in-range face survives");
        assertArrayEquals(new int[]{0, 1, 2}, mesh.indices());
    }

    /** Both halves of a quad go, or the surviving half keeps a pair flag pointing at another face. */
    @Test
    void droppingHalfAQuadDropsTheWholeQuad() {
        var builder = new PhotonMesh.Builder();
        for (int i = 0; i < 4; i++) {
            builder.vertex(i, 0, 0, 0, 0, 0, 0, 1, 1);
        }
        var mesh = builder.quad(0, 1, 2, 7).triangle(0, 1, 2).build();
        assertEquals(1, mesh.triangleCount());
        assertFalse(mesh.quadPaired(0), "the survivor is the lone triangle, not half a quad");
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

            // geometry() stays null: the data lives on the GPU
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
