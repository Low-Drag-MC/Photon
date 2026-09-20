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
 *
 * <p>In Model mode the mesh's three streams ({@link PhotonMesh}) become three static buffers, which is
 * the whole reason they are split: <b>only the geometry stream changes when a mesh deforms</b>, so a
 * dynamic mesh re-uploads that one and an external provider can supply it outright, while the UVs and
 * the tangents stay exactly where they were put.</p>
 */
class ParticleInstanceRenderer extends InstancedRenderBackend {

    private final ParticleConfig config;
    /** Effective renderer runtime (slot-or-config per field); drives render-mode-dependent geometry +
     *  layout. Custom GPU data still comes from the config. */
    private final ParticleRendererSetting.Runtime renderer;
    /** Mesh baked into the current static buffers, for hot-reload staleness checks (identity compare). */
    @Nullable
    private PhotonMesh builtMesh;
    /** Whether the current static geometry/layout was baked for Model mode (vs billboard family); a
     *  runtime renderMode override crossing this boundary forces a rebuild. */
    private boolean builtModelMode;
    /** The emitter's Tangent renderer setting, refreshed per frame by the pass. */
    private boolean wantsTangent;
    /** Whether the current static geometry carries a tangent; a change against {@link #wantsTangent}
     *  forces a rebuild (the tangent stream exists or it does not). */
    private boolean builtWithTangent;
    /** Shade / useBlockUV as the attribute stream was baked with them. Both are runtime-overridable and
     *  timeline-animatable, and both are folded into that stream, so both have to be able to invalidate
     *  it — before this was tracked, animating either did nothing until some other change forced a
     *  rebuild. */
    private boolean builtShade;
    private boolean builtUseBlockUV;

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

    /** Whether the baked static streams still match what the renderer settings ask for. */
    boolean staticGeometryStale() {
        if (renderer.getRenderMode() != ParticleRendererSetting.Mode.Model) {
            return false; // the billboard quad depends on nothing
        }
        return builtMesh != renderer.getModelSource().getMesh()
                || builtWithTangent != wantsTangent
                || builtShade != renderer.isShade()
                || builtUseBlockUV != renderer.isUseBlockUV();
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
        this.builtShade = renderer.isShade();
        this.builtUseBlockUV = renderer.isUseBlockUV();
        if (builtModelMode) {
            createModelGeometry(resource);
        } else {
            createBillboardGeometry(resource);
        }
    }

    /**
     * The mesh's streams, one buffer each.
     *
     * <p>MIRRORED FROM the PARTICLE_MODEL_INSTANCE block of particle.glsl (keep in lockstep):
     * location 0 = position and location 2 = normal out of the geometry buffer, location 1 =
     * {@code (u, v, shade)} out of the attribute buffer, location 3 = the tangent out of its own.
     * Shade rides in the attribute vec3's z rather than taking a location of its own because
     * locations 4..8 are the per-instance attributes and 9+ are the additional-data channels a
     * hand-written shader declares — inserting anything here would shift those out from under it.</p>
     *
     * <p>⚠️ The model pivot is deliberately NOT baked in. It is applied per instance (see
     * {@link TileParticleRenderer#uploadInstances}) so these buffers hold nothing but the mesh: that is
     * what lets the geometry buffer be replaced wholesale by a dynamic mesh, and it also fixes a pivot
     * animation doing nothing, since a pivot change never invalidated the bake.</p>
     */
    private void createModelGeometry(InstanceResource resource) {
        var source = renderer.getModelSource();
        var mesh = source.getMesh();
        var remapUV = source.hasAtlasUV() && !builtUseBlockUV && mesh.spriteBounds().length > 0;
        int vertexCount = mesh.vertexCount();

        // ---- geometry: position 3 + normal 3 ----------------------------------------------------
        var geometry = mesh.geometry();
        resource.modelVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
        glBufferData(GL_ARRAY_BUFFER, geometry, GL_DYNAMIC_DRAW);
        int geometryStride = PhotonMesh.FLOATS_PER_GEOMETRY * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, geometryStride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, geometryStride, 3 * Float.BYTES);
        glEnableVertexAttribArray(2);

        // ---- attributes: u, v, shade ------------------------------------------------------------
        var attributes = mesh.attributes();
        var bounds = mesh.spriteBounds();
        var attributeBuffer = BufferUtils.createFloatBuffer(vertexCount * PhotonMesh.FLOATS_PER_ATTRIBUTE);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int off = PhotonMesh.attributeOffset(vertex);
            float u = attributes[off];
            float v = attributes[off + 1];
            if (remapUV) {
                int s = PhotonMesh.spriteOffset(vertex);
                float u0 = bounds[s], v0 = bounds[s + 1];
                float uw = bounds[s + 2] - u0, vh = bounds[s + 3] - v0;
                if (uw != 0f) u = (u - u0) / uw;
                if (vh != 0f) v = (v - v0) / vh;
            }
            attributeBuffer.put(u).put(v).put(builtShade ? attributes[off + 2] : 1f);
        }
        attributeBuffer.flip();
        resource.attributeVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, resource.attributeVbo);
        glBufferData(GL_ARRAY_BUFFER, attributeBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, PhotonMesh.FLOATS_PER_ATTRIBUTE * Float.BYTES, 0);
        glEnableVertexAttribArray(1);

        // ---- tangents, only when the emitter asked for them -------------------------------------
        // The array is generated on first access, so a tangent-free emitter never pays for it. When it
        // is off, location 3 must be left DISABLED rather than pointing at a buffer that no longer
        // exists — the enable bit is VAO state and survives a rebuild.
        glDisableVertexAttribArray(3);
        if (wantsTangent) {
            resource.tangentVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, resource.tangentVbo);
            glBufferData(GL_ARRAY_BUFFER, mesh.tangents(), GL_STATIC_DRAW);
            glVertexAttribPointer(3, 4, GL_FLOAT, false, PhotonMesh.FLOATS_PER_TANGENT * Float.BYTES, 0);
            glEnableVertexAttribArray(3);
        }

        // ---- indices ----------------------------------------------------------------------------
        var indices = mesh.indices();
        resource.modelEbo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource.modelEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        modelEboSize = indices.length;
        builtMesh = mesh;
    }

    private void createBillboardGeometry(InstanceResource resource) {
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
        builtMesh = null;
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
