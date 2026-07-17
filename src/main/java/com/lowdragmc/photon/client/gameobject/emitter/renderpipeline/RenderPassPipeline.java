package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.google.common.collect.Maps;
import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.lowdraglib2.client.shader.LDLibShaders;
import com.lowdragmc.lowdraglib2.client.utils.ShaderUtils;
import com.lowdragmc.lowdraglib2.math.PositionedRect;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
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

        clearRenderingState();
        afterRendering();
        return null;
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
        prepareTarget(mainTarget.width, mainTarget.height);
        PhotonPostProcessing.prepareTarget(mainTarget.width, mainTarget.height);
    }

    public static HDRTarget resize(@Nullable HDRTarget target, int width, int height, boolean useDepth) {
        return resize(target, width, height, useDepth, false);
    }

    public static HDRTarget resize(@Nullable HDRTarget target, int width, int height, boolean useDepth, boolean forceResize) {
        if (target == null) {
            target = new HDRTarget(width, height, GL11.GL_LINEAR, useDepth);
            target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        } else if (forceResize || target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
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
        // the post-effect chain (builtin bloom at priority 0 + this frame's requested custom effects);
        // once per frame — the second queue's build passes through (previously bloom ran twice)
        RenderTarget outputTarget = PostEffectStack.currentSink().consumeAndExecute(DRAW_TARGET, doBloom);

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
            RenderSystem.assertOnRenderThread();
            GlStateManager._disableDepthTest();

            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.getId());
            LDLibShaders.getBlitShader().setSampler("DiffuseSampler", outputTarget.getColorTextureId());

            LDLibShaders.getBlitShader().apply();

            // unlock depth color from iris manager
            DepthColorStorage.unlockDepthColor();
            GlStateManager._depthMask(false);
            GlStateManager._colorMask(true, true, true, true);

            Tesselator tesselator = RenderSystem.renderThreadTesselator();
            BufferBuilder bufferbuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            bufferbuilder.addVertex(-1, 1, 0);
            bufferbuilder.addVertex(-1, -1, 0);
            bufferbuilder.addVertex(1, -1, 0);
            bufferbuilder.addVertex(1, 1, 0);
            BufferUploader.draw(bufferbuilder.buildOrThrow());
            LDLibShaders.getBlitShader().clear();

            GlStateManager._depthMask(true);
            GlStateManager._enableDepthTest();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainTarget.frameBufferId);
        } else {
            ShaderUtils.fastBlit(outputTarget, mainTarget);
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