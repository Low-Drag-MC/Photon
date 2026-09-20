package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.DynamicMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IDynamicMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.VertexAnimation;
import net.minecraft.client.renderer.ShaderInstance;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL30.*;

/**
 * GL-resource backend of {@link TileParticleRenderer}: billboard-quad or baked-model base
 * geometry plus the tile per-instance layout. Buffers and the draw call live in
 * {@link InstancedRenderBackend}.
 *
 * <p>In Model mode the mesh's three streams become three static buffers, so a deforming mesh
 * re-uploads only the geometry one and a provider can supply it outright.</p>
 */
class ParticleInstanceRenderer extends InstancedRenderBackend {

    private final ParticleConfig config;
    /** Effective renderer runtime (slot-or-config per field); drives render-mode-dependent geometry +
     *  layout. Custom GPU data still comes from the config. */
    private final ParticleRendererSetting.Runtime renderer;
    /** ⚠️ The topology, not the mesh: a dynamic mesh is a new instance per pose and comparing that
     *  would rebuild every frame. */
    @Nullable
    private PhotonMesh builtTopology;
    /** Geometry revision currently in the geometry buffer; a change means re-upload, not rebuild. */
    private long builtRevision;
    /** The provider's buffer this VAO points at, or 0 when the geometry buffer is ours. */
    private int builtGlBuffer;
    private long builtGlOffset;
    private boolean builtGlPackedNormals;
    /** Whether the current static geometry/layout was baked for Model mode (vs billboard family); a
     *  runtime renderMode override crossing this boundary forces a rebuild. */
    private boolean builtModelMode;
    /** The emitter's Tangent renderer setting, refreshed per frame by the pass. */
    private boolean wantsTangent;
    /** Whether the current static geometry carries a tangent; a change against {@link #wantsTangent}
     *  forces a rebuild (the tangent stream exists or it does not). */
    private boolean builtWithTangent;
    /** Shade / useBlockUV as the attribute stream was baked with them; both are animatable, and
     *  before this was tracked animating either did nothing. */
    private boolean builtShade;
    private boolean builtUseBlockUV;

    public ParticleInstanceRenderer(ParticleConfig config, ParticleRendererSetting.Runtime renderer) {
        this.config = config;
        this.renderer = renderer;
    }

    boolean wasBuiltForModel() {
        return builtModelMode;
    }

    /** The live-geometry provider behind the current model source, or null for an ordinary model. */
    @Nullable
    private IDynamicMesh dynamic() {
        return renderer.getModelSource().asDynamic();
    }

    /** The baked pose table the shader samples, or null when the model is posed on this side. */
    @Nullable
    VertexAnimation vertexAnimation() {
        return renderer.getRenderMode() == ParticleRendererSetting.Mode.Model
                ? renderer.getModelSource().vertexAnimation() : null;
    }

    /**
     * The table and the two uniforms the PHOTON_VAT variant reads. Raw GL like the sampler binds above,
     * which is what makes it work for core-shader JSONs and KilaGraph programs alike.
     */
    @Override
    protected void bindExtraBuffers(ShaderInstance shader) {
        var animation = vertexAnimation();
        if (animation == null) return;
        int texture = animation.texture();
        if (texture == -1) return;
        bindBufferSampler(shader, VAT_SAMPLER, vatSamplerUnit(), texture, VAT_MEMO);
        int size = glGetUniformLocation(shader.getId(), "PhotonVatSize");
        if (size >= 0) glUniform2i(size, animation.vertexCount(), animation.frames());
        int params = glGetUniformLocation(shader.getId(), "PhotonVatParams");
        if (params >= 0) {
            var phase = animation.phase();
            glUniform3f(params, phase[0], phase[1], phase[2]);
        }
    }

    /** The model was replaced, a bake input changed, or the provider moved us to another buffer.
     *  Not true for a mesh that merely deformed — that is {@link #geometryStale()}. */
    boolean staticGeometryStale() {
        if (renderer.getRenderMode() != ParticleRendererSetting.Mode.Model) {
            return false; // the billboard quad depends on nothing
        }
        if (builtTopology != renderer.getModelSource().getMesh().topology()
                || builtWithTangent != wantsTangent
                || builtShade != renderer.isShade()
                || builtUseBlockUV != renderer.isUseBlockUV()) {
            return true;
        }
        var dynamic = dynamic();
        int buffer = dynamic == null ? 0 : dynamic.glBuffer();
        if (buffer != builtGlBuffer) {
            return true;
        }
        return buffer != 0 && (dynamic.glByteOffset() != builtGlOffset
                || dynamic.glPackedNormals() != builtGlPackedNormals);
    }

    /** Only ever true for a mesh we upload: a provider's own buffer is deformed in place by its owner. */
    boolean geometryStale() {
        return builtModelMode && builtGlBuffer == 0
                && renderer.getModelSource().getMesh().geometryRevision() != builtRevision;
    }

    /** Re-upload the geometry stream in place; the index buffer, UVs, VAO and instance data stay. */
    void updateGeometry() {
        if (!isInitialized() || builtGlBuffer != 0) return;
        var mesh = renderer.getModelSource().getMesh();
        if (mesh.topology() != builtTopology) return; // a rebuild is due instead; don't write a mismatch
        RenderSystem.assertOnRenderThread();
        var resource = resource();
        if (resource == null) return;
        if (resource.modelVbo != -1) {
            glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, mesh.geometry());
        }
        if (wantsTangent && resource.tangentVbo != -1) {
            glBindBuffer(GL_ARRAY_BUFFER, resource.tangentVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, mesh.tangents());
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        builtRevision = mesh.geometryRevision();
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
     * MIRRORED FROM particle.glsl's PARTICLE_MODEL_INSTANCE block: loc 0 = position and loc 2 = normal
     * from the geometry buffer, loc 1 = {@code (u, v, shade)} from the attribute buffer, loc 3 = the
     * tangent. Shade rides in the attribute vec3's z because 4..8 are per-instance and 9+ are the
     * channels a hand-written shader declares.
     *
     * <p>⚠️ The model pivot is applied per instance, not baked, so these buffers hold nothing but the
     * mesh — which is what lets a provider replace the geometry one.</p>
     */
    private void createModelGeometry(InstanceResource resource) {
        var source = renderer.getModelSource();
        var mesh = source.getMesh();
        var remapUV = source.hasAtlasUV() && !builtUseBlockUV && mesh.spriteBounds().length > 0;
        int vertexCount = mesh.vertexCount();

        // ---- geometry: position 3 + normal 3 ----------------------------------------------------
        // ours, or the provider's own buffer bound straight in: a buffer object is untyped in GL, so
        // the SSBO a compute pass wrote is a perfectly good vertex buffer
        var dynamic = dynamic();
        builtGlBuffer = dynamic == null ? 0 : dynamic.glBuffer();
        if (builtGlBuffer != 0) {
            builtGlOffset = dynamic.glByteOffset();
            builtGlPackedNormals = dynamic.glPackedNormals();
            glBindBuffer(GL_ARRAY_BUFFER, builtGlBuffer);
            // packed = 16B a vertex, the layout a compute skinning pass already writes
            int stride = builtGlPackedNormals ? 3 * Float.BYTES + 4 : PhotonMesh.FLOATS_PER_GEOMETRY * Float.BYTES;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, builtGlOffset);
            glEnableVertexAttribArray(0);
            if (builtGlPackedNormals) {
                glVertexAttribPointer(2, 4, GL_BYTE, true, stride, builtGlOffset + 3 * Float.BYTES);
            } else {
                glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, builtGlOffset + 3 * Float.BYTES);
            }
            glEnableVertexAttribArray(2);
        } else {
            builtGlOffset = 0L;
            builtGlPackedNormals = false;
            resource.modelVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
            glBufferData(GL_ARRAY_BUFFER, mesh.geometry(), GL_DYNAMIC_DRAW);
            int geometryStride = PhotonMesh.FLOATS_PER_GEOMETRY * Float.BYTES;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, geometryStride, 0);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(2, 3, GL_FLOAT, false, geometryStride, 3 * Float.BYTES);
            glEnableVertexAttribArray(2);
        }

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

        // ⚠️ when off, location 3 must be left DISABLED rather than pointing at a deleted buffer:
        // the enable bit is VAO state and survives a rebuild
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
        builtTopology = mesh.topology();
        builtRevision = mesh.geometryRevision();
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
        builtTopology = null;
        builtRevision = 0L;
        builtGlBuffer = 0;
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
