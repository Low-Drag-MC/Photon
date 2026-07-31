package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The CustomMask target: Unreal's CustomDepth/Stencil without a GL stencil buffer. Emitters flagged
 * {@code renderer.customMask} redraw their geometry here as a flat group id in R, and post effects read
 * it back through the Custom Mask / Custom Depth input nodes to confine themselves to those objects.
 * <p>
 * It carries <b>its own depth</b>, seeded from the scene's: that makes world geometry occlude the mask
 * (a particle behind a wall must not be masked) while the flagged passes write their depth <i>here</i>
 * rather than into the frame — which is exactly what makes this a custom depth buffer an effect can read.
 * <p>
 * One instance per (size, depth format), cleared once per frame on first use so both of a view's stages
 * accumulate into the same mask. {@link #writtenThisFrame} is what tells the effect chain whether a mask
 * exists at all: a frame that drew none must not let effects read the previous one's. Render thread only.
 */
public final class PhotonMaskTarget implements AutoCloseable {

    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private static final Map<Long, PhotonMaskTarget> INSTANCES = new HashMap<>();
    private static long frameCounter;
    /** ~2 s at 60 fps, matching {@code PostFXTargetPool.EVICT_AFTER_FRAMES}: long enough to survive a
     *  panel being hidden and reopened, short enough not to hoard sizes after a resize. */
    private static final int EVICT_AFTER_FRAMES = 120;

    /**
     * The mask target for this size, cleared and depth-seeded if this is the frame's first use.
     * {@code sceneDepth} supplies both the format (a depth blit needs matching formats) and the initial
     * contents.
     */
    public static PhotonMaskTarget acquire(int width, int height, GpuTextureView sceneDepth) {
        var format = sceneDepth.texture().getFormat();
        var key = ((long) width << 32) | (height & 0xFFFFFFFFL);
        var target = INSTANCES.get(key);
        if (target != null && target.depthFormat != format) {
            target.close();
            INSTANCES.remove(key);
            target = null;
        }
        if (target == null) {
            target = create(width, height, format);
            INSTANCES.put(key, target);
        }
        target.lastUsedFrame = frameCounter;
        if (target.preparedFrame != frameCounter) {
            target.preparedFrame = frameCounter;
            target.prepare(sceneDepth);
        }
        return target;
    }

    private static PhotonMaskTarget create(int width, int height, TextureFormat depthFormat) {
        var device = RenderSystem.getDevice();
        var color = device.createTexture(() -> "Photon custom mask", USAGE, TextureFormat.RED8,
                Math.max(1, width), Math.max(1, height), 1, 1);
        var depth = device.createTexture(() -> "Photon custom depth", USAGE, depthFormat,
                Math.max(1, width), Math.max(1, height), 1, 1);
        return new PhotonMaskTarget(color, depth, depthFormat);
    }

    /**
     * The mask an EARLIER stage of this frame already wrote, without clearing or re-seeding it — null
     * when no stage did. Deliberately not two calls: a bare lookup hands back the previous frame's ids,
     * which is exactly the mistake this guards against.
     */
    @Nullable
    public static PhotonMaskTarget writtenThisFrame(int width, int height) {
        var target = INSTANCES.get(((long) width << 32) | (height & 0xFFFFFFFFL));
        return target != null && target.wroteFrame == frameCounter ? target : null;
    }

    /** Frame boundary: advance the clock and drop targets nothing masked into for a while. */
    public static void endFrame() {
        frameCounter++;
        INSTANCES.values().removeIf(target -> {
            if (frameCounter - target.lastUsedFrame > EVICT_AFTER_FRAMES) {
                target.close();
                return true;
            }
            return false;
        });
    }

    private final GpuTexture color;
    private final GpuTextureView colorView;
    private final GpuTexture depth;
    private final GpuTextureView depthView;
    private final TextureFormat depthFormat;
    private long lastUsedFrame;
    private long preparedFrame = -1;
    private long wroteFrame = -1;
    private boolean clearPending;

    private PhotonMaskTarget(GpuTexture color, GpuTexture depth, TextureFormat depthFormat) {
        var device = RenderSystem.getDevice();
        this.color = color;
        this.colorView = device.createTextureView(color);
        this.depth = depth;
        this.depthView = device.createTextureView(depth);
        this.depthFormat = depthFormat;
    }

    /** Seed the depth from the scene's, so occlusion clips what the sub-pass writes. The COLOUR clear is
     *  deferred to {@link #takeClear()} rather than done here — a pass clears its attachment on creation,
     *  so the frame's first sub-pass gets it for free instead of costing a pass of its own. */
    private void prepare(GpuTextureView sceneDepth) {
        wroteFrame = -1;
        clearPending = true;
        PhotonFramebufferBlit.depth(sceneDepth.texture(), depth, color.getWidth(0), color.getHeight(0));
    }

    /** Whether the caller's pass must clear the ids — true exactly once per frame, for whichever
     *  sub-pass opens first. */
    public boolean takeClear() {
        var pending = clearPending;
        clearPending = false;
        return pending;
    }

    /** Called by the sub-pass after it draws — what makes the mask readable this frame. */
    public void markWritten() {
        wroteFrame = frameCounter;
    }

    public GpuTextureView colorView() {
        return colorView;
    }

    /** The flagged passes' own depth — what the Custom Depth input node reads. */
    public GpuTextureView depthView() {
        return depthView;
    }

    @Override
    public void close() {
        colorView.close();
        color.close();
        depthView.close();
        depth.close();
    }
}
