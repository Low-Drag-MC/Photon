package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.google.common.collect.Maps;
import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.lowdraglib2.math.PositionedRect;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.photon.client.postfx.runtime.FormatTarget;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.postfx.runtime.SceneBlit;
import com.lowdragmc.photon.client.postprocessing.PhotonPostProcessing;
import com.lowdragmc.photon.core.mixins.iris.ExtendedShaderAccessor;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
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

    @Override
    public @Nullable MeshData build() {
        if (particles.isEmpty()) return null;
        beforeRendering();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        // the draw target was freshly copied from the scene in beforeRendering -> stale sampler
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

    private void beforeRendering() {
        current = this;
        var mode = PhotonParticleManager.getDrawMode();
        drawMode = mode == null ? SceneView.DrawMode.DRAW : mode;
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        PhotonPostProcessing.prepareTarget(mainTarget.width, mainTarget.height);
        prepareTarget(mainTarget.width, mainTarget.height); // ends bound to DRAW_TARGET
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
        int framebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
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
        // we will copy the color texture and share the depth texture of the main target.
        if (Photon.isShaderModInstalled() && GameRenderer.getParticleShader() instanceof ExtendedShaderAccessor extendedShader) {
            // iris has its own separated fbo. we should use it instead
            GlFramebuffer fbo = extendedShader.getParent().isBeforeTranslucent ?
                    extendedShader.getWritingToBeforeTranslucent() :
                    extendedShader.getWritingToAfterTranslucent();
            DRAW_TARGET.copyColorFrom(fbo.getId(), width, height);
            if (fbo.hasDepthAttachment()) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.getId());
                boolean useStencil = false;
                int objType = GL30.glGetFramebufferAttachmentParameteri(
                        GL30.GL_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                int depthTexture = GL30.glGetFramebufferAttachmentParameteri(
                        GL30.GL_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                if (objType == GL30.GL_NONE) {
                    objType = GL30.glGetFramebufferAttachmentParameteri(
                            GL30.GL_FRAMEBUFFER,
                            GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);

                    depthTexture = GL30.glGetFramebufferAttachmentParameteri(
                            GL30.GL_FRAMEBUFFER,
                            GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                    if (objType != GL30.GL_NONE) {
                        useStencil = true;
                    }
                }
                if (objType != GL30.GL_NONE) {
                    if (!DRAW_TARGET.hasOtherAttachedDepthTexture() || DRAW_TARGET.getAttachedDepthTexture() != depthTexture) {
                        DRAW_TARGET.attachDepthBufferInternal(depthTexture, useStencil, true);
                    }
                }
            }
        } else {
            var mainTarget = Minecraft.getInstance().getMainRenderTarget();
            DRAW_TARGET.copyColorFrom(mainTarget);
            if (!DRAW_TARGET.hasOtherAttachedDepthTexture() || DRAW_TARGET.getAttachedDepthTexture() != mainTarget.getDepthTextureId()) {
                DRAW_TARGET.attachDepthBuffer(mainTarget);
            }
        }
        DRAW_TARGET.bindWrite(false);
    }

    private void afterRendering() {
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

        var doBloom = PhotonParticleManager.isSceneBloomEnabled() && PhotonConfig.INSTANCE.enableBloom.get() && (!Photon.isUsingShaderPack() || PhotonConfig.INSTANCE.enableBloomWithIrisShader.get());
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

        // we need it because extended shaders only work while the main target bound.
        mainTarget.bindWrite(false);
        if (Photon.isShaderModInstalled() && GameRenderer.getParticleShader() instanceof ExtendedShaderAccessor extendedShader) {
            // We want to blit our result back to iris's fbo
            GlFramebuffer fbo = extendedShader.getParent().isBeforeTranslucent ?
                    extendedShader.getWritingToBeforeTranslucent() :
                    extendedShader.getWritingToAfterTranslucent();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.getId());
            // Unlock depth colour from the iris manager — and it MUST happen between the shader's
            // apply() and the mask setup, which is exactly what the hook is. Iris takes that lock when
            // a shader it does not manage is applied, and while it is held every colour-mask call is
            // swallowed, so the blit writes nothing at all and FX simply vanish under a shader pack.
            SceneBlit.writeBackToBound(outputTarget.getColorTextureId(), DepthColorStorage::unlockDepthColor);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainTarget.frameBufferId);
        } else {
            SceneBlit.writeBack(outputTarget, mainTarget);
        }

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
    public @Nonnull HDRTarget getSceneSampler() {
        if (SCENE_SAMPLER != null && !IS_SCENE_SAMPLER_DIRTY) return SCENE_SAMPLER;
        updateSceneSampler();
        DRAW_TARGET.bindWrite(false);
        return SCENE_SAMPLER;
    }

    public void markSceneSamplerDirty() {
        IS_SCENE_SAMPLER_DIRTY = true;
    }

    private void updateSceneSampler() {
        SCENE_SAMPLER = resize(SCENE_SAMPLER, DRAW_TARGET.width, DRAW_TARGET.height, true);
        SCENE_SAMPLER.copyDepthAndColorFrom(DRAW_TARGET);
        IS_SCENE_SAMPLER_DIRTY = false;
    }
}