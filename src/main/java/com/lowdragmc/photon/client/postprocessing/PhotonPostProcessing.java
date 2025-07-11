package com.lowdragmc.photon.client.postprocessing;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.lowdraglib2.client.shader.LDLibShaders;
import com.lowdragmc.lowdraglib2.client.utils.ShaderUtils;
import com.lowdragmc.lowdraglib2.math.PositionedRect;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.core.mixins.iris.ExtendedShaderAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL46;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class PhotonPostProcessing {
    public static class Mip {
        @Getter
        private HDRTarget swapA, swapB;

        public void updateScreenSize(int width, int height) {
            swapA = resize(swapA, width, height, false);
            swapB = resize(swapB, width, height, false);
        }

        public void clear() {
            if (swapA != null) {
                swapA.destroyBuffers();
            }
            if (swapB != null) {
                swapB.destroyBuffers();
            }
        }
    }
    private static final Minecraft MC = Minecraft.getInstance();
    private static int LAST_WIDTH, LAST_HEIGHT;
    private static HDRTarget INPUT, HIGH_LIGHT, OUTPUT;
    private static List<Mip> MIPS = new ArrayList<>();

    private static ShaderInstance loadShader(String shaderName) {
        try {
            return new ShaderInstance(Minecraft.getInstance().getResourceManager(), ResourceLocation.parse(shaderName), DefaultVertexFormat.POSITION);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void prepareTarget() {
        var mainTarget = MC.getMainRenderTarget();
        checkTargetValid(mainTarget.width, mainTarget.height);
        // we will copy the color texture and share the depth texture of the main target.
        if (Photon.isShaderModInstalled() && GameRenderer.getParticleShader() instanceof ExtendedShaderAccessor extendedShader) {
            // iris has its own separated fbo. we should use it instead
            GlFramebuffer fbo = extendedShader.getParent().isBeforeTranslucent ?
                    extendedShader.getWritingToBeforeTranslucent() :
                    extendedShader.getWritingToAfterTranslucent();
            INPUT.copyColorFrom(fbo.getId(), INPUT.width, INPUT.height);
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
                    if (!INPUT.hasOtherAttachedDepthTexture() || INPUT.getAttachedDepthTexture() != depthTexture) {
                        INPUT.attachDepthBufferInternal(depthTexture, useStencil, true);
                    }
                }
            }
        } else {
            INPUT.copyColorFrom(mainTarget);
            if (!INPUT.hasOtherAttachedDepthTexture() || INPUT.getAttachedDepthTexture() != mainTarget.getDepthTextureId()) {
                INPUT.attachDepthBuffer(MC.getMainRenderTarget());
            }
        }
        INPUT.bindWrite(false);
    }

    public static void postTarget() {
        var mainTarget = MC.getMainRenderTarget();
        var lastViewport = PositionedRect.of(GlStateManager.Viewport.x(), GlStateManager.Viewport.y(), GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
        var background = Minecraft.getInstance().getMainRenderTarget();
        // setup view port
        if (lastViewport.position.x != 0 ||
                lastViewport.position.y != 0 ||
                lastViewport.size.width != background.width ||
                lastViewport.size.height != background.height){
            RenderSystem.viewport(0, 0, background.width, background.height);
        }

        var doBloom = PhotonConfig.INSTANCE.enableBloom.get() && (!Photon.isUsingShaderPack() || PhotonConfig.INSTANCE.enableBloomWithIrisShader.get());
        // TODO do bloom
        renderBloom();

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
            LDLibShaders.getBlitShader().setSampler("DiffuseSampler", OUTPUT.getColorTextureId());

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
            ShaderUtils.fastBlit(OUTPUT, mainTarget);
        }

        // restore view port
        if (lastViewport.position.x != 0 ||
                lastViewport.position.y != 0 ||
                lastViewport.size.width != background.width ||
                lastViewport.size.height != background.height){
            RenderSystem.viewport(lastViewport.position.x, lastViewport.position.y, lastViewport.size.width, lastViewport.size.height);
        }
    }

    public static void checkTargetValid(int width, int height) {
        int mipLevel = PhotonConfig.INSTANCE.bloomMipLevel.get();
        if (LAST_WIDTH == width && LAST_HEIGHT == height && MIPS.size() == mipLevel) return;

        INPUT = resize(INPUT, width, height, true);
        HIGH_LIGHT = resize(HIGH_LIGHT, width, height, false);
        OUTPUT = resize(OUTPUT, width, height, false);

        MIPS.forEach(Mip::clear);
        MIPS.clear();
        for (int i = 0; i < mipLevel; i++) {
            MIPS.add(new Mip());
        }

        var w = width;
        var h = height;
        for (Mip mip : MIPS) {
            w = w / 2;
            h = h / 2;
            mip.updateScreenSize(w, h);
        }

        LAST_WIDTH = width;
        LAST_HEIGHT = height;
    }

    private static HDRTarget resize(@Nullable HDRTarget target, int width, int height, boolean useDepth) {
        if (target == null) {
            target = new HDRTarget(width, height, GL11.GL_LINEAR, useDepth);
            target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        }
        target.resize(width, height, Minecraft.ON_OSX);
        return target;
    }

    private static void renderBloom() {
        if (Platform.isDevEnv() && GL.getCapabilities().GL_KHR_debug) {
            GL46.glPushDebugGroup(GL46.GL_DEBUG_SOURCE_APPLICATION, 0, "photon_bloom");
        }

        var brightPassShader = PhotonShaders.getBrightPassShader();
        var separableBlur = PhotonShaders.getSeparableBlurShader();
        var combinePassShader = PhotonConfig.INSTANCE.bloomMode.get() == PhotonConfig.BloomMode.ADD ?
                PhotonShaders.getBloomAddPassShader() : PhotonShaders.getBloomScatterPassShader();
        var finalCombinePassShader = PhotonShaders.getBloomFinalScatterPassShader();

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        brightPassShader.setSampler("inputSampler", INPUT);
        brightPassShader.safeGetUniform("Threshold").set(PhotonConfig.INSTANCE.bloomThreshold.get().floatValue());
        blitShader(brightPassShader, HIGH_LIGHT);

        // down-sampling
        RenderTarget input = HIGH_LIGHT;
        for (Mip mip : MIPS) {
            var swapA = mip.swapA;
            var swapB = mip.swapB;
            separableBlur.setSampler("inputSampler", input);
            separableBlur.safeGetUniform("BlurDir").set(1f, 0f);
            separableBlur.safeGetUniform("OutSize").set((float) swapA.width, (float) swapA.height);
            blitShader(separableBlur, swapA);

            separableBlur.setSampler("inputSampler", swapA);
            separableBlur.safeGetUniform("BlurDir").set(0f, 1f);
            separableBlur.safeGetUniform("OutSize").set((float) swapB.width, (float) swapB.height);
            blitShader(separableBlur, swapB);
            input = swapB;
        }

        // up-sampling
        RenderTarget lowRes = MIPS.getLast().swapB;
        var bloomIntensity = PhotonConfig.INSTANCE.bloomIntensity.get().floatValue();
        combinePassShader.safeGetUniform("BloomIntensive").set(bloomIntensity);
        for (int i = MIPS.size() - 2; i >= 0; i--) {
            var highRes = MIPS.get(i);
            combinePassShader.setSampler("inputA", lowRes);
            combinePassShader.setSampler("inputB", highRes.getSwapB());
            blitShader(combinePassShader, highRes.getSwapA());
            lowRes = highRes.getSwapA();
        }

        finalCombinePassShader.setSampler("inputA", lowRes);
        finalCombinePassShader.setSampler("inputB", INPUT);
        finalCombinePassShader.safeGetUniform("BloomIntensive").set(bloomIntensity);
        blitShader(finalCombinePassShader, OUTPUT);

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();

        if (Platform.isDevEnv() && GL.getCapabilities().GL_KHR_debug) {
            GL46.glPopDebugGroup();
        }
    }

    public static void blitShader(ShaderInstance shaderInstance, RenderTarget dist) {
        dist.clear(Minecraft.ON_OSX);
        dist.bindWrite(false);
        shaderInstance.apply();
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        var buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1, 1, 0);
        buffer.addVertex(-1, -1, 0);
        buffer.addVertex(1, -1, 0);
        buffer.addVertex(1, 1, 0);
        BufferUploader.draw(buffer.buildOrThrow());
        shaderInstance.clear();
    }
}
