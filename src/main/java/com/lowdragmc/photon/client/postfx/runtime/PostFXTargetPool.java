package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL46;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * The transient render-target pool for post-processing passes (RGBA16F, linear filter, no depth).
 * {@link #release} returns a target to its size bucket <b>immediately</b>, so a later pass in the same
 * frame reuses it — that same-frame reuse IS the aliasing mechanism (GL draws are ordered on one
 * context, no sync needed); a 6-level bloom pyramid peaks at ~3 live targets. Free targets persist
 * across frames (LIFO — the hottest target first) and are destroyed after {@link #EVICT_AFTER_FRAMES}
 * frames unused ({@link #endFrame}) or on window resize ({@link #invalidateAll}). Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class PostFXTargetPool {

    private static final class Pooled {
        final HDRTarget target;
        long lastUsedFrame;

        Pooled(HDRTarget target, long lastUsedFrame) {
            this.target = target;
            this.lastUsedFrame = lastUsedFrame;
        }
    }

    /** (width, height, format) -> free targets of that shape, most recently used last. */
    private static final Map<Long, ArrayDeque<Pooled>> FREE = new HashMap<>();
    private static long FRAME_ID;
    /** ~2 s at 60 fps: absorbs editor-vs-game size flips without hoarding stale sizes. */
    private static final int EVICT_AFTER_FRAMES = 120;

    private PostFXTargetPool() {}

    /** The current frame index — the effect stack's once-per-frame consumption guard keys on it. */
    public static long currentFrame() {
        return FRAME_ID;
    }

    public static HDRTarget acquire(int width, int height) {
        return acquire(width, height, TargetFormat.RGBA16F);
    }

    /** Pop a free target of exactly {@code width}×{@code height}×{@code format} or allocate one.
     *  Caller owns it until {@link #release}. */
    public static HDRTarget acquire(int width, int height, TargetFormat format) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        var bucket = FREE.get(key(width, height, format));
        if (bucket != null) {
            var pooled = bucket.pollLast();
            if (pooled != null) return pooled.target;
        }
        var target = format == TargetFormat.RGBA16F
                ? new HDRTarget(width, height, GL11.GL_LINEAR, false)
                : new FormatTarget(width, height, GL11.GL_LINEAR, format);
        target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        labelTarget(target, width, height, format);
        return target;
    }

    /** Return {@code target} to the pool — legal (and intended) mid-frame, enabling aliasing. */
    public static void release(HDRTarget target) {
        var format = target instanceof FormatTarget formatTarget ? formatTarget.getFormat() : TargetFormat.RGBA16F;
        FREE.computeIfAbsent(key(target.width, target.height, format), k -> new ArrayDeque<>())
                .addLast(new Pooled(target, FRAME_ID));
    }

    /** Advance the frame clock and destroy free targets untouched for {@link #EVICT_AFTER_FRAMES}. */
    public static void endFrame() {
        FRAME_ID++;
        var buckets = FREE.values().iterator();
        while (buckets.hasNext()) {
            var bucket = buckets.next();
            bucket.removeIf(pooled -> {
                if (FRAME_ID - pooled.lastUsedFrame > EVICT_AFTER_FRAMES) {
                    pooled.target.destroyBuffers();
                    return true;
                }
                return false;
            });
            if (bucket.isEmpty()) buckets.remove();
        }
    }

    /** Destroy every free target (window resize — screen-relative sizes all change at once). */
    public static void invalidateAll() {
        FREE.values().forEach(bucket -> bucket.forEach(pooled -> pooled.target.destroyBuffers()));
        FREE.clear();
    }

    private static long key(int width, int height, TargetFormat format) {
        return ((long) width << 36) | ((long) height << 8) | format.ordinal();
    }

    /** RenderDoc-friendly names on the pooled FBO + color texture (dev only, same guard as bloom). */
    private static void labelTarget(HDRTarget target, int width, int height, TargetFormat format) {
        if (!Platform.isDevEnv() || !GL.getCapabilities().GL_KHR_debug) return;
        var label = "photonfx_pool %dx%d %s".formatted(width, height, format.name());
        GL46.glObjectLabel(GL30.GL_FRAMEBUFFER, target.frameBufferId, label);
        GL46.glObjectLabel(GL11.GL_TEXTURE, target.getColorTextureId(), label);
    }
}
