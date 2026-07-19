package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;

import javax.annotation.Nullable;
import java.lang.ref.Cleaner;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

/**
 * Shared GL-resource backend for instanced particle rendering: owns the VAO, the static base
 * geometry (VBO/EBO) and the per-instance VBO (divisor-1 attributes), and issues the instanced
 * draw call. Subclasses define the base mesh ({@link #createStaticGeometry}) and the per-instance
 * attribute layout ({@link #instanceFloats}/{@link #defineInstanceAttributes}); the per-particle
 * fill math lives in the owning renderer ({@link #beginUpload}/{@link #endUpload}).
 */
abstract class InstancedRenderBackend {
    protected static class InstanceResource implements AutoCloseable {
        protected int vao = -1;
        protected int modelVbo = -1;
        protected int modelEbo = -1;
        protected int instanceVbo = -1;
        // optional per-point buffer texture (vertex pulling), see pointTexelsPerPoint()
        protected int pointTbo = -1;
        protected int pointTex = -1;
        // optional per-instance additional-data buffer texture (shadergraph), see dataTexelsPerInstance()
        protected int dataTbo = -1;
        protected int dataTex = -1;
        // optional per-instance custom-data buffer texture (shadergraph CustomDataNode), see customTexelsPerInstance()
        protected int customTbo = -1;
        protected int customTex = -1;

        @Override
        public void close() {
            if (vao != -1) {
                glDeleteVertexArrays(vao);
                vao = -1;
            }

            if (modelVbo != -1) {
                glDeleteBuffers(modelVbo);
                modelVbo = -1;
            }

            if (instanceVbo != -1) {
                glDeleteBuffers(instanceVbo);
                instanceVbo = -1;
            }

            if (modelEbo != -1) {
                glDeleteBuffers(modelEbo);
                modelEbo = -1;
            }

            if (pointTex != -1) {
                glDeleteTextures(pointTex);
                pointTex = -1;
            }

            if (pointTbo != -1) {
                glDeleteBuffers(pointTbo);
                pointTbo = -1;
            }

            if (dataTex != -1) {
                glDeleteTextures(dataTex);
                dataTex = -1;
            }

            if (dataTbo != -1) {
                glDeleteBuffers(dataTbo);
                dataTbo = -1;
            }

            if (customTex != -1) {
                glDeleteTextures(customTex);
                customTex = -1;
            }

            if (customTbo != -1) {
                glDeleteBuffers(customTbo);
                customTbo = -1;
            }
        }
    }

    /** Vertex-shader sampler name of the per-point buffer texture (vertex pulling). */
    public static final String POINT_SAMPLER = "PhotonPoints";
    /** Vertex-shader sampler name of the per-instance additional-data buffer texture. */
    public static final String DATA_SAMPLER = "PhotonData";
    /** Vertex-shader sampler name of the per-instance custom-data buffer texture. */
    public static final String CUSTOM_SAMPLER = "PhotonCustomData";
    /** Texture units the buffer textures bind to (combined limit is >= 48 on GL 3.3; MC uses 0-11). */
    private static final int POINT_SAMPLER_UNIT = 15;
    private static final int DATA_SAMPLER_UNIT = 14;
    private static final int CUSTOM_SAMPLER_UNIT = 13;

    @Getter
    private boolean initialized = false;

    @Nullable
    private InstanceResource resource;
    @Nullable
    private Cleaner.Cleanable cleanable;

    protected int modelEboSize = 0;
    protected int instanceDataSize = 0; // number of floats per instance
    private int maxInstancesSize = 0;
    private int maxPointsSize = 0;
    private int maxDataSize = 0;
    private int maxCustomSize = 0;
    private int instanceCount = 0;
    @Nullable
    private static FloatBuffer instanceDataBuffer = null;
    @Nullable
    private static FloatBuffer pointDataBuffer = null;
    @Nullable
    private static FloatBuffer recordDataBuffer = null;
    @Nullable
    private static FloatBuffer customDataBuffer = null;

    /** Uploads the static base mesh into {@code resource.modelVbo}/{@code modelEbo} (the VAO is bound), sets the static attribute pointers and {@link #modelEboSize}. */
    protected abstract void createStaticGeometry(InstanceResource resource);

    /** Total floats per instance, including any custom-data tail. Sampled whenever the VBO is (re)created or grown. */
    protected abstract int instanceFloats();

    /** Defines the divisor-1 instance attribute pointers (the instance VBO is bound; called only for a fresh VBO). */
    protected abstract void defineInstanceAttributes(int stride);

    /** Instance capacity to allocate on first init (the buffer grows on demand afterwards). */
    protected abstract int initialInstanceCapacity();

    /**
     * RGBA32F texels per point in the {@value #POINT_SAMPLER} buffer texture, or 0 for backends
     * without vertex pulling. Instances then reference points by index and the vertex shader
     * texelFetches them (note: GL guarantees only 65536 texels — ~16k+ points per batch — the
     * practical driver limits are far higher).
     */
    protected int pointTexelsPerPoint() {
        return 0;
    }

    /**
     * RGBA32F texels per instance in the {@value #DATA_SAMPLER} buffer texture (the packed
     * additional-data record), or 0 for backends without it. Fixed per config; the shadergraph
     * {@code photon_data_*()} accessors read it by {@code gl_InstanceID}.
     */
    protected int dataTexelsPerInstance() {
        return 0;
    }

    /**
     * RGBA32F texels per instance in the {@value #CUSTOM_SAMPLER} buffer texture (the user custom-data
     * record), or 0 for backends without it / when no shadergraph on the pass reads custom data. The
     * shadergraph {@code photon_custom_data(i)} accessor reads it by {@code gl_InstanceID} with a
     * constant stride (see {@code AdditionalGPUDataSetting.MAX_CUSTOM_DATA}).
     */
    protected int customTexelsPerInstance() {
        return 0;
    }

    public void init() {
        if (initialized) return;
        ensureCreated(initialInstanceCapacity());
        initialized = true;
    }

    /**
     * Resize only the instance buffer capacity; do not recreate VAO/static buffers.
     */
    public void resize(int size) {
        RenderSystem.assertOnRenderThread();
        ensureCreated(size);
    }

    private void ensureCreated(int instanceCapacity) {
        RenderSystem.assertOnRenderThread();

        if (resource == null) {
            resource = new InstanceResource();
            cleanable = AutoCloseCleaner.registerRenderThread(this, resource);
        }

        if (resource.vao == -1) {
            resource.vao = glGenVertexArrays();
        }

        glBindVertexArray(resource.vao);

        if (resource.modelVbo == -1 || resource.modelEbo == -1) {
            createStaticGeometry(resource);
        }

        // create instance data + grow capacity if needed
        createOrResizeInstanceData(instanceCapacity);

        glBindVertexArray(0);
    }

    private void createOrResizeInstanceData(int requestedMaxSize) {
        if (resource == null) return;

        var newVBO = false;
        if (resource.instanceVbo == -1) {
            resource.instanceVbo = glGenBuffers();
            newVBO = true;
        }

        instanceDataSize = instanceFloats();

        glBindBuffer(GL_ARRAY_BUFFER, resource.instanceVbo);

        boolean needGrow = requestedMaxSize > maxInstancesSize;
        if (needGrow || newVBO) {
            // grow with 1.5x headroom: trails add points every tick during their growth phase,
            // 1:1 growth would reallocate the buffer once per tick
            maxInstancesSize = Math.max(requestedMaxSize, maxInstancesSize + (maxInstancesSize >> 1));
            glBufferData(GL_ARRAY_BUFFER, ((long) maxInstancesSize) * instanceDataSize * Float.BYTES, GL_STREAM_DRAW);
        }

        if (newVBO) {
            defineInstanceAttributes(instanceDataSize * Float.BYTES);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Defines one divisor-1 float attribute and returns the offset advanced past it. */
    protected static int floatInstanceAttrib(int attribIndex, int size, int stride, int offset) {
        glVertexAttribPointer(attribIndex, size, GL_FLOAT, false, stride, offset);
        glEnableVertexAttribArray(attribIndex);
        glVertexAttribDivisor(attribIndex, 1);
        return offset + size * Float.BYTES;
    }

    /** Defines one divisor-1 uint attribute (int-bits packed into the float stream) and returns the advanced offset. */
    protected static int intInstanceAttrib(int attribIndex, int stride, int offset) {
        return intInstanceAttrib(attribIndex, 1, stride, offset);
    }

    /** Defines one divisor-1 integer attribute of {@code size} components (int-bits in the float stream). */
    protected static int intInstanceAttrib(int attribIndex, int size, int stride, int offset) {
        glVertexAttribIPointer(attribIndex, size, GL_UNSIGNED_INT, stride, offset);
        glEnableVertexAttribArray(attribIndex);
        glVertexAttribDivisor(attribIndex, 1);
        return offset + size * Float.BYTES;
    }

    /**
     * Full cleanup: delete VAO/VBO/EBO etc.
     * Call this when render mode/layout changes, not for instance capacity growth.
     */
    public void dispose() {
        if (cleanable != null) {
            cleanable.clean();
            cleanable = null;
        }

        resource = null;

        modelEboSize = 0;
        maxInstancesSize = 0;
        maxPointsSize = 0;
        maxDataSize = 0;
        maxCustomSize = 0;
        instanceCount = 0;
        initialized = false;
    }

    private static FloatBuffer getInstanceDataBuffer(int requiredCapacity) {
        if (instanceDataBuffer == null || instanceDataBuffer.capacity() < requiredCapacity) {
            int newCapacity = instanceDataBuffer == null ?
                    Math.max(requiredCapacity, 10000) :
                    Math.max(requiredCapacity, instanceDataBuffer.capacity() * 2);

            instanceDataBuffer = BufferUtils.createFloatBuffer(newCapacity);
        }
        return instanceDataBuffer;
    }

    private static FloatBuffer getPointDataBuffer(int requiredCapacity) {
        if (pointDataBuffer == null || pointDataBuffer.capacity() < requiredCapacity) {
            int newCapacity = pointDataBuffer == null ?
                    Math.max(requiredCapacity, 10000) :
                    Math.max(requiredCapacity, pointDataBuffer.capacity() * 2);

            pointDataBuffer = BufferUtils.createFloatBuffer(newCapacity);
        }
        return pointDataBuffer;
    }

    private static FloatBuffer getRecordDataBuffer(int requiredCapacity) {
        if (recordDataBuffer == null || recordDataBuffer.capacity() < requiredCapacity) {
            int newCapacity = recordDataBuffer == null ?
                    Math.max(requiredCapacity, 10000) :
                    Math.max(requiredCapacity, recordDataBuffer.capacity() * 2);

            recordDataBuffer = BufferUtils.createFloatBuffer(newCapacity);
        }
        return recordDataBuffer;
    }

    private static FloatBuffer getCustomDataBuffer(int requiredCapacity) {
        if (customDataBuffer == null || customDataBuffer.capacity() < requiredCapacity) {
            int newCapacity = customDataBuffer == null ?
                    Math.max(requiredCapacity, 10000) :
                    Math.max(requiredCapacity, customDataBuffer.capacity() * 2);

            customDataBuffer = BufferUtils.createFloatBuffer(newCapacity);
        }
        return customDataBuffer;
    }

    /**
     * Prepare the custom-data buffer texture for a fresh upload: (lazy-)create/grow the TBO and
     * return the cleared shared custom staging buffer ({@link #customTexelsPerInstance()} x 4 floats
     * per instance). Call after {@link #beginUpload}. Null when unsupported or GL unavailable.
     */
    @Nullable
    FloatBuffer beginCustomUpload(int instanceCapacity) {
        var texels = customTexelsPerInstance();
        if (resource == null || texels <= 0) return null;

        var newTbo = resource.customTbo == -1;
        if (newTbo) {
            resource.customTbo = glGenBuffers();
        }
        if (newTbo || instanceCapacity > maxCustomSize) {
            maxCustomSize = Math.max(instanceCapacity, maxCustomSize + (maxCustomSize >> 1));
            glBindBuffer(GL_TEXTURE_BUFFER, resource.customTbo);
            glBufferData(GL_TEXTURE_BUFFER, ((long) maxCustomSize) * texels * 4 * Float.BYTES, GL_STREAM_DRAW);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
        }
        if (resource.customTex == -1) {
            resource.customTex = glGenTextures();
            glBindTexture(GL_TEXTURE_BUFFER, resource.customTex);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, resource.customTbo);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
        }

        var buffer = getCustomDataBuffer(instanceCapacity * texels * 4);
        buffer.clear();
        return buffer;
    }

    /** Flip and upload the filled custom-data staging buffer into the TBO. */
    void endCustomUpload(FloatBuffer buffer) {
        if (resource == null || resource.customTbo == -1) return;
        buffer.flip();
        glBindBuffer(GL_TEXTURE_BUFFER, resource.customTbo);
        glBufferSubData(GL_TEXTURE_BUFFER, 0, buffer);
        glBindBuffer(GL_TEXTURE_BUFFER, 0);
    }

    /**
     * Prepare the additional-data buffer texture for a fresh upload: (lazy-)create/grow the TBO and
     * return the cleared shared record staging buffer ({@link #dataTexelsPerInstance()} x 4 floats
     * per instance). Call after {@link #beginUpload}. Null when unsupported or GL unavailable.
     */
    @Nullable
    FloatBuffer beginDataUpload(int instanceCapacity) {
        var texels = dataTexelsPerInstance();
        if (resource == null || texels <= 0) return null;

        var newTbo = resource.dataTbo == -1;
        if (newTbo) {
            resource.dataTbo = glGenBuffers();
        }
        if (newTbo || instanceCapacity > maxDataSize) {
            maxDataSize = Math.max(instanceCapacity, maxDataSize + (maxDataSize >> 1));
            glBindBuffer(GL_TEXTURE_BUFFER, resource.dataTbo);
            glBufferData(GL_TEXTURE_BUFFER, ((long) maxDataSize) * texels * 4 * Float.BYTES, GL_STREAM_DRAW);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
        }
        if (resource.dataTex == -1) {
            resource.dataTex = glGenTextures();
            glBindTexture(GL_TEXTURE_BUFFER, resource.dataTex);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, resource.dataTbo);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
        }

        var buffer = getRecordDataBuffer(instanceCapacity * texels * 4);
        buffer.clear();
        return buffer;
    }

    /** Flip and upload the filled additional-data staging buffer into the TBO. */
    void endDataUpload(FloatBuffer buffer) {
        if (resource == null || resource.dataTbo == -1) return;
        buffer.flip();
        glBindBuffer(GL_TEXTURE_BUFFER, resource.dataTbo);
        glBufferSubData(GL_TEXTURE_BUFFER, 0, buffer);
        glBindBuffer(GL_TEXTURE_BUFFER, 0);
    }

    /**
     * Prepare the point buffer texture for a fresh upload: (lazy-)create/grow the TBO and return
     * the cleared shared point staging buffer ({@link #pointTexelsPerPoint()} x 4 floats per
     * point). Call after {@link #beginUpload} (the GL_TEXTURE_BUFFER binding point is independent
     * of the VAO/ARRAY_BUFFER state beginUpload sets up). Null when unsupported or GL unavailable.
     */
    @Nullable
    FloatBuffer beginPointUpload(int pointCapacity) {
        var texels = pointTexelsPerPoint();
        if (resource == null || texels <= 0) return null;

        var newTbo = resource.pointTbo == -1;
        if (newTbo) {
            resource.pointTbo = glGenBuffers();
        }
        if (newTbo || pointCapacity > maxPointsSize) {
            // grow with 1.5x headroom (see createOrResizeInstanceData)
            maxPointsSize = Math.max(pointCapacity, maxPointsSize + (maxPointsSize >> 1));
            glBindBuffer(GL_TEXTURE_BUFFER, resource.pointTbo);
            glBufferData(GL_TEXTURE_BUFFER, ((long) maxPointsSize) * texels * 4 * Float.BYTES, GL_STREAM_DRAW);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
        }
        if (resource.pointTex == -1) {
            resource.pointTex = glGenTextures();
            glBindTexture(GL_TEXTURE_BUFFER, resource.pointTex);
            // the texture tracks the buffer store, re-specification (growth) included
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, resource.pointTbo);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
        }

        var buffer = getPointDataBuffer(pointCapacity * texels * 4);
        buffer.clear();
        return buffer;
    }

    /** Flip and upload the filled point staging buffer into the TBO. */
    void endPointUpload(FloatBuffer buffer) {
        if (resource == null || resource.pointTbo == -1) return;
        buffer.flip();
        glBindBuffer(GL_TEXTURE_BUFFER, resource.pointTbo);
        glBufferSubData(GL_TEXTURE_BUFFER, 0, buffer);
        glBindBuffer(GL_TEXTURE_BUFFER, 0);
    }

    /**
     * Prepare the buffers for a fresh instance-data upload: (lazy-)create GL resources, grow the
     * instance VBO if needed, bind the VAO/VBO and return the cleared shared staging buffer the
     * caller fills ({@code instanceDataSize} floats per instance). Returns null when GL resources
     * are unavailable. Safe to share the staging buffer across backends: passes upload and draw
     * strictly sequentially on the render thread.
     */
    @Nullable
    FloatBuffer beginUpload(int instanceCapacity) {
        init();
        if (resource == null) return null;

        if (instanceCapacity > maxInstancesSize) {
            resize(instanceCapacity);
        }

        var required = instanceCapacity * instanceDataSize;
        var buffer = getInstanceDataBuffer(required);
        buffer.clear();

        glBindVertexArray(resource.vao);
        glBindBuffer(GL_ARRAY_BUFFER, resource.instanceVbo);
        return buffer;
    }

    /**
     * Flip and upload the filled staging buffer; remembers the instance count for the draw call.
     */
    void endUpload(FloatBuffer buffer, int instanceCount) {
        this.instanceCount = instanceCount;
        buffer.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
    }

    // TODO(M2): drawWithShader/bindBufferSampler — the ShaderInstance-driven instanced draw
    // (setDefaultUniforms + apply + raw buffer-texture binds + glDrawElementsInstanced) moves to
    // RenderPass.setPipeline + TEXEL_BUFFER uniforms + drawIndexed(instanceCount).
}
