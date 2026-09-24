package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The transient render-target pool for post-processing passes. {@link #release} returns a target to its
 * (size, format) bucket <b>immediately</b>, so a later pass in the same frame reuses it — that same-frame
 * reuse IS the aliasing mechanism (GL draws are ordered on one context, no sync needed); a 6-level blur
 * pyramid peaks at ~3 live targets. Free targets persist across frames (LIFO — the hottest target first)
 * and are destroyed after {@link #EVICT_AFTER_FRAMES} frames unused ({@link #endFrame}).
 *
 * <p>A failed allocation returns null and the caller passes the chain through. Render thread only.</p>
 */
public final class PostFXTargetPool {

    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

    /** One pooled off-screen target. Identity-based (never a record): the pool tracks ownership. */
    public static final class Target implements AutoCloseable {

        private final GpuTexture texture;
        private final GpuTextureView view;
        private final TargetFormat format;
        private long lastUsedFrame;

        private Target(GpuTexture texture, TargetFormat format) {
            this.texture = texture;
            this.view = RenderSystem.getDevice().createTextureView(texture);
            this.format = format;
        }

        public GpuTextureView view() {
            return view;
        }

        public int width() {
            return texture.getWidth(0);
        }

        public int height() {
            return texture.getHeight(0);
        }

        public TargetFormat format() {
            return format;
        }

        @Override
        public void close() {
            view.close();
            texture.close();
        }
    }

    /** (width, height, format) -> free targets of that shape, most recently used last. */
    private static final Map<Long, ArrayDeque<Target>> FREE = new HashMap<>();
    /** Shapes whose allocation failed — keeps the warning to once per shape instead of once per frame. */
    private static final Set<Long> FAILED = new HashSet<>();
    private static long FRAME_ID;
    /** Bytes held by FREE targets, maintained incrementally — the budget check runs every frame and
     *  has no business re-walking every bucket to answer "are we over?". */
    private static long freeBytes;
    /** ~2 s at 60 fps: absorbs editor-vs-game size flips without hoarding stale sizes. */
    private static final int EVICT_AFTER_FRAMES = 120;

    private PostFXTargetPool() {}

    /** The current frame index — the effect stack's once-per-frame consumption guard keys on it. */
    public static long currentFrame() {
        return FRAME_ID;
    }

    @Nullable
    public static Target acquire(int width, int height) {
        return acquire(width, height, TargetFormat.RGBA16F);
    }

    /** Pop a free target of exactly {@code width}×{@code height}×{@code format} or allocate one; null when
     *  the format is unavailable on this backend. Caller owns it until {@link #release}. */
    @Nullable
    public static Target acquire(int width, int height, TargetFormat format) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        var key = key(width, height, format);
        var bucket = FREE.get(key);
        if (bucket != null) {
            var pooled = bucket.pollLast();
            if (pooled != null) {
                freeBytes -= byteSize(pooled);
                if (bucket.isEmpty()) FREE.remove(key);
                return pooled;
            }
        }
        if (FAILED.contains(key)) {
            return null;
        }
        var texture = allocate(width, height, format);
        if (texture == null) {
            FAILED.add(key);
            Photon.LOGGER.error("Photon could not allocate a {}x{} {} post-effect target — effects are "
                    + "off at this size/format", width, height, format);
            return null;
        }
        return new Target(texture, format);
    }

    @Nullable
    private static GpuTexture allocate(int width, int height, TargetFormat format) {
        var label = "photonfx_pool %dx%d %s".formatted(width, height, format.name());
        try {
            return RenderSystem.getDevice().createTexture(label, USAGE, format.gpuFormat(), width, height, 1, 1);
        } catch (RuntimeException e) {
            Photon.LOGGER.error("Photon post-effect target allocation failed", e);
            return null;
        }
    }

    /** Return {@code target} to the pool — legal (and intended) mid-frame, enabling aliasing. */
    public static void release(@Nullable Target target) {
        if (target == null) return;
        target.lastUsedFrame = FRAME_ID;
        FREE.computeIfAbsent(key(target.width(), target.height(), target.format()), k -> new ArrayDeque<>())
                .addLast(target);
        freeBytes += byteSize(target);
    }

    /** Destroy a free target and drop it out of the accounting. */
    private static void destroy(Target target) {
        freeBytes -= byteSize(target);
        target.close();
    }

    /** Advance the frame clock, destroy free targets untouched for {@link #EVICT_AFTER_FRAMES},
     *  then enforce the configured VRAM budget over the remaining free targets (oldest-first). */
    public static void endFrame() {
        FRAME_ID++;
        var buckets = FREE.entrySet().iterator();
        while (buckets.hasNext()) {
            var bucket = buckets.next().getValue();
            // LIFO by recency, so the stale ones are all at the head
            while (!bucket.isEmpty() && FRAME_ID - bucket.peekFirst().lastUsedFrame > EVICT_AFTER_FRAMES) {
                destroy(bucket.pollFirst());
            }
            if (bucket.isEmpty()) buckets.remove();
        }
        enforceBudget();
    }

    /**
     * Evict oldest free targets while the pool exceeds the configured budget (in-use targets are
     * frame-transient and never counted — the cap bounds what persists across frames).
     * <p>
     * Each bucket is already ordered oldest-first, so eviction pops heads rather than sorting the whole
     * pool and then searching every bucket for each victim.
     */
    private static void enforceBudget() {
        var budgetBytes = PhotonConfig.INSTANCE.postFxPoolBudgetMB.get() * 1024L * 1024L;
        while (freeBytes > budgetBytes) {
            ArrayDeque<Target> oldestBucket = null;
            for (var bucket : FREE.values()) {
                var head = bucket.peekFirst();
                if (head != null && (oldestBucket == null
                        || head.lastUsedFrame < oldestBucket.peekFirst().lastUsedFrame)) {
                    oldestBucket = bucket;
                }
            }
            if (oldestBucket == null) return; // nothing free left to give back
            destroy(oldestBucket.pollFirst());
        }
        FREE.values().removeIf(ArrayDeque::isEmpty);
    }

    private static long byteSize(Target target) {
        return (long) target.width() * target.height() * target.format().gpuFormat().blockSize();
    }

    private static long key(int width, int height, TargetFormat format) {
        return ((long) width << 36) | ((long) height << 8) | format.ordinal();
    }
}
