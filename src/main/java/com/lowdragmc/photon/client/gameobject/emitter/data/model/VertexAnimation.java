package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.AutoCloseCleaner;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL31.*;

/**
 * A baked pose table on the GPU: the buffer texture the model path samples when every particle is at its
 * own frame of the animation.
 *
 * <p>Uploaded once and then read-only, so a swarm costs one texture and no per-frame work at all.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VertexAnimation {

    private static final class Resource implements AutoCloseable {
        private int buffer = -1;
        private int texture = -1;

        @Override
        public void close() {
            if (texture != -1) {
                glDeleteTextures(texture);
                texture = -1;
            }
            if (buffer != -1) {
                glDeleteBuffers(buffer);
                buffer = -1;
            }
        }
    }

    private final float[] table;
    private final int vertexCount;
    private final int frames;
    /** (shared clip position, weight on the per-particle random, weight on the particle's t) —
     *  refreshed by the source each frame, MIRRORED FROM the PHOTON_VAT block of particle.glsl. */
    private final float[] phase = new float[3];
    @Nullable
    private Resource resource;
    @Nullable
    private Cleaner.Cleanable cleanable;

    public VertexAnimation(float[] table, int vertexCount, int frames) {
        this.table = table;
        this.vertexCount = vertexCount;
        this.frames = frames;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int frames() {
        return frames;
    }

    public float[] phase() {
        return phase;
    }

    public void setPhase(float base, float randomWeight, float tWeight) {
        phase[0] = base;
        phase[1] = randomWeight;
        phase[2] = tWeight;
    }

    /** The buffer texture, created on first use. {@code -1} when it could not be made. */
    public int texture() {
        if (resource == null) {
            resource = new Resource();
            cleanable = AutoCloseCleaner.registerRenderThread(this, resource);
            resource.buffer = glGenBuffers();
            var staging = BufferUtils.createFloatBuffer(table.length);
            staging.put(table).flip();
            glBindBuffer(GL_TEXTURE_BUFFER, resource.buffer);
            glBufferData(GL_TEXTURE_BUFFER, staging, GL_STATIC_DRAW);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);

            resource.texture = glGenTextures();
            glBindTexture(GL_TEXTURE_BUFFER, resource.texture);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, resource.buffer);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
        }
        return resource.texture;
    }

    public void dispose() {
        if (cleanable != null) {
            cleanable.clean();
            cleanable = null;
        }
        resource = null;
    }
}
