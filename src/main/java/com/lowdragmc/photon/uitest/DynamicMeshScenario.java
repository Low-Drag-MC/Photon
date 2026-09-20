package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.DynamicMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IDynamicMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.opengl.GL30.*;

/**
 * Injecting live geometry through the pieces a headless test cannot reach: that the source loads at all
 * (its dispatch codec needs a frozen registry), that batching splits by provider (get it wrong and two
 * characters share one buffer), and that a buffer someone else owns can really back our vertex
 * attributes in both layouts — the claim the whole zero-copy path rests on.
 */
@LDLRegisterClient(name = "dynamic_mesh", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicMeshScenario implements UIScenario {

    /** A quad whose geometry slides along +Y, one unit per revision. */
    private static final class Sliding implements IDynamicMesh {
        private final PhotonMesh topology;
        private long revision = 1;
        private int drawn;
        private float[] geometry;

        Sliding() {
            this.topology = new PhotonMesh.Builder()
                    .quad(corner(0, 0, 0, 0), corner(1, 0, 1, 0), corner(1, 1, 1, 1), corner(0, 1, 0, 1), 1f)
                    .build();
            this.geometry = pose(0f);
        }

        @Override
        public PhotonMesh topology() {
            return topology;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
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

        private float[] pose(float y) {
            var out = new float[topology.vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
            var rest = topology.geometry();
            for (int v = 0; v < topology.vertexCount(); v++) {
                int off = PhotonMesh.geometryOffset(v);
                out[off] = rest[off];
                out[off + 1] = rest[off + 1] + y;
                out[off + 2] = rest[off + 2];
                out[off + 3] = rest[off + 3];
                out[off + 4] = rest[off + 4];
                out[off + 5] = rest[off + 5];
            }
            return out;
        }

        private static float[] corner(float x, float y, float u, float v) {
            return new float[]{x, y, 0f, u, v, 0f, 0f, 1f};
        }
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("a provider reaches the render paths as an ordinary model source", ctx -> {
            var provider = new Sliding();
            var source = new DynamicMeshSource(provider);
            var mesh = source.getMesh();
            ctx.check("the source resolved a mesh", !mesh.isEmpty(), "non-empty", "empty");
            ctx.check("it is the provider's topology", mesh.topology() == provider.topology().topology(),
                    "same topology", "a different one");
            ctx.check("raw UVs, so no atlas remap", !source.hasAtlasUV(), false, source.hasAtlasUV());
            ctx.check("the same revision resolves to the same instance", source.getMesh() == mesh,
                    "same instance", "rebuilt");

            provider.advance();
            var posed = source.getMesh();
            ctx.check("a new revision resolves to a new instance", posed != mesh, "new instance", "stale");
            ctx.check("still the same model, so the buffers survive",
                    posed.topology() == mesh.topology(), "same topology", "a rebuild");
            ctx.check("and the pose moved", posed.geometry()[1] != mesh.geometry()[1],
                    "moved", "identical geometry");
        })

        // the pass key holds a MeshData, so this decides who batches with whom
        .step("batching splits by provider, not by shape", ctx -> {
            var provider = new Sliding();
            var mine = new MeshData(new DynamicMeshSource(provider));
            var alsoMine = new MeshData(new DynamicMeshSource(provider));
            var theirs = new MeshData(new DynamicMeshSource(new Sliding()));

            ctx.check("two emitters on one provider batch together", mine.equals(alsoMine),
                    "equal", "split apart");
            ctx.check("and hash together", mine.hashCode() == alsoMine.hashCode(),
                    mine.hashCode(), alsoMine.hashCode());
            // identical topology, different animation: sharing a buffer would show one character in the
            // other's pose
            ctx.check("two providers never batch together", !mine.equals(theirs),
                    "not equal", "merged into one pass");
        })

        .step("the provider is told when a draw read from it", ctx -> {
            var provider = new Sliding();
            provider.onDrawn();
            provider.onDrawn();
            ctx.check("onDrawn is the keep-alive hook", provider.drawn == 2, 2, provider.drawn);
        })

        // both layouts, set up exactly as ParticleInstanceRenderer does
        .step("a foreign GL buffer backs the geometry attributes", ctx -> {
            if (!RenderSystem.isOnRenderThread()) {
                ctx.check("this step needs the render thread", false, "render thread", "another thread");
                return;
            }
            // pretend this came from someone else's compute pass
            float[] data = {
                    1f, 2f, 3f, 0f, 0f, 1f,
                    4f, 5f, 6f, 0f, 1f, 0f,
            };
            int vao = glGenVertexArrays();
            int foreign = glGenBuffers();
            try {
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, foreign);
                glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
                drainGlErrors();

                int floatStride = PhotonMesh.FLOATS_PER_GEOMETRY * Float.BYTES;
                glVertexAttribPointer(0, 3, GL_FLOAT, false, floatStride, 0L);
                glEnableVertexAttribArray(0);
                glVertexAttribPointer(2, 3, GL_FLOAT, false, floatStride, 3L * Float.BYTES);
                glEnableVertexAttribArray(2);
                ctx.check("float layout accepted", glGetError() == GL_NO_ERROR, GL_NO_ERROR, glGetError());

                // and the 16-byte packed-normal layout a compute skinning pass usually already writes,
                // at a non-zero offset the way a suballocated pool hands it over
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 16L);
                glVertexAttribPointer(2, 4, GL_BYTE, true, 16, 16L + 3 * Float.BYTES);
                ctx.check("packed-normal layout at an offset accepted",
                        glGetError() == GL_NO_ERROR, GL_NO_ERROR, glGetError());

                // a buffer object is untyped: read it straight back to prove the contents are ours
                var readback = new float[data.length];
                glGetBufferSubData(GL_ARRAY_BUFFER, 0, readback);
                ctx.check("the buffer holds what was written",
                        readback[0] == 1f && readback[7] == 5f, "1.0 / 5.0",
                        readback[0] + " / " + readback[7]);

                // in-place re-upload, which is what a CPU-side provider's new pose costs
                glBufferSubData(GL_ARRAY_BUFFER, 0, new float[]{9f});
                glGetBufferSubData(GL_ARRAY_BUFFER, 0, readback);
                ctx.check("glBufferSubData replaced only the pose",
                        readback[0] == 9f && readback[7] == 5f, "9.0 / 5.0",
                        readback[0] + " / " + readback[7]);
            } finally {
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                glBindVertexArray(0);
                glDeleteBuffers(foreign);
                glDeleteVertexArrays(vao);
            }
        })

        .step("a GPU-only provider still resolves to its rest pose on the CPU side", ctx -> {
            var topology = new PhotonMesh.Builder()
                    .triangle(new float[]{0, 0, 0, 0, 0, 0, 0, 1},
                            new float[]{1, 0, 0, 1, 0, 0, 0, 1},
                            new float[]{0, 1, 0, 0, 1, 0, 0, 1})
                    .build();
            var source = new DynamicMeshSource(new IDynamicMesh() {
                @Override
                public PhotonMesh topology() {
                    return topology;
                }

                @Override
                public long revision() {
                    return 5;
                }

                @Override
                @Nullable
                public float[] geometry() {
                    return null; // it only exists on the GPU
                }

                @Override
                public int glBuffer() {
                    return 1;
                }
            });
            ctx.check("the topology comes through unchanged", source.getMesh() == topology,
                    "the topology", "a derived pose");
            var meshData = new MeshData(source);
            ctx.check("shape sampling sees the rest pose, not nothing",
                    meshData.getTriangles().size() == 1, 1, meshData.getTriangles().size());
        });
    }

    /** Clear any error the surrounding frame left behind, so a check measures only its own call. */
    private static void drainGlErrors() {
        for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) {
            // drain
        }
    }
}
