package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
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
        }
    }

    /** Vertex-shader sampler name of the per-point buffer texture (vertex pulling). */
    public static final String POINT_SAMPLER = "PhotonPoints";
    /** Texture unit the point buffer binds to (combined limit is >= 48 on GL 3.3). */
    private static final int POINT_SAMPLER_UNIT = 15;

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
    private int instanceCount = 0;
    @Nullable
    private static FloatBuffer instanceDataBuffer = null;
    @Nullable
    private static FloatBuffer pointDataBuffer = null;

    /** Uploads the static base mesh into {@code resource.modelVbo}/{@code modelEbo} (the VAO is bound), sets the static attribute pointers and {@link #modelEboSize}. */
    protected abstract void createStaticGeometry(InstanceResource resource);

    /** Total floats per instance, including any custom-data tail. Sampled whenever the VBO is (re)created or grown. */
    protected abstract int instanceFloats();

    /** Defines the divisor-1 instance attribute pointers (the instance VBO is bound; called only for a fresh VBO). */
    protected abstract void defineInstanceAttributes(int stride);

    /** Instance capacity to allocate on first init (the buffer grows on demand afterwards). */
    protected abstract int initialInstanceCapacity();

    /** Resets declared-but-inactive custom attribute slots before a draw (see the additional-GPU-data packing). */
    protected void zeroInactiveCustomSlots() {
    }

    /**
     * RGBA32F texels per point in the {@value #POINT_SAMPLER} buffer texture, or 0 for backends
     * without vertex pulling. Instances then reference points by index and the vertex shader
     * texelFetches them (note: GL guarantees only 65536 texels — ~16k+ points per batch — the
     * practical driver limits are far higher).
     */
    protected int pointTexelsPerPoint() {
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
        instanceCount = 0;
        lastSamplerShader = null;
        lastSamplerLocation = -1;

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

    public void drawWithShader(ShaderInstance shader) {
        // bind shader
        shader.setDefaultUniforms(
                VertexFormat.Mode.QUADS,
                RenderSystem.getModelViewMatrix(),
                RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow()
        );
        shader.apply();

        bindPointSampler(shader);
        zeroInactiveCustomSlots();

        // draw instance
        glDrawElementsInstanced(GL_TRIANGLES, modelEboSize, GL_UNSIGNED_INT, 0, instanceCount);
    }

    // per-shader memo of the sampler's uniform location (the per-draw glGetUniformLocation string
    // lookup is measurable at small batch sizes); keyed by instance identity — a recompiled shader
    // is a new object
    @Nullable
    private ShaderInstance lastSamplerShader;
    private int lastSamplerLocation = -1;

    /**
     * Binds the point buffer texture to {@value #POINT_SAMPLER} via raw GL (after apply(), the
     * program is bound). Raw lookup works uniformly for core-shader JSONs and KilaGraph-compiled
     * programs — no sampler metadata needed. The TEXTURE_BUFFER target is separate from the 2D
     * bindings GlStateManager tracks, and the active unit is saved/restored through
     * GlStateManager's client-side cache (no synchronous glGet).
     */
    private void bindPointSampler(ShaderInstance shader) {
        if (resource == null || resource.pointTex == -1) return;
        if (shader != lastSamplerShader) {
            lastSamplerShader = shader;
            lastSamplerLocation = glGetUniformLocation(shader.getId(), POINT_SAMPLER);
        }
        if (lastSamplerLocation < 0) return;
        glUniform1i(lastSamplerLocation, POINT_SAMPLER_UNIT);
        int previousUnit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL_TEXTURE0 + POINT_SAMPLER_UNIT);
        glBindTexture(GL_TEXTURE_BUFFER, resource.pointTex);
        GlStateManager._activeTexture(previousUnit);
    }
}
