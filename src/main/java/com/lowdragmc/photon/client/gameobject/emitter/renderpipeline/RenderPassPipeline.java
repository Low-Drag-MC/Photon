package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.google.common.collect.Maps;
import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.lowdraglib2.math.PositionedRect;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.lowdragmc.photon.client.compat.iris.IrisFrameTarget;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.photon.client.postfx.runtime.FormatTarget;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.postfx.runtime.SceneBlit;
import com.lowdragmc.photon.client.postprocessing.PhotonPostProcessing;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nonnull;
import java.util.*;

public class RenderPassPipeline extends BufferBuilder {
    @Getter
    private final ByteBufferBuilder sortingBuffer;

    // runtime
    @Getter
    private SceneView.DrawMode drawMode = SceneView.DrawMode.DRAW;
    /** True while the wireframe overlay sub-pass is drawing (WIREFRAME, or the second pass of BOTH). */
    @Getter
    private boolean wireframeSubPass = false;
    /** True while the custom-mask sub-pass is drawing (flagged passes redraw flat mask ids). */
    @Getter
    private boolean maskSubPass = false;
    /** CustomMask/CustomDepth target (SCENE_SAMPLER-style pipeline static): lazily created only
     *  while any pass writes a mask; R8 color (the id encoding is 8-bit by design — 1 byte/px)
     *  with its OWN depth, pre-filled from the scene. */
    @Nullable
    private static com.lowdragmc.photon.client.postfx.runtime.FormatTarget MASK_TARGET;
    /** This frame's mask textures for the post-effect chain (-1 = no mask was written). */
    @Getter
    private static int maskColorTexture = -1;
    @Getter
    private static int maskDepthTexture = -1;
    @Nullable
    @Getter
    private static RenderPassPipeline current = null;
    /** The shader pack's resolved particle-stage layout for this build, null on the plain path. */
    @Nullable
    @Getter
    private IrisFrameTarget irisTarget;
    /** The framebuffer + viewport the pack had bound when it handed us the particle pass. */
    private int entryFramebuffer;
    private PositionedRect entryViewport = PositionedRect.of(0, 0, 0, 0);
    /** Identity of the Iris depth texture currently attached to {@link #DRAW_TARGET}: texture id,
     *  Iris' buffer version, and the framebuffer it was attached to (a resize builds a new one and
     *  silently re-attaches the target's own depth). */
    private static int attachedDepthTexture = -1;
    private static int attachedDepthVersion = -1;
    private static int attachedDepthFramebuffer = -1;
    /** AFTER_PACK: the accumulated FX layer waiting to be composited onto the finished frame
     *  ({@code null} = nothing pending). Both queues accumulate into it before it is consumed. */
    @Nullable
    private static RenderTarget pendingAfterPackLayer = null;
    private static boolean pendingAfterPackBloom = false;
    private static boolean afterPackLayerStarted = false;
    private final Map<PhotonFXRenderPass, Queue<IParticle>> particles = Maps.newTreeMap(makeRenderPassComparator());
    @Getter
    private Camera camera;
    @Getter
    private float partialTicks;
    @Getter
    private static HDRTarget DRAW_TARGET;
    private static boolean IS_DRAW_TARGET_DIRTY = true;
    @Nullable
    private static HDRTarget SCENE_SAMPLER;
    private static boolean IS_SCENE_SAMPLER_DIRTY = true;

    public static Comparator<PhotonFXRenderPass> makeRenderPassComparator() {
        return (passOne, passTwo) -> {
            var comparedResult = passOne.layerOrder() - passTwo.layerOrder();
            if (comparedResult == 0) {
                if (passOne.equals(passTwo)) {
                    return 0;
                }
                return Integer.compare(passOne.hashCode(), passTwo.hashCode());
            }
            return comparedResult;
        };
    }

    public RenderPassPipeline(ByteBufferBuilder sortingBuffer) {
        super(sortingBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        this.sortingBuffer = sortingBuffer;
    }

    /**
     * Whether the draws in this build go into a transparent premultiplied accumulator rather than
     * straight onto the scene — true exactly on the shader-pack path, and never during the mask
     * sub-pass (that one writes flat ids into its own R8 target, where coverage is meaningless).
     *
     * @see com.lowdragmc.photon.client.compat.iris.IrisBlendPlan
     */
    public boolean isPremultipliedAccumulation() {
        return irisTarget != null && !maskSubPass;
    }

    @Override
    public @Nullable MeshData build() {
        if (particles.isEmpty()) return null;
        // The shadow pass re-runs world geometry into a different framebuffer, at a different
        // resolution, with a different projection. Nothing we resolve or draw would be valid there,
        // and writing into the pack's shadow map is actively harmful.
        if (IrisCompat.isShadowPass()) {
            clearRenderingState();
            return null;
        }
        // Resolve BEFORE anything binds a framebuffer: resolution goes through Iris' ShaderMap, so
        // it does not depend on MC's main target still being bound (which is what the shader-getter
        // override keys off).
        irisTarget = IrisCompat.resolveFrameTarget(this == ParticleQueueRenderType.TRANSLUCENT_QUEUE.pipeline);
        if (irisTarget != null && !irisTarget.canComposite()) {
            clearRenderingState();
            irisTarget = null;
            return null;
        }
        if (irisTarget != null) {
            // only the pack path hands the framebuffer back; capturing this on the plain path would
            // cost a synchronous glGet and three allocations per build for nothing
            entryFramebuffer = GlStateManager.getBoundFramebuffer();
            entryViewport = PositionedRect.of(GlStateManager.Viewport.x(), GlStateManager.Viewport.y(),
                    GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
        }
        beforeRendering();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        // the draw target was freshly copied from the scene in beforeRendering -> stale sampler
        // (no-op under a shader pack, where the samplers point at Iris' textures directly)
        markSceneSamplerDirty();

        // shaded sub-pass (DRAW and BOTH)
        if (drawMode != SceneView.DrawMode.WIREFRAME) {
            wireframeSubPass = false;
            renderQueuedPasses();
        }
        // wireframe overlay sub-pass (WIREFRAME and BOTH): global polygon LINE mode + the inverse
        // material draws each mesh as inverted-color lines over the (optional) shaded pass.
        if (drawMode != SceneView.DrawMode.DRAW) {
            wireframeSubPass = true;
            GL30.glPolygonMode(GL30.GL_FRONT_AND_BACK, GL30.GL_LINE);
            GL30.glEnable(GL30.GL_POLYGON_OFFSET_LINE);
            GL30.glPolygonOffset(-1.0f, -1.0f);
            renderQueuedPasses();
            GL30.glPolygonOffset(0f, 0f);
            GL30.glDisable(GL30.GL_POLYGON_OFFSET_LINE);
            GL30.glPolygonMode(GL30.GL_FRONT_AND_BACK, GL30.GL_FILL);
            wireframeSubPass = false;
        }

        renderMaskSubPass();

        clearRenderingState();
        afterRendering();
        return null;
    }

    /**
     * CustomMask/CustomDepth (Unreal CustomDepth/Stencil-style, no GL stencil): redraw every
     * flagged pass into MASK_TARGET as a flat mask id. The target carries its own depth,
     * pre-filled from the scene so occlusion clips the mask AND flagged passes write their own
     * depth (= custom depth) without touching the main depth buffer. Skipped entirely — no
     * target, no draws — while nothing is flagged.
     */
    private void renderMaskSubPass() {
        boolean anyMask = false;
        for (var entry : particles.entrySet()) {
            if (!entry.getValue().isEmpty() && entry.getKey().renderer.isWriteCustomMask()) {
                anyMask = true;
                break;
            }
        }
        if (!anyMask) return;
        // demand-driven: flagged emitters cost nothing unless some pending effect this frame
        // actually reads the mask (MaskFilter request / Custom Mask input / the editor mask view)
        if (!PostEffectStack.currentSink().hasPendingMaskConsumer()) return;
        // clear() rebinds with a full-target viewport — preserve the current one (the editor
        // scene renders in a sub-viewport and the mask must stay pixel-aligned with it)
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();
        if (MASK_TARGET == null) {
            MASK_TARGET = new FormatTarget(
                    DRAW_TARGET.width, DRAW_TARGET.height, GL11.GL_LINEAR,
                    TargetFormat.R8, true);
        } else if (MASK_TARGET.width != DRAW_TARGET.width || MASK_TARGET.height != DRAW_TARGET.height) {
            MASK_TARGET.resize(DRAW_TARGET.width, DRAW_TARGET.height, Minecraft.ON_OSX);
        }
        MASK_TARGET.setClearColor(0f, 0f, 0f, 0f);
        MASK_TARGET.clear(Minecraft.ON_OSX);
        MASK_TARGET.copyDepthFrom(DRAW_TARGET);
        MASK_TARGET.bindWrite(false);
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        maskSubPass = true;
        renderQueuedPasses();
        maskSubPass = false;
        maskColorTexture = MASK_TARGET.getColorTextureId();
        maskDepthTexture = MASK_TARGET.getDepthTextureId();
        DRAW_TARGET.bindWrite(false);
    }

    /** Frame boundary: mask textures are only valid for the frame their sub-pass ran in — a
     *  no-particle frame must not feed post effects last frame's (stale) mask. */
    public static void clearFrameMask() {
        maskColorTexture = -1;
        maskDepthTexture = -1;
        // a frame that produced an AFTER_PACK layer but never reached the composite hook (screenshot
        // paths, a cancelled level render) must not leak it into the next frame
        pendingAfterPackLayer = null;
        afterPackLayerStarted = false;
    }

    /** Draw all queued render passes once for the current sub-pass. The queues are iterated (not
     * drained), so this can safely run twice for {@link SceneView.DrawMode#BOTH}. */
    private void renderQueuedPasses() {
        for (var entry : particles.entrySet()) {
            var renderPass = entry.getKey();
            var particleQueue = entry.getValue();
            if (!particleQueue.isEmpty()) {
                renderPass.prepareStatus(this);
                var drewSomething = renderPass.drawParticles(this, particleQueue, camera, partialTicks);
                renderPass.releaseStatus(this);
                if (drewSomething) {
                    // only a pass that wrote pixels can change what a later scene-sampling
                    // material sees; skipping the mark avoids a redundant scene re-copy
                    markSceneSamplerDirty();
                }
            }
        }
    }

    /**
     * Whether Photon's own bloom should run on this build's FX layer.
     *
     * <p>Under a pack it depends on where the layer ends up. When we composite into the pack's own
     * colour target the pack's bloom chain will process our FX along with everything else, so ours
     * would double up — off unless explicitly asked for. When the layer is held back to after the
     * pack's passes ({@link IrisCompositeMode#AFTER_PACK}) the pack never sees those pixels, so
     * <b>nothing</b> would bloom them; ours is the only bloom they can get.
     */
    private boolean wantsBloom() {
        if (!PhotonParticleManager.isSceneBloomEnabled() || !PhotonConfig.INSTANCE.enableBloom.get()) {
            return false;
        }
        if (irisTarget == null) return true;
        return irisTarget.compositeMode() == IrisCompositeMode.AFTER_PACK
                || PhotonConfig.INSTANCE.enableBloomWithIrisShader.get();
    }

    private void beforeRendering() {
        current = this;
        var mode = PhotonParticleManager.getDrawMode();
        drawMode = mode == null ? SceneView.DrawMode.DRAW : mode;
        // Size the accumulator to whatever we will composite ONTO, because the composite is a
        // texelFetch at matching resolution:
        //  - into the pack's own target -> the pack's buffer size. Render scale (TAAU) and
        //    per-buffer `size.buffer.colortexN` make that differ from MC's window, and a mismatch
        //    shows up as FX that do not line up with the scene.
        //  - AFTER_PACK -> MC's main target, since that is where the layer eventually lands.
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        boolean packSized = irisTarget != null && irisTarget.compositeMode() != IrisCompositeMode.AFTER_PACK;
        int width = packSized ? irisTarget.width() : mainTarget.width;
        int height = packSized ? irisTarget.height() : mainTarget.height;
        if (irisTarget == null || wantsBloom()) {
            PhotonPostProcessing.prepareTarget(width, height);
        }
        prepareTarget(width, height); // ends bound to DRAW_TARGET
    }

    public static HDRTarget resize(@Nullable HDRTarget target, int width, int height, boolean useDepth) {
        return resize(target, width, height, useDepth, false);
    }

    /**
     * Allocate or re-shape a target, <b>leaving the bound framebuffer and the viewport exactly as they
     * were</b>.
     *
     * <p>That guarantee is the whole point of routing allocation through here: creating or resizing an
     * {@link HDRTarget} binds it and sets the viewport to its own size (ctor → {@code createBuffers} →
     * {@code clear} → {@code bindWrite(true)}). Callers allocate in the middle of a draw — the bloom
     * pyramid mid-frame, the scene sampler mid particle pass, the editor preview inside a sub-viewport —
     * and a leaked binding or a leaked mip-sized viewport silently redirects everything drawn after it
     * (that is how the clouds ended up in the backbuffer). Only allocation frames pay for the restore.
     */
    public static HDRTarget resize(@Nullable HDRTarget target, int width, int height, boolean useDepth, boolean forceResize) {
        if (target != null && !forceResize && target.width == width && target.height == height) {
            return target; // no allocation, nothing to restore
        }
        int framebuffer = GlStateManager.getBoundFramebuffer();
        int viewportX = GlStateManager.Viewport.x();
        int viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width();
        int viewportHeight = GlStateManager.Viewport.height();
        if (target == null) {
            target = new HDRTarget(width, height, GL11.GL_LINEAR, useDepth);
            target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        } else {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        return target;
    }

    public static void markDrawTargetDirty() {
        IS_DRAW_TARGET_DIRTY = true;
    }

    private void prepareTarget(int width, int height) {
        DRAW_TARGET = resize(DRAW_TARGET, width, height, true, IS_DRAW_TARGET_DIRTY);
        IS_DRAW_TARGET_DIRTY = false;
        if (irisTarget != null) {
            prepareIrisAccumulator();
            return;
        }
        // No shader pack: DRAW_TARGET is a working copy of the frame, so the write-back can replace.
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        DRAW_TARGET.copyColorFrom(mainTarget);
        if (!DRAW_TARGET.hasOtherAttachedDepthTexture() || DRAW_TARGET.getAttachedDepthTexture() != mainTarget.getDepthTextureId()) {
            DRAW_TARGET.attachDepthBuffer(mainTarget);
        }
        // this path owns the attachment now; the pack path must re-attach when it takes over again
        attachedDepthTexture = -1;
        DRAW_TARGET.bindWrite(false);
    }

    /**
     * Set {@link #DRAW_TARGET} up as a <b>transparent premultiplied accumulator</b> for the shader-pack
     * path — deliberately NOT seeded from the pack's buffer.
     *
     * <p>Copying it is what the old code did, and it is only meaningful when the pack's particle
     * program writes the scene colour (BSL, Complementary). On a deferred pack that buffer is the
     * translucent layer, cleared to zero every frame — copying it gives black, and replacing it
     * afterwards throws away the water and weather already accumulated there. What Photon actually
     * owns is the FX <i>layer</i>; building it standalone and letting
     * {@code SceneBlit.compositePremultipliedToBound} blend it in reproduces exactly what the pack's
     * own translucent programs do, and works the same whether the target is the scene or an
     * accumulator.
     *
     * <p>Depth is shared with the pack (occlusion against the real scene) but never cleared:
     * {@code RenderTarget.clear} would clear the depth attachment too, i.e. wipe the pack's
     * {@code depthtex0}. Hence the explicit colour-only clear.
     */
    private void prepareIrisAccumulator() {
        assert irisTarget != null;
        int depthTexture = irisTarget.depthTexture();
        if (depthTexture != 0 && (attachedDepthTexture != depthTexture
                || attachedDepthVersion != irisTarget.depthBufferVersion()
                || attachedDepthFramebuffer != DRAW_TARGET.frameBufferId)) {
            DRAW_TARGET.attachDepthBuffer(depthTexture);
            attachedDepthTexture = depthTexture;
            attachedDepthVersion = irisTarget.depthBufferVersion();
            attachedDepthFramebuffer = DRAW_TARGET.frameBufferId;
        }
        DRAW_TARGET.bindWrite(false);
        RenderSystem.viewport(entryViewport.position.x, entryViewport.position.y,
                entryViewport.size.width, entryViewport.size.height);
        // AFTER_PACK keeps one layer for the whole frame (both queues accumulate into it before it
        // is composited), so it must be cleared once per frame, not once per build.
        boolean deferred = irisTarget.compositeMode() == IrisCompositeMode.AFTER_PACK;
        if (!deferred || !afterPackLayerStarted) {
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._clearColor(0f, 0f, 0f, 0f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        }
        if (deferred) afterPackLayerStarted = true;
    }

    private void afterRendering() {
        if (irisTarget != null) {
            compositeToShaderPack();
            return;
        }
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        var lastViewport = PositionedRect.of(GlStateManager.Viewport.x(), GlStateManager.Viewport.y(), GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
        var background = Minecraft.getInstance().getMainRenderTarget();
        var hasDifferentViewPort = lastViewport.position.x != 0 ||
                lastViewport.position.y != 0 ||
                lastViewport.size.width != background.width ||
                lastViewport.size.height != background.height;
        // setup view port
        if (hasDifferentViewPort) {
            RenderSystem.viewport(0, 0, background.width, background.height);
        }

        var doBloom = wantsBloom();
        // Bloom and the custom effect chain want DIFFERENT timing, and the opaque/translucent queues
        // own separate pipelines that may both build in one frame:
        //
        //  - BLOOM must run on EVERY build. It is an HDR effect, and DRAW_TARGET (RGBA16F) is the only
        //    place this build's overbright exists: writing back to the main target (RGBA8) clamps it to
        //    1, and the bright pass keeps only luma > Threshold (1 by default). So a build that skips
        //    bloom can never get it back — its highlights are already gone by the next build.
        //  - THE EFFECT CHAIN must run once, on the LAST build. Effects are whole-frame image
        //    operations; running them per build applies them twice.
        //
        // Treating both the same is what broke this: whichever queue built first consumed the frame,
        // so a single opaque particle could take the bloom away from every translucent one.
        RenderTarget outputTarget = isLastBuildThisFrame()
                ? PostEffectStack.currentSink().consumeAndExecute(DRAW_TARGET, doBloom)
                : (doBloom ? PhotonPostProcessing.postTarget(DRAW_TARGET) : DRAW_TARGET);

        // a sub-viewport means this scene is embedded inside a larger frame (the editor scene view):
        // the chain processed the whole frame, but the write-back must not touch pixels outside the
        // viewport — UI drawn before the scene would get post-processed too
        int[] uiScissorBox = null;
        if (hasDifferentViewPort) {
            uiScissorBox = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, uiScissorBox);
            RenderSystem.enableScissor(lastViewport.position.x, lastViewport.position.y,
                    lastViewport.size.width, lastViewport.size.height);
        }

        SceneBlit.writeBack(outputTarget, mainTarget);

        // restore the UI clip state the scene render suspended (the box outlives the disabled test)
        if (uiScissorBox != null) {
            RenderSystem.disableScissor();
            GlStateManager._scissorBox(uiScissorBox[0], uiScissorBox[1], uiScissorBox[2], uiScissorBox[3]);
        }

        // restore view port
        if (hasDifferentViewPort){
            RenderSystem.viewport(lastViewport.position.x, lastViewport.position.y, lastViewport.size.width, lastViewport.size.height);
        }

        RenderSystem.setShader(GameRenderer::getParticleShader);
        current = null;
    }

    /**
     * Hand the accumulated FX layer to the shader pack.
     *
     * <p>Three things here are load-bearing:
     *
     * <ul>
     *   <li><b>MC's main render target is never bound.</b> Iris only overrides shaders while
     *       {@code isMainBound}, and binding the main target is what re-arms the depth/colour lock
     *       that silently swallows every write. Keeping our own {@code RenderTarget} bound until the
     *       composite is what makes the whole path work without fighting Iris.</li>
     *   <li>The composite goes through a <b>private single-attachment framebuffer</b>, so the pack's
     *       other draw buffers (normals, specular, …) are never written with undefined values.</li>
     *   <li>Neither Photon's bloom nor its post-effect chain runs here. FX now live in the pack's own
     *       translucent layer, so the pack's bloom/DOF/tonemap already apply to them; Photon's bloom
     *       would double up and would destroy the coverage alpha the composite depends on. The
     *       custom chain runs later, after the pack's final pass.</li>
     * </ul>
     */
    private void compositeToShaderPack() {
        assert irisTarget != null;
        // Defensive: a lock leaked from elsewhere would make the composite a no-op with no other
        // symptom than "the FX vanished".
        if (IrisCompat.isDepthColorLocked()) {
            IrisCompat.degrade("LOCK", "Iris held the depth/colour lock at composite time; released it");
            IrisCompat.unlockDepthColorIfLocked();
        }
        if (irisTarget.compositeMode() == IrisCompositeMode.AFTER_PACK) {
            // Nothing to write yet: the pack's particle target is packed material data. Park the
            // layer and let PhotonPostFX composite it once the pack's own passes are done. Bloom is
            // applied there, once, on the finished layer — not per build.
            pendingAfterPackLayer = DRAW_TARGET;
            pendingAfterPackBloom = wantsBloom();
            restoreEntryState();
            irisTarget = null;
            current = null;
            return;
        }

        int compositeFramebuffer = IrisCompat.compositeFramebuffer(irisTarget);
        if (compositeFramebuffer != 0) {
            int colorTexture = bloomedColorOf(DRAW_TARGET, wantsBloom());
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, compositeFramebuffer);
            RenderSystem.viewport(entryViewport.position.x, entryViewport.position.y,
                    entryViewport.size.width, entryViewport.size.height);
            // Write alpha only into a translucent accumulator, whose alpha IS the coverage the pack
            // blends with. A scene-colour target's alpha is either absent or the pack's own data.
            SceneBlit.compositePremultipliedToBound(colorTexture, DRAW_TARGET.getColorTextureId(),
                    !irisTarget.primaryIsSceneColor());
        }

        restoreEntryState();
        irisTarget = null;
        current = null;
    }

    /**
     * The texture the composite should read <b>colour</b> from: the bloom result when bloom is on,
     * the layer itself otherwise. Coverage always stays with the layer — the bloom chain ends on an
     * opaque alpha, which would destroy the coverage the premultiplied composite depends on.
     *
     * <p>Must be called <b>before</b> the destination framebuffer is bound: the bloom chain binds
     * its own targets and would otherwise leave the wrong one active for the composite.
     */
    private static int bloomedColorOf(RenderTarget layer, boolean bloom) {
        return bloom
                ? PhotonPostProcessing.postTarget(layer).getColorTextureId()
                : layer.getColorTextureId();
    }

    /**
     * Hand the pack back exactly the framebuffer and viewport it gave us.
     *
     * <p>Through {@code bindWrite} when that is MC's main target: Iris tracks {@code isMainBound}
     * from {@code RenderTarget.bindWrite} alone, and our own {@code DRAW_TARGET.bindWrite} set it
     * false. Restoring with a raw GL bind would leave it false for the rest of the frame, silently
     * switching Iris' shader override off for the hand, weather and everything after us. Safe here
     * and not earlier: no foreign shader is applied after this point, so the depth/colour lock is
     * not re-armed.
     */
    private void restoreEntryState() {
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        if (entryFramebuffer == mainTarget.frameBufferId) {
            mainTarget.bindWrite(false);
        } else {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, entryFramebuffer);
        }
        RenderSystem.viewport(entryViewport.position.x, entryViewport.position.y,
                entryViewport.size.width, entryViewport.size.height);
        RenderSystem.setShader(GameRenderer::getParticleShader);
    }

    /**
     * Composite the parked AFTER_PACK layer onto the finished frame. Called once, after the pack's
     * composite and final passes have run, from {@link
     * com.lowdragmc.photon.client.postfx.PhotonPostFX#onLevelRenderComplete()} — before the custom
     * effect chain, so effects see the FX.
     */
    public static void compositePendingAfterPackLayer() {
        var layer = pendingAfterPackLayer;
        if (layer == null) return;
        pendingAfterPackLayer = null;
        // The pack will never see these pixels, so its bloom cannot reach them — ours is the only
        // one they can get. It runs here, once, on the layer both queues finished accumulating.
        int colorTexture = bloomedColorOf(layer, pendingAfterPackBloom);
        // bindWrite(true) also restores the full-frame viewport that the bloom chain left mip-sized
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        SceneBlit.compositePremultipliedToBound(colorTexture, layer.getColorTextureId(), false);
    }

    /**
     * Whether no further Photon build follows this one this frame. The opaque queue always renders
     * before the translucent one (vanilla splits its particle pass in two, and the editor scene calls
     * its manager once per filter), so the translucent build is always last — and an opaque build is
     * only last when nothing is queued behind it.
     */
    private boolean isLastBuildThisFrame() {
        return this == ParticleQueueRenderType.TRANSLUCENT_QUEUE.pipeline
                || !ParticleQueueRenderType.TRANSLUCENT_QUEUE.hasQueuedParticles();
    }

    private void clearRenderingState() {
        particles.clear();
        camera = null;
    }

    public void setupRenderingState(Camera camera, float partialTicks) {
        this.camera = camera;
        this.partialTicks = partialTicks;
    }

    public void pipeQueue(@Nonnull PhotonFXRenderPass renderPass, @Nonnull Collection<IParticle> queue) {
        particles.computeIfAbsent(renderPass, t -> new ArrayDeque<>()).addAll(queue);
    }

    ///  Scene Sampler

    /** What a scene-sampling material (soft particles, refraction, distortion) reads. */
    public record SceneSamplers(int colorTexture, int depthTexture) {}

    /**
     * Scene colour and depth for the materials drawing in this build.
     *
     * <p>Under a shader pack these are the pack's own textures, bound directly: {@code DRAW_TARGET}
     * is a transparent accumulator there, so copying it would hand every sampling material a black
     * frame. No feedback loop is possible — neither texture is a draw target while Photon renders
     * (we are bound to {@code DRAW_TARGET}), and the composite happens after the last sample.
     *
     * <p>The pack's scene colour is <b>lit, pre-tonemap, and in the pack's working colour space</b>
     * (linear Rec.2020 for Photon/SixthSurge), and it does not contain FX drawn earlier in the same
     * frame. Materials authored against the plain path will read differently; that is reported as a
     * degradation rather than silently papered over.
     */
    public @Nonnull SceneSamplers getSceneSamplers() {
        if (irisTarget != null) {
            IrisCompat.degrade("SCENE_SAMPLER", "materials sampling scene colour read the pack's "
                    + "colortex0 (lit, pre-tonemap, pack colour space) instead of the finished frame");
            return new SceneSamplers(irisTarget.sceneColorTexture(), irisTarget.sceneDepthTexture());
        }
        var sampler = getSceneSampler();
        return new SceneSamplers(sampler.getColorTextureId(), sampler.getDepthTextureId());
    }

    public @Nonnull HDRTarget getSceneSampler() {
        if (SCENE_SAMPLER != null && !IS_SCENE_SAMPLER_DIRTY) return SCENE_SAMPLER;
        updateSceneSampler();
        DRAW_TARGET.bindWrite(false);
        return SCENE_SAMPLER;
    }

    public void markSceneSamplerDirty() {
        // On the pack path there is nothing to refresh: samplers point straight at Iris' textures,
        // so this also skips a full-screen copy per drawing pass.
        if (irisTarget != null) return;
        IS_SCENE_SAMPLER_DIRTY = true;
    }

    private void updateSceneSampler() {
        SCENE_SAMPLER = resize(SCENE_SAMPLER, DRAW_TARGET.width, DRAW_TARGET.height, true);
        SCENE_SAMPLER.copyDepthAndColorFrom(DRAW_TARGET);
        IS_SCENE_SAMPLER_DIRTY = false;
    }
}