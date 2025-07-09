package com.lowdragmc.photon.client.postprocessing;

import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.lowdraglib2.client.utils.ShaderUtils;
import com.lowdragmc.lowdraglib2.math.PositionedRect;
import com.lowdragmc.photon.client.PhotonShaders;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.common.NeoForgeConfig;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

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

    public static void hookDepthBuffer(RenderTarget fbo, int depthBuffer) {
        //Hook DepthBuffer
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.frameBufferId);
        if (!fbo.isStencilEnabled())
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
        else if (NeoForgeConfig.CLIENT.useCombinedDepthStencilAttachment.get()) {
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
        } else {
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
        }
    }

    public static void prepareTarget() {
        var mainTarget = MC.getMainRenderTarget();
        updateScreenSize(mainTarget.width, mainTarget.height);
        INPUT.copyColorFrom(mainTarget);
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
        GlStateManager._disableScissorTest();

        renderBloom();

        GlStateManager._enableScissorTest();

        ShaderUtils.fastBlit(OUTPUT, mainTarget);

        // restore view port
        if (lastViewport.position.x != 0 ||
                lastViewport.position.y != 0 ||
                lastViewport.size.width != background.width ||
                lastViewport.size.height != background.height){
            RenderSystem.viewport(lastViewport.position.x, lastViewport.position.y, lastViewport.size.width, lastViewport.size.height);
        }
    }

    public static void updateScreenSize(int width, int height) {
        if (LAST_WIDTH == width && LAST_HEIGHT == height) return;

        INPUT = resize(INPUT, width, height, true);
        hookDepthBuffer(INPUT, MC.getMainRenderTarget().getDepthTextureId());
        HIGH_LIGHT = resize(HIGH_LIGHT, width, height, false);
        OUTPUT = resize(OUTPUT, width, height, false);

        int mips = 5;

        if (MIPS.size() != mips) {
            MIPS.forEach(Mip::clear);
            MIPS.clear();
            for (int i = 0; i < mips; i++) {
                MIPS.add(new Mip());
            }
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
        var brightPassShader = PhotonShaders.getBrightPassShader();
        var separableBlur = PhotonShaders.getSeparableBlurShader();
        var combinePassShader = PhotonShaders.getBloomScatterPassShader();
        var finalCombinePassShader = PhotonShaders.getBloomFinalScatterPassShader();

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        brightPassShader.setSampler("inputSampler", INPUT);
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
        for (int i = MIPS.size() - 2; i >= 0; i--) {
            var highRes = MIPS.get(i);
            combinePassShader.setSampler("inputA", lowRes);
            combinePassShader.setSampler("inputB", highRes.getSwapB());
            blitShader(combinePassShader, highRes.getSwapA());
            lowRes = highRes.getSwapA();
        }

        finalCombinePassShader.setSampler("inputA", lowRes);
        finalCombinePassShader.setSampler("inputB", INPUT);
        blitShader(finalCombinePassShader, OUTPUT);

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
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
