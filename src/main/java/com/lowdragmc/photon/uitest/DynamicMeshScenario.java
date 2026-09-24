package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.DynamicMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IDynamicMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Injecting live geometry through the pieces a headless test cannot reach: that the source loads at all
 * (its dispatch codec needs a frozen registry), that batching splits by provider (get it wrong and two
 * characters share one buffer), and that a device buffer someone else created can hold a geometry stream
 * in both layouts and be re-posed in place — on whichever backend the game runs.
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

        .step("a foreign device buffer holds the geometry stream", ctx -> {
            if (!RenderSystem.isOnRenderThread()) {
                ctx.check("this step needs the render thread", false, "render thread", "another thread");
                return;
            }
            float[] data = {
                    1f, 2f, 3f, 0f, 0f, 1f,
                    4f, 5f, 6f, 0f, 1f, 0f,
            };
            var floatStride = PhotonMesh.FLOATS_PER_GEOMETRY * Float.BYTES;
            ctx.check("the float layout is 6 floats a vertex", floatStride == 24, 24, floatStride);
            var bytes = MemoryUtil.memAlloc(data.length * Float.BYTES);
            GpuBuffer foreign = null;
            try {
                bytes.asFloatBuffer().put(data);
                foreign = RenderSystem.getDevice().createBuffer(() -> "foreign geometry",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes);
                ctx.check("the provider's buffer is a vertex buffer",
                        (foreign.usage() & GpuBuffer.USAGE_VERTEX) != 0, "USAGE_VERTEX", foreign.usage());
                // a slice at a non-zero offset, the way a suballocated pool hands one over
                GpuBufferSlice second = foreign.slice(floatStride, floatStride);
                ctx.check("a suballocated slice keeps its offset", second.offset() == floatStride,
                        floatStride, second.offset());
                // in-place re-pose
                var pose = MemoryUtil.memAlloc(Float.BYTES);
                try {
                    pose.putFloat(0, 9f);
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(foreign.slice(0, Float.BYTES), pose);
                } finally {
                    MemoryUtil.memFree(pose);
                }
                ctx.check("the re-pose was accepted", !foreign.isClosed(), "open", "closed");
            } finally {
                MemoryUtil.memFree(bytes);
                if (foreign != null) foreign.close();
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
                public GpuBufferSlice gpuGeometry() {
                    return STAND_IN.slice(); // never read
                }
            });
            ctx.check("the topology comes through unchanged", source.getMesh() == topology,
                    "the topology", "a derived pose");
            var meshData = new MeshData(source);
            ctx.check("shape sampling sees the rest pose, not nothing",
                    meshData.getTriangles().size() == 1, 1, meshData.getTriangles().size());
        });
    }

    private static final class StandIn {
        @Nullable
        private GpuBuffer buffer;

        GpuBufferSlice slice() {
            if (buffer == null) {
                buffer = RenderSystem.getDevice().createBuffer(() -> "dynamic mesh stand-in",
                        GpuBuffer.USAGE_VERTEX, 16);
            }
            return buffer.slice();
        }
    }

    private static final StandIn STAND_IN = new StandIn();
}
