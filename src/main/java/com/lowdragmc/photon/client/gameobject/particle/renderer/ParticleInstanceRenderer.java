package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL30.*;

/**
 * GL-resource backend of {@link TileParticleRenderer}: billboard-quad or baked-model base
 * geometry plus the tile per-instance layout (pos/size/scale/rot/color/uv/light + custom data).
 * Buffer management and the draw call live in {@link InstancedRenderBackend}.
 */
class ParticleInstanceRenderer extends InstancedRenderBackend {

    private final ParticleConfig config;
    /** Effective renderer runtime (slot-or-config per field); drives render-mode-dependent geometry +
     *  layout. Custom GPU data still comes from the config. */
    private final ParticleRendererSetting.Runtime renderer;
    /** Mesh baked into the current static VBO, for hot-reload staleness checks (identity compare). */
    @Nullable
    private PhotonMesh builtMesh;
    /** Whether the current static geometry/layout was baked for Model mode (vs billboard family); a
     *  runtime renderMode override crossing this boundary forces a rebuild. */
    private boolean builtModelMode;
    /** The emitter's Tangent renderer setting, refreshed per frame by the pass. */
    private boolean wantsTangent;
    /** Whether the current static geometry carries a tangent; a change against {@link #wantsTangent}
     *  forces a rebuild (the mesh vertex layout differs). */
    private boolean builtWithTangent;

    public ParticleInstanceRenderer(ParticleConfig config, ParticleRendererSetting.Runtime renderer) {
        this.config = config;
        this.renderer = renderer;
    }

    @Nullable
    PhotonMesh getBuiltMesh() {
        return builtMesh;
    }

    boolean wasBuiltForModel() {
        return builtModelMode;
    }

    boolean wasBuiltWithTangent() {
        return builtWithTangent;
    }

    boolean wantsTangent() {
        return wantsTangent;
    }

    void setWantsTangent(boolean wantsTangent) {
        this.wantsTangent = wantsTangent;
    }

    @Override
    protected int initialInstanceCapacity() {
        return config.getMaxParticles();
    }

    @Override
    protected void createStaticGeometry(InstanceResource resource) {
        this.builtModelMode = renderer.getRenderMode() == ParticleRendererSetting.Mode.Model;
        this.builtWithTangent = wantsTangent;
        if (renderer.getRenderMode() == ParticleRendererSetting.Mode.Model) {
            var source = renderer.getModelSource();
            var mesh = source.getMesh();
            var remapUV = source.hasAtlasUV() && !renderer.isUseBlockUV();
            var shade = renderer.isShade();

            // With tangents: pos 3, uv 2, normal+brightness 4, tangent+handedness 4 — brightness and
            // handedness ride in the w of the two vec4s so the tangent costs no EXTRA attribute location
            // (per-instance attributes stay at 4..8, TILE_MODEL's channel base stays at 9).
            // Without: pos 3, uv 2, normal 3, brightness 1 — byte-identical to the pre-tangent layout.
            // MIRRORED FROM the PARTICLE_MODEL_INSTANCE block of particle.glsl (keep in lockstep).
            int floatsPerVertex = wantsTangent ? 3 + 2 + 4 + 4 : 3 + 2 + 3 + 1;
            int quadCount = mesh.quadCount();
            var vertexBuffer = BufferUtils.createFloatBuffer(quadCount * 4 * floatsPerVertex);
            var indexBuffer = BufferUtils.createIntBuffer(quadCount * 6);
            var vertexBase = 0;
            var pivotPoint = renderer.getModelPivot();
            var vertices = mesh.vertices();
            // only touched when the emitter asked for tangents — the mesh generates them on first access
            var tangents = wantsTangent ? mesh.tangents() : null;
            var bounds = mesh.spriteBounds();

            for (int quad = 0; quad < quadCount; quad++) {
                var brightness = shade ? mesh.shadeBrightness(quad) : 1f;
                float u0 = 0, v0 = 0, uw = 1, vh = 1;
                if (remapUV) {
                    u0 = bounds[quad * 4];
                    v0 = bounds[quad * 4 + 1];
                    uw = bounds[quad * 4 + 2] - u0;
                    vh = bounds[quad * 4 + 3] - v0;
                }

                for (int corner = 0; corner < 4; corner++) {
                    int off = PhotonMesh.vertexOffset(quad, corner);
                    int tan = PhotonMesh.tangentOffset(quad, corner);
                    var u = vertices[off + 3];
                    var v = vertices[off + 4];
                    if (remapUV) {
                        u = (u - u0) / uw;
                        v = (v - v0) / vh;
                    }

                    vertexBuffer.put(vertices[off] + pivotPoint.x)
                            .put(vertices[off + 1] + pivotPoint.y)
                            .put(vertices[off + 2] + pivotPoint.z); // pos
                    vertexBuffer.put(u).put(v); // uv
                    vertexBuffer.put(vertices[off + 5]).put(vertices[off + 6]).put(vertices[off + 7]); // normal
                    vertexBuffer.put(brightness); // brightness (aNormal.w when tangents are on)
                    if (wantsTangent) {
                        // tangent.xyz + handedness in w. The atlas->sprite UV remap above is a positive
                        // per-axis scale, so it can't rotate the tangent — no remap needed here.
                        vertexBuffer.put(tangents[tan]).put(tangents[tan + 1]).put(tangents[tan + 2])
                                .put(tangents[tan + 3]);
                    }
                }

                // index (triangles are degenerate quads — the second triangle has zero area)
                indexBuffer.put(vertexBase).put(vertexBase + 1).put(vertexBase + 2);
                indexBuffer.put(vertexBase + 2).put(vertexBase + 3).put(vertexBase);
                vertexBase += 4;
            }

            vertexBuffer.flip();
            indexBuffer.flip();

            resource.modelVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_DYNAMIC_DRAW);
            int stride = floatsPerVertex * Float.BYTES;
            int offset = 0;

            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, offset); // position
            glEnableVertexAttribArray(0);
            offset += 3 * Float.BYTES;

            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, offset); // uv
            glEnableVertexAttribArray(1);
            offset += 2 * Float.BYTES;

            // with tangents location 2 widens to hold brightness in its w, freeing location 3 for the
            // tangent; without, it is the original vec3 normal + float brightness pair
            int normalSize = wantsTangent ? 4 : 3;
            glVertexAttribPointer(2, normalSize, GL_FLOAT, false, stride, offset); // normal (+ brightness in w)
            glEnableVertexAttribArray(2);
            offset += normalSize * Float.BYTES;

            glVertexAttribPointer(3, wantsTangent ? 4 : 1, GL_FLOAT, false, stride, offset); // tangent | brightness
            glEnableVertexAttribArray(3);

            resource.modelEbo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource.modelEbo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_DYNAMIC_DRAW);
            modelEboSize = 6 * quadCount;
            builtMesh = mesh;

        } else {
            // particle quad
            float[] quadVertices = {
                    // x, y, z
                    1f, -1f, 0f,
                    1f, 1f, 0f,
                    -1f, 1f, 0f,
                    -1f, -1f, 0f,
            };
            int[] quadIndices = {
                    0, 1, 2, 2, 3, 0
            };

            // bind vertex data
            resource.modelVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
            glBufferData(GL_ARRAY_BUFFER, quadVertices, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
            glEnableVertexAttribArray(0);

            // create ebo
            resource.modelEbo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource.modelEbo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, quadIndices, GL_STATIC_DRAW);
            modelEboSize = 6;
        }
    }

    @Override
    protected int instanceFloats() {
        var custom = config.additionalGPUDataSetting.attribFloats();
        return custom + (renderer.getRenderMode() == ParticleRendererSetting.Mode.Model
                ? 3 + 3 + 4 + 4 + 1        // pos scale rotation color light
                : 3 + 2 + 3 + 4 + 4 + 4 + 1); // pos size scale rotation color uv light
    }

    @Override
    protected int dataTexelsPerInstance() {
        return config.additionalGPUDataSetting.dataTexels();
    }

    @Override
    protected int customTexelsPerInstance() {
        return config.additionalGPUDataSetting.hasCustomRecord()
                ? config.additionalGPUDataSetting.customDataTexels() : 0;
    }

    @Override
    protected void defineInstanceAttributes(int stride) {
        int attribIndex;
        int offset = 0;

        if (renderer.getRenderMode() == ParticleRendererSetting.Mode.Model) {
            attribIndex = 4;
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // pos vec3
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // scale vec3
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // rotation vec4
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // color vec4
            offset = intInstanceAttrib(attribIndex++, stride, offset);      // light int
        } else {
            attribIndex = 1;
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // pos vec3
            offset = floatInstanceAttrib(attribIndex++, 2, stride, offset); // size vec2
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // scale vec3
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // rotation vec4
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // color vec4
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // uv vec4
            offset = intInstanceAttrib(attribIndex++, stride, offset);      // light int
        }

        config.additionalGPUDataSetting.layoutAttribs(offset, stride);
    }
}
