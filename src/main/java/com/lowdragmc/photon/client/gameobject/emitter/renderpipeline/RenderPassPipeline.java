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
import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;

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
    /** The accumulated FX layer waiting to be composited onto the finished frame ({@code null} =
     *  nothing pending) — used by both deferred paths, Iris {@link IrisCompositeMode#AFTER_PACK} and
     *  the plain {@link FXCompositeMode#LATE}. Both queues accumulate into it before it is consumed. */
    @Nullable
    private static RenderTarget pendingLateLayer = null;
    private static boolean pendingLateBloom = false;
    private static boolean lateLayerStarted = false;
    private final Map<PhotonFXRenderPass, Queue<IParticle>> particles = Maps.newTreeMap(makeRenderPassComparator());
    /** The subset of {@link #particles} the current sub-pass draws. Equal to {@code particles} unless
     *  the plain path split this build into an in-place group and a late-composited one. */
    private Map<PhotonFXRenderPass, Queue<IParticle>> activeGroup = particles;
    /** The passes routed to {@link FXCompositeMode#LATE} this build, {@code null} when none are. */
    @Nullable
    private Map<PhotonFXRenderPass, Queue<IParticle>> lateGroup;
    /** True while the group being drawn accumulates into the standalone late layer. */
    private boolean lateLayer = false;
    @Getter
    private Camera camera;
    @Getter
    private float partialTicks;
    /** Whichever of {@link #INLINE_TARGET} / {@link #LATE_TARGET} the group currently drawing uses. */
    @Getter
    private static HDRTarget DRAW_TARGET;
    /** In-place accumulator: a working copy of the frame on the plain path, the pack's premultiplied
     *  accumulator under Iris. */
    @Nullable
    private static HDRTarget INLINE_TARGET;
    /**
     * The standalone {@link FXCompositeMode#LATE} layer — deliberately a SEPARATE target from
     * {@link #INLINE_TARGET}, not a reuse of it.
     *
     * <p>Vanilla's Fabulous branch renders every particle type in ONE
     * {@code particleEngine.render(..., type -> true)} call, and {@code
     * ClientHooks.makeParticleRenderTypeComparator} orders modded types by
     * {@code System.identityHashCode} — so Photon's opaque queue can build <b>after</b> its
     * translucent one, arbitrarily, per JVM run. Sharing one target meant that opaque build's
     * {@code copyColorFrom(mainTarget)} overwrote the already-parked late layer with a copy of the
     * whole frame, which was then blended back over the frame at composite time.
     */
    @Nullable
    private static HDRTarget LATE_TARGET;
    private static boolean IS_INLINE_TARGET_DIRTY = true;
    private static boolean IS_LATE_TARGET_DIRTY = true;
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
     * straight onto the scene — true on the shader-pack path and on the {@link FXCompositeMode#LATE}
     * path, and never during the mask sub-pass (that one writes flat ids into its own R8 target,
     * where coverage is meaningless).
     *
     * @see PremultipliedBlendPlan
     */
    public boolean isPremultipliedAccumulation() {
        return (irisTarget != null || lateLayer) && !maskSubPass;
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
        current = this;
        var mode = PhotonParticleManager.getDrawMode();
        drawMode = mode == null ? SceneView.DrawMode.DRAW : mode;

        var inlineGroup = routePasses();
        if (irisTarget != null || lateGroup != null) {
            // Only the deferring paths hand the framebuffer back; capturing this when the write-back
            // is going to bind MC's main target anyway would cost a synchronous glGet and three
            // allocations per build for nothing.
            entryFramebuffer = GlStateManager.getBoundFramebuffer();
            entryViewport = PositionedRect.of(GlStateManager.Viewport.x(), GlStateManager.Viewport.y(),
                    GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
        }
        // A throwing pass must not latch the pipeline into a half-built state: `lateLayer` stuck true
        // would send the NEXT build down the layer path with a stale depth attachment, a stuck
        // `current` would keep every material reading a dead pipeline, and — worst of the three — our
        // own framebuffer would still be bound, so the whole rest of the level render would land in
        // it. (That is exactly what the missing-water bug looked like.) The frame is lost either way;
        // the point is that the frame after it is not.
        boolean completed = false;
        try {
            if (!inlineGroup.isEmpty()) {
                renderGroup(inlineGroup, false, lateGroup == null);
            }
            if (lateGroup != null) {
                renderGroup(lateGroup, true, true);
            }
            completed = true;
        } finally {
            if (!completed) {
                UISurface.currentTarget().bindWrite(true);
            }
            clearRenderingState();
            current = null;
        }
        return null;
    }

    /**
     * Split this build's passes between the in-place path and the late-composited layer, returning
     * the in-place group and stashing the other in {@link #lateGroup}.
     *
     * <p>Both maps are views onto {@link #particles}, which stays the union — the mask sub-pass is a
     * per-emitter concept and has to see every pass at once. The common cases (all late, or all
     * in-place) reuse {@code particles} directly rather than allocating.
     */
    private Map<PhotonFXRenderPass, Queue<IParticle>> routePasses() {
        lateGroup = null;
        // Under a shader pack there is nothing to split TO: every pass already accumulates into one
        // layer, and unreproducible blends are approximated rather than rerouted.
        if (irisTarget != null || !isLateCapablePipeline()) return particles;

        boolean anyInline = false;
        boolean anyLate = false;
        for (var entry : particles.entrySet()) {
            // an empty queue draws nothing; letting it vote would allocate and composite a blank
            // layer, and would keep the depth snapshot armed, for a pass with no particles in it
            if (entry.getValue().isEmpty()) continue;
            if (isLatePass(entry.getKey())) anyLate = true; else anyInline = true;
            if (anyInline && anyLate) break;
        }
        if (!anyLate) return particles;
        // Something wants the layer, so keep the snapshot coming even when the global default is
        // VANILLA. Recorded BEFORE the availability check on purpose: that is what arms the capture
        // for an emitter-level override, which would otherwise never get a snapshot to route into.
        OpaqueDepthCapture.demand();
        // No snapshot this frame means no depth to test against — anything drawn into the layer
        // would float in front of the terrain. Fall back rather than render something visibly broken.
        if (!OpaqueDepthCapture.hasCaptureThisFrame()) return particles;
        if (!anyInline) {
            lateGroup = particles;
            return Map.of();
        }
        var inline = new TreeMap<PhotonFXRenderPass, Queue<IParticle>>(makeRenderPassComparator());
        var late = new TreeMap<PhotonFXRenderPass, Queue<IParticle>>(makeRenderPassComparator());
        for (var entry : particles.entrySet()) {
            (isLatePass(entry.getKey()) ? late : inline).put(entry.getKey(), entry.getValue());
        }
        lateGroup = late;
        return inline;
    }

    /**
     * Whether an FX layer is still owed a composite this frame — queued for this very build, or
     * already parked by an earlier one. The post-effect chain has to wait for it either way.
     */
    private boolean deferChainToComposite() {
        return lateGroup != null || isLateLayerPending();
    }

    /** Whether a late layer is structurally possible for this pipeline (ignoring whether a snapshot
     *  actually exists this frame — {@link #routePasses()} checks that after recording demand). */
    private boolean isLateCapablePipeline() {
        // The editor scene draws into a sub-viewport of a screen: it has no clouds and no water to
        // be wrecked by, and no after-renderLevel seam to composite from, so the layer would simply
        // never land. Keep it on the path it has always used.
        if (PhotonParticleManager.getRenderingManager() != null) return false;
        // Opaque-layer FX render in the "solid particles" slot, BEFORE the translucent chunk layer
        // (NeoForge moved them there for MC-161917), so their ordering against water is already
        // right and they write depth like vanilla's opaque particle sheets do.
        return this == ParticleQueueRenderType.TRANSLUCENT_QUEUE.pipeline;
    }

    /**
     * Whether {@code pass} may join the late layer: it has to ask for it, and every one of its
     * materials has to survive premultiplied accumulation exactly. A pass that blends against the
     * destination colour (multiply, min/max) cannot — its backdrop would be transparent black — so
     * it keeps drawing in place, with the vanilla artefacts, rather than being silently approximated.
     */
    private static boolean isLatePass(PhotonFXRenderPass pass) {
        return pass.renderer.getCompositeMode().resolve() == FXCompositeMode.LATE
                && PremultipliedBlendPlan.areLayerSafe(pass.renderer.getMaterials());
    }

    /**
     * Draw one routing group start to finish: prepare its target, run the shaded/wireframe
     * sub-passes, and hand the result on (write-back, pack composite, or park).
     *
     * @param lastGroup whether the mask sub-pass — which covers ALL of this build's passes, not just
     *                  this group — should run here. It has to happen before {@link #afterRendering}
     *                  consumes the draw target, and under a shader pack before the composite
     *                  releases Iris' depth/colour lock.
     */
    private void renderGroup(Map<PhotonFXRenderPass, Queue<IParticle>> group, boolean late, boolean lastGroup) {
        activeGroup = group;
        lateLayer = late;
        beforeRendering();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        // the draw target was freshly prepared -> stale sampler. The late path has to set the flag
        // directly: markSceneSamplerDirty() deliberately swallows it there (see its javadoc), but
        // the first sample of a build still needs a fresh copy of the frame.
        if (late) {
            IS_SCENE_SAMPLER_DIRTY = true;
        } else {
            markSceneSamplerDirty();
        }

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

        if (lastGroup) {
            activeGroup = particles;
            renderMaskSubPass();
            activeGroup = group;
        }

        afterRendering();
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
        // Whatever depth the FX themselves were clipped against — the live scene depth in place, the
        // opaque-only snapshot on the late path — so the mask lines up with what is on screen instead
        // of being cut by a water surface the effect is allowed to draw through.
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
        // a frame that produced a deferred layer but never reached the composite hook (screenshot
        // paths, a cancelled level render) must not leak it into the next frame
        pendingLateLayer = null;
        lateLayerStarted = false;
        // likewise the opaque depth snapshot: a frame that never reached AFTER_BLOCK_ENTITIES must
        // not depth-test this frame's FX against last frame's geometry
        OpaqueDepthCapture.endFrame();
    }

    /** Draw the active group's render passes once for the current sub-pass. The queues are iterated
     * (not drained), so this can safely run twice for {@link SceneView.DrawMode#BOTH}. */
    private void renderQueuedPasses() {
        for (var entry : activeGroup.entrySet()) {
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
        // Size the accumulator to whatever we will composite ONTO, because the composite is a
        // texelFetch at matching resolution:
        //  - into the pack's own target -> the pack's buffer size. Render scale (TAAU) and
        //    per-buffer `size.buffer.colortexN` make that differ from MC's window, and a mismatch
        //    shows up as FX that do not line up with the scene.
        //  - AFTER_PACK -> MC's main target, since that is where the layer eventually lands.
        var mainTarget = UISurface.currentTarget();
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
        IS_INLINE_TARGET_DIRTY = true;
        IS_LATE_TARGET_DIRTY = true;
    }

    private void prepareTarget(int width, int height) {
        if (lateLayer) {
            LATE_TARGET = resize(LATE_TARGET, width, height, true, IS_LATE_TARGET_DIRTY);
            IS_LATE_TARGET_DIRTY = false;
            DRAW_TARGET = LATE_TARGET;
            prepareLateAccumulator();
            return;
        }
        INLINE_TARGET = resize(INLINE_TARGET, width, height, true, IS_INLINE_TARGET_DIRTY);
        IS_INLINE_TARGET_DIRTY = false;
        DRAW_TARGET = INLINE_TARGET;
        if (irisTarget != null) {
            prepareIrisAccumulator();
            return;
        }
        // No shader pack: DRAW_TARGET is a working copy of the frame, so the write-back can replace.
        var mainTarget = UISurface.currentTarget();
        DRAW_TARGET.copyColorFrom(mainTarget);
        if (!DRAW_TARGET.hasOtherAttachedDepthTexture() || DRAW_TARGET.getAttachedDepthTexture() != mainTarget.getDepthTextureId()) {
            DRAW_TARGET.attachDepthBuffer(mainTarget);
        }
        // this path owns the attachment now; the pack path must re-attach when it takes over again
        attachedDepthTexture = -1;
        DRAW_TARGET.bindWrite(false);
    }

    /**
     * Set {@link #DRAW_TARGET} up as a <b>standalone premultiplied FX layer</b> for
     * {@link FXCompositeMode#LATE} — the plain-path twin of {@link #prepareIrisAccumulator()}.
     *
     * <p>Two departures from the in-place path, and both are the whole point:
     *
     * <ul>
     *   <li><b>Not seeded from the frame.</b> The layer is composited with
     *       {@code ONE / ONE_MINUS_SRC_ALPHA} long after the clouds and weather have been drawn; a
     *       working copy of the frame would replace them instead of blending over them. What Photon
     *       owns here is the FX layer, not the picture.</li>
     *   <li><b>Depth comes from {@link OpaqueDepthCapture}</b>, not the live buffer, so the
     *       translucent chunk layer cannot reject fragments behind it. Depth <i>writes</i> then land
     *       in that throwaway snapshot, which is why a material may keep {@code depthMask} on for
     *       FX-vs-FX occlusion without leaking into the clouds and hand drawn after us.</li>
     * </ul>
     */
    private void prepareLateAccumulator() {
        int depthTexture = OpaqueDepthCapture.depthTexture();
        // A resize resets HDRTarget's own attachedDepthTexture to -1 in createBuffers, so this also
        // covers "the target was rebuilt and silently went back to its own depth buffer". The Iris
        // attachment statics are deliberately NOT touched: they track INLINE_TARGET, which this path
        // never binds.
        if (depthTexture != 0 && (!DRAW_TARGET.hasOtherAttachedDepthTexture()
                || DRAW_TARGET.getAttachedDepthTexture() != depthTexture)) {
            DRAW_TARGET.attachDepthBuffer(depthTexture);
        }
        DRAW_TARGET.bindWrite(false);
        // Colour only — RenderTarget.clear would wipe the depth attachment, i.e. the opaque snapshot
        // we are about to test against. One clear per frame: both queues share the pending layer.
        if (!lateLayerStarted) {
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._clearColor(0f, 0f, 0f, 0f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        }
        lateLayerStarted = true;
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
        if (!deferred || !lateLayerStarted) {
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._clearColor(0f, 0f, 0f, 0f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        }
        if (deferred) lateLayerStarted = true;
    }

    private void afterRendering() {
        if (irisTarget != null) {
            compositeToShaderPack();
            return;
        }
        if (lateLayer) {
            parkLateLayer();
            return;
        }
        var mainTarget = UISurface.currentTarget();
        var lastViewport = PositionedRect.of(GlStateManager.Viewport.x(), GlStateManager.Viewport.y(), GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
        var background = UISurface.currentTarget();
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
        //
        // A late layer moves the chain again: it is composited after renderLevel returns, so
        // consuming here would post-process a frame that does not contain those FX yet.
        RenderTarget outputTarget = isLastBuildThisFrame() && !deferChainToComposite()
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
    }

    /**
     * Park the finished {@link FXCompositeMode#LATE} layer for
     * {@link #compositePendingLateLayer()}. Nothing is written to the frame here — that is the
     * entire point of the mode, the layer has to wait until the clouds and weather are down.
     *
     * <p>Restoring the entry binding is not optional: {@code DRAW_TARGET.bindWrite} left our own
     * framebuffer active, and everything vanilla draws after the particle pass (clouds, weather, the
     * world border, later the hand and HUD) would otherwise land in the FX layer. It goes back to the
     * framebuffer we were <i>handed</i> rather than to MC's main target, because in Fabulous the
     * particle pass runs with {@code particlesTarget} bound — vanilla particles queued behind ours
     * still have to land there.
     */
    private void parkLateLayer() {
        pendingLateLayer = DRAW_TARGET;
        pendingLateBloom = wantsBloom();
        restoreEntryState();
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
            pendingLateLayer = DRAW_TARGET;
            pendingLateBloom = wantsBloom();
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
        var mainTarget = UISurface.currentTarget();
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
     * Whether a build has parked an FX layer that still has to be composited this frame.
     *
     * <p>Deliberately NOT {@code lateLayerStarted}: that flag lives until the frame boundary
     * (RenderFrameEvent.Post), i.e. past the GUI, so an editor screen opened over a world with late
     * FX would see it still set and defer its own effect chain to a composite that already happened.
     */
    public static boolean isLateLayerPending() {
        return pendingLateLayer != null;
    }

    /**
     * Composite the parked FX layer onto the finished frame. Called once from {@link
     * com.lowdragmc.photon.client.postfx.PhotonPostFX#onLevelRenderComplete()} — after
     * {@code LevelRenderer.renderLevel} has returned, and before the custom effect chain so effects
     * see the FX.
     *
     * <p>That seam is what buys {@link FXCompositeMode#LATE} its two fixes at once: it sits after the
     * clouds and weather in Fast/Fancy, and after {@code transparencyChain.process()} in Fabulous —
     * where the five layer targets have already been resolved into the main one. Photon never
     * participated in that chain (its FX went into the main/opaque target and were then painted over
     * by water, clouds and weather alike), so Fabulous is fixed by the same code path rather than by
     * a mode of its own.
     */
    public static void compositePendingLateLayer() {
        var layer = pendingLateLayer;
        if (layer == null) return;
        pendingLateLayer = null;
        // Nothing else will ever see these pixels, so no other bloom can reach them — ours is the
        // only one they can get. It runs here, once, on the layer both queues finished accumulating.
        int colorTexture = bloomedColorOf(layer, pendingLateBloom);
        // bindWrite(true) also restores the full-frame viewport that the bloom chain left mip-sized
        UISurface.currentTarget().bindWrite(true);
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
        activeGroup = particles;
        lateGroup = null;
        lateLayer = false;
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
        if (lateLayer) {
            // DRAW_TARGET is a transparent accumulator here, so the usual "copy what we are drawing
            // onto" would hand every sampling material a black frame. Sample the real frame instead.
            //
            // The depth handed out is the LIVE one (water included), NOT the opaque snapshot the
            // layer depth-tests against — deliberately. Hardware rejection against the snapshot is
            // what stops a water surface slicing the effect in half; a DepthFade against the live
            // depth is what makes the part that does reach the water fade out softly instead of
            // ending on a hard line.
            var sampler = getLateSceneSampler();
            return new SceneSamplers(sampler.getColorTextureId(), sampler.getDepthTextureId());
        }
        var sampler = getSceneSampler();
        return new SceneSamplers(sampler.getColorTextureId(), sampler.getDepthTextureId());
    }

    /** The frame as it stands at the particle pass — colour and live depth straight off MC's main
     *  target, since the late layer itself holds neither. */
    private HDRTarget getLateSceneSampler() {
        if (SCENE_SAMPLER != null && !IS_SCENE_SAMPLER_DIRTY) return SCENE_SAMPLER;
        var mainTarget = UISurface.currentTarget();
        SCENE_SAMPLER = resize(SCENE_SAMPLER, DRAW_TARGET.width, DRAW_TARGET.height, true);
        SCENE_SAMPLER.copyDepthAndColorFrom(mainTarget);
        IS_SCENE_SAMPLER_DIRTY = false;
        DRAW_TARGET.bindWrite(false);
        return SCENE_SAMPLER;
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
        // Nor on the late path: what it samples is MC's main target, which no draw of ours touches
        // until the composite. Re-copying it per pass would be a full-screen blit for an image that
        // cannot have changed. The one copy a build does need is forced in renderGroup().
        if (lateLayer) return;
        IS_SCENE_SAMPLER_DIRTY = true;
    }

    private void updateSceneSampler() {
        SCENE_SAMPLER = resize(SCENE_SAMPLER, DRAW_TARGET.width, DRAW_TARGET.height, true);
        SCENE_SAMPLER.copyDepthAndColorFrom(DRAW_TARGET);
        IS_SCENE_SAMPLER_DIRTY = false;
    }
}