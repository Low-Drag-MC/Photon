package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.photon.client.render.PhotonFloatTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * The transient render-target pool for post-processing passes. {@link #release} returns a target to its
 * (size, format) bucket <b>immediately</b>, so a later pass in the same frame reuses it — that same-frame
 * reuse IS the aliasing mechanism (GL draws are ordered on one context, no sync needed); a 6-level blur
 * pyramid peaks at ~3 live targets. Free targets persist across frames (LIFO — the hottest target first)
 * and are destroyed after {@link #EVICT_AFTER_FRAMES} frames unused ({@link #endFrame}).
 *
 * <p>Float formats are allocated raw ({@link PhotonFloatTextures}) because 26.1's {@link TextureFormat}
 * still has no float colour formats — the same GL-backend-only debt {@code PhotonDrawTarget} carries, and
 * for the same reason: an effect chain running in RGBA8 would clamp away exactly the HDR the chain
 * exists to work on. An allocation that fails returns null and the caller passes the chain through
 * rather than silently degrading to LDR.</p>
 *
 * <p>Targets carry no {@code COPY_SRC}/{@code COPY_DST}: everything that moves pixels in or out of them
 * is either a fullscreen draw or {@code PhotonFramebufferBlit}, neither of which needs a usage flag
 * (see {@code PhotonDrawTarget} for why the engine copy path is avoided). Render thread only.</p>
 */
public final class PostFXTargetPool {

    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

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
    private static final java.util.Set<Long> FAILED = new java.util.HashSet<>();
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
        return switch (format) {
            case RGBA16F -> PhotonFloatTextures.createRgba16f(label, USAGE, width, height);
            case RG16F -> PhotonFloatTextures.createHalfFloat(label, USAGE, width, height,
                    GL30.GL_RG16F, GL30.GL_RG, TextureFormat.RGBA8);
            case R16F -> PhotonFloatTextures.createHalfFloat(label, USAGE, width, height,
                    GL30.GL_R16F, GL11.GL_RED, TextureFormat.RED8);
            case RGBA8 -> RenderSystem.getDevice().createTexture(label, USAGE, TextureFormat.RGBA8,
                    width, height, 1, 1);
            case R8 -> RenderSystem.getDevice().createTexture(label, USAGE, TextureFormat.RED8,
                    width, height, 1, 1);
        };
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
        var budgetBytes = com.lowdragmc.photon.PhotonConfig.INSTANCE.postFxPoolBudgetMB.get() * 1024L * 1024L;
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
        long bytesPerPixel = switch (target.format()) {
            case RGBA16F -> 8;
            case RGBA8, RG16F -> 4;
            case R16F -> 2;
            case R8 -> 1;
        };
        return (long) target.width() * target.height() * bytesPerPixel;
    }

    private static long key(int width, int height, TargetFormat format) {
        return ((long) width << 36) | ((long) height << 8) | format.ordinal();
    }
}
