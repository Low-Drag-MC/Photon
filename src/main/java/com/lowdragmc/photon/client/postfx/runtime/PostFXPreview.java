package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonFramebufferBlit;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The render-graph editor's off-screen preview: a clean copy of the scene as the effect chain receives
 * it, plus one effect run over that copy, drawn into the editor panel.
 *
 * <p><b>Pull-based.</b> A visible preview panel calls {@link #requestPreview} every frame it draws; the
 * chain then fills the capture on its next run, and {@link #processPending()} renders the effect. Nothing
 * is copied or rendered while no preview is open.</p>
 *
 * <p><b>Phase split (mandatory, same as {@code MaterialPreviewRenderer}).</b> GUI drawing happens INSIDE
 * an open render pass, where creating a pass or uploading a texture throws. So the panel only records a
 * request and blits the last result; every GPU step runs from {@link #processPending()} at
 * {@code RenderFrameEvent.Pre}.</p>
 *
 * <p>The preview runs its effect against a clean scene with no CustomMask, so a mask-reading effect
 * shows the unmodified capture — masks only exist while the frame's flagged emitters render.</p>
 */
public final class PostFXPreview {

    private static long requestFrame = Long.MIN_VALUE;
    /** The clean scene as the chain received it (color + depth), refreshed while a preview is open. */
    @Nullable
    private static PostFXTargetPool.Target colorCapture;
    @Nullable
    private static GpuTexture depthCapture;
    @Nullable
    private static GpuTextureView depthCaptureView;

    /** What the panel asked to preview this frame — consumed by {@link #processPending()}. */
    private record Request(CompiledEffect effect, Map<String, Object> params) {}

    @Nullable
    private static Request pending;
    /** The rendered preview, registered so the GUI can blit it by {@link Identifier}. */
    @Nullable
    private static Result result;

    private record Result(GpuTextureView view, Identifier id, int width, int height) {
        void close() {
            // release() removes the registration and closes the AbstractTexture, which owns both
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    private PostFXPreview() {}

    /** Called by preview UIs each frame they draw; keeps the per-frame capture alive. */
    public static void requestCapture() {
        requestFrame = PostFXTargetPool.currentFrame();
    }

    /** Request a preview of {@code effect} at weight 1 with {@code params} — and, implicitly, the
     *  capture it runs over. */
    public static void requestPreview(CompiledEffect effect, Map<String, Object> params) {
        requestCapture();
        pending = new Request(effect, params);
    }

    /** "Asked for within the last frame". Written as a comparison, NOT as
     *  {@code currentFrame - requestFrame <= 1}: that subtraction overflows against the never-asked
     *  sentinel and reports true forever, which had every world frame copying the screen. */
    private static boolean captureWanted() {
        return requestFrame >= PostFXTargetPool.currentFrame() - 1;
    }

    /** Copy the clean scene if a preview wants it; drop the captures once none does. */
    public static void captureIfRequested(GpuTextureView cleanScene, @Nullable GpuTextureView cleanDepth) {
        if (!captureWanted()) {
            return; // processPending runs every frame and owns the teardown
        }
        int width = cleanScene.texture().getWidth(0);
        int height = cleanScene.texture().getHeight(0);
        if (colorCapture != null && (colorCapture.width() != width || colorCapture.height() != height)) {
            releaseCaptures();
        }
        if (colorCapture == null) {
            colorCapture = PostFXTargetPool.acquire(width, height);
            if (colorCapture == null) return;
        }
        PhotonFramebufferBlit.color(cleanScene, colorCapture.view());

        // depth matters: two of the shipped passes (outline, dof_composite) read it, and without a copy
        // their preview would fall back to the untouched scene
        if (cleanDepth == null) return;
        if (depthCapture == null) {
            // the SOURCE's format, not a fixed one: a depth glBlitFramebuffer requires the two formats
            // to match exactly, and a mismatch fails silently (the preview would just show stale depth)
            depthCapture = RenderSystem.getDevice().createTexture(() -> "Photon postfx preview depth",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    cleanDepth.texture().getFormat(), width, height, 1, 1);
            depthCaptureView = RenderSystem.getDevice().createTextureView(depthCapture);
        }
        PhotonFramebufferBlit.depth(cleanDepth.texture(), depthCapture, width, height);
    }

    /** Run the requested effect over the capture. {@code RenderFrameEvent.Pre} only — no pass is open. */
    public static void processPending() {
        var request = pending;
        pending = null;
        if (request == null || colorCapture == null) {
            if (!captureWanted()) releaseAll();
            return;
        }
        var inputs = RenderGraphExecutor.FrameInputs.of(colorCapture.view(), depthCaptureView);
        var output = RenderGraphExecutor.execute(request.effect(), 1f, request.params(), inputs);
        // a broken or no-op effect previews the clean scene, which is more useful than a blank panel
        var shown = output != null ? output.view() : colorCapture.view();
        publish(shown, colorCapture.width(), colorCapture.height());
        PostFXTargetPool.release(output);
    }

    /** Copy {@code view} into the GUI-visible preview texture, (re)allocating it on a size change. */
    private static void publish(GpuTextureView view, int width, int height) {
        if (result != null && (result.width() != width || result.height() != height)) {
            result.close();
            result = null;
        }
        if (result == null) {
            var device = RenderSystem.getDevice();
            // NOT named `texture`/`textureView`: those are AbstractTexture's own field names, and the
            // initializer below would then assign the fields to themselves instead of these
            var color = device.createTexture(() -> "Photon postfx preview",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.RGBA8, width, height, 1, 1);
            var colorView = device.createTextureView(color);
            var id = Photon.id("postfx_preview");
            Minecraft.getInstance().getTextureManager().register(id, new AbstractTexture() {{
                this.texture = color;
                this.textureView = colorView;
                this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }});
            result = new Result(colorView, id, width, height);
        }
        PhotonFramebufferBlit.color(view, result.view());
    }

    /**
     * The last rendered preview, aspect-fit into the drawn rect, or null when there is none yet. The
     * capture is bottom-up like every render target, so the V axis is flipped.
     */
    @Nullable
    public static IGuiTexture previewTexture() {
        var current = result;
        if (current == null) return null;
        return GuiTexture.of((context, x, y, width, height) -> {
            if (width <= 1 || height <= 1) return;
            float aspect = (float) current.width() / current.height();
            float drawWidth = Math.min(width, height * aspect);
            float drawHeight = drawWidth / aspect;
            context.blit(RenderPipelines.GUI_TEXTURED, current.id(),
                    x + (width - drawWidth) / 2f, y + (height - drawHeight) / 2f, drawWidth, drawHeight,
                    0f, 1f, 1f, 0f, -1);
        });
    }

    private static void releaseCaptures() {
        PostFXTargetPool.release(colorCapture);
        colorCapture = null;
        if (depthCaptureView != null) {
            depthCaptureView.close();
            depthCaptureView = null;
        }
        if (depthCapture != null) {
            depthCapture.close();
            depthCapture = null;
        }
    }

    private static void releaseAll() {
        releaseCaptures();
        if (result != null) {
            result.close();
            result = null;
        }
    }

}
