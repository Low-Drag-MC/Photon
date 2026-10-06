package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL31.*;

/**
 * Per-particle data and custom-data records for the CPU tile path, appended in the order the particles' vertices
 * are written; the {@code PHOTON_CPU_DATA} vertex shader finds its own as {@code gl_VertexID / PhotonVertsPerParticle}.
 */
@OnlyIn(Dist.CLIENT)
public final class CpuParticleRecords {
    public static final String VERTS_UNIFORM = "PhotonVertsPerParticle";
    /** Diagnostic: how many record uploads ever happened — a plain CPU pass must never add one. */
    private static int uploads;

    private static final class Resource implements AutoCloseable {
        int dataTbo = -1, dataTex = -1, customTbo = -1, customTex = -1;

        @Override
        public void close() {
            if (dataTex != -1) glDeleteTextures(dataTex);
            if (dataTbo != -1) glDeleteBuffers(dataTbo);
            if (customTex != -1) glDeleteTextures(customTex);
            if (customTbo != -1) glDeleteBuffers(customTbo);
            dataTex = dataTbo = customTex = customTbo = -1;
        }
    }

    @Nullable
    private Resource resource;
    @Nullable
    private FloatBuffer dataStaging;
    @Nullable
    private FloatBuffer customStaging;
    private int dataCapacity;
    private int customCapacity;
    @Nullable
    private AdditionalGPUDataSetting setting;
    private boolean writeData;
    private boolean writeCustom;
    private int vertsPerParticle = 4;
    @Nullable
    private ShaderInstance memoShader;
    private int memoData = -1, memoCustom = -1, memoVerts = -1;

    /** Start a frame's records; {@code setting} decides which of the two buffers are wanted. */
    public void begin(AdditionalGPUDataSetting setting, int particleCapacity) {
        this.setting = setting;
        this.writeData = setting.hasDataRecord();
        this.writeCustom = setting.hasCustomRecord();
        if (writeData) dataStaging = staging(dataStaging, particleCapacity * setting.dataTexels() * 4);
        if (writeCustom) customStaging = staging(customStaging, particleCapacity * setting.customDataTexels() * 4);
    }

    public void setVertsPerParticle(int vertsPerParticle) {
        this.vertsPerParticle = Math.max(1, vertsPerParticle);
    }

    /** Append one particle's records — call exactly once per particle whose vertices were written. */
    public void write(IParticle particle, float partialTicks) {
        if (setting == null) return;
        if (writeData && dataStaging != null) setting.uploadDataRecord(particle, dataStaging, partialTicks);
        if (writeCustom && customStaging != null) setting.uploadCustomRecord(particle, customStaging, partialTicks);
    }

    /** Upload what {@link #write} staged. */
    public void end() {
        RenderSystem.assertOnRenderThread();
        if (setting == null) return;
        var res = resource();
        if (writeData && dataStaging != null) {
            dataCapacity = upload(res, true, dataStaging, dataCapacity);
        }
        if (writeCustom && customStaging != null) {
            customCapacity = upload(res, false, customStaging, customCapacity);
        }
        uploads++;
        setting = null;
    }

    public static int uploads() {
        return uploads;
    }

    /** Bind both buffers and the vertex count to {@code shader} — after {@code apply()}, the program bound. */
    public void bind(ShaderInstance shader) {
        if (resource == null) return;
        if (shader != memoShader) {
            memoShader = shader;
            memoData = glGetUniformLocation(shader.getId(), InstancedRenderBackend.DATA_SAMPLER);
            memoCustom = glGetUniformLocation(shader.getId(), InstancedRenderBackend.CUSTOM_SAMPLER);
            memoVerts = glGetUniformLocation(shader.getId(), VERTS_UNIFORM);
        }
        if (memoVerts >= 0) glUniform1i(memoVerts, vertsPerParticle);
        int previousUnit = GlStateManager._getActiveTexture();
        if (memoData >= 0 && resource.dataTex != -1) {
            int unit = InstancedRenderBackend.dataUnit();
            glUniform1i(memoData, unit);
            GlStateManager._activeTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_BUFFER, resource.dataTex);
        }
        if (memoCustom >= 0 && resource.customTex != -1) {
            int unit = InstancedRenderBackend.customUnit();
            glUniform1i(memoCustom, unit);
            GlStateManager._activeTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_BUFFER, resource.customTex);
        }
        GlStateManager._activeTexture(previousUnit);
    }

    public void close() {
        if (resource != null) {
            resource.close();
            resource = null;
        }
        memoShader = null;
    }

    private Resource resource() {
        if (resource == null) {
            resource = new Resource();
            AutoCloseCleaner.registerRenderThread(this, resource);
        }
        return resource;
    }

    private static FloatBuffer staging(@Nullable FloatBuffer current, int floats) {
        if (current == null || current.capacity() < floats) {
            int capacity = current == null ? Math.max(floats, 4096) : Math.max(floats, current.capacity() * 2);
            current = BufferUtils.createFloatBuffer(capacity);
        }
        current.clear();
        return current;
    }

    /** Grow-or-reuse the buffer store and upload; returns the store's capacity in floats. */
    private static int upload(Resource res, boolean data, FloatBuffer staging, int capacity) {
        int floats = staging.position();
        staging.flip();
        int tbo = data ? res.dataTbo : res.customTbo;
        if (tbo == -1) {
            tbo = glGenBuffers();
            if (data) res.dataTbo = tbo; else res.customTbo = tbo;
        }
        glBindBuffer(GL_TEXTURE_BUFFER, tbo);
        if (floats > capacity || capacity == 0) {
            capacity = Math.max(Math.max(floats, 4), capacity + (capacity >> 1));
            glBufferData(GL_TEXTURE_BUFFER, (long) capacity * Float.BYTES, GL_STREAM_DRAW);
        }
        if (floats > 0) glBufferSubData(GL_TEXTURE_BUFFER, 0, staging);
        glBindBuffer(GL_TEXTURE_BUFFER, 0);
        int tex = data ? res.dataTex : res.customTex;
        if (tex == -1) {
            tex = glGenTextures();
            glBindTexture(GL_TEXTURE_BUFFER, tex);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, tbo);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            if (data) res.dataTex = tex; else res.customTex = tex;
        }
        return capacity;
    }
}
