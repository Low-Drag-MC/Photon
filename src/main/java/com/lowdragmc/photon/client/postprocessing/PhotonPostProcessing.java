package com.lowdragmc.photon.client.postprocessing;

import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.lowdraglib2.client.utils.ShaderUtils;
import com.lowdragmc.lowdraglib2.math.PositionedRect;
import com.lowdragmc.photon.client.PhotonShaders;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
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

@OnlyIn(Dist.CLIENT)
public class PhotonPostProcessing {
    private static final Minecraft MC = Minecraft.getInstance();
    private static int LAST_WIDTH, LAST_HEIGHT;
    private static HDRTarget INPUT, HIGH_LIGHT, OUTPUT;
    private static HDRTarget SWAP2A, SWAP4A, SWAP8A, SWAP2B, SWAP4B, SWAP8B;

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

        SWAP2A = resize(SWAP2A, width / 2, height / 2, false);
        SWAP4A = resize(SWAP4A, width / 4, height / 4, false);
        SWAP8A = resize(SWAP8A, width / 8, height / 8, false);
//        SWAP16A = resize(SWAP16A, width / 16, height / 16, false, GL11.GL_LINEAR);

        SWAP2B = resize(SWAP2B, width / 2, height / 2, false);
        SWAP4B = resize(SWAP4B, width / 4, height / 4, false);
        SWAP8B = resize(SWAP8B, width / 8, height / 8, false);
//        SWAP16B = resize(SWAP16B, width / 16, height / 16, false, GL11.GL_LINEAR);

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
        var unrealComposite = PhotonShaders.getUnrealCompositeShader();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        brightPassShader.setSampler("DiffuseSampler", INPUT);
//        brightPassShader.safeGetUniform("Threshold").set(1f);
        blitShader(brightPassShader, HIGH_LIGHT);

        separableBlur.setSampler("DiffuseSampler", HIGH_LIGHT);
        separableBlur.safeGetUniform("BlurDir").set(1f, 0f);
        separableBlur.safeGetUniform("Radius").set(3);
        separableBlur.safeGetUniform("OutSize").set((float)SWAP2A.width, (float)SWAP2A.height);
        blitShader(separableBlur, SWAP2A);

        separableBlur.setSampler("DiffuseSampler", SWAP2A);
        separableBlur.safeGetUniform("BlurDir").set(0f, 1f);
        separableBlur.safeGetUniform("Radius").set(3);
        separableBlur.safeGetUniform("OutSize").set((float)SWAP2B.width, (float)SWAP2B.height);
        blitShader(separableBlur, SWAP2B);

        separableBlur.setSampler("DiffuseSampler", SWAP2B);
        separableBlur.safeGetUniform("BlurDir").set(1f, 0f);
        separableBlur.safeGetUniform("Radius").set(5);
        separableBlur.safeGetUniform("OutSize").set((float)SWAP4A.width, (float)SWAP4A.height);
        blitShader(separableBlur, SWAP4A);

        separableBlur.setSampler("DiffuseSampler", SWAP4A);
        separableBlur.safeGetUniform("BlurDir").set(0f, 1f);
        separableBlur.safeGetUniform("Radius").set(5);
        separableBlur.safeGetUniform("OutSize").set((float)SWAP4B.width, (float)SWAP4B.height);
        blitShader(separableBlur, SWAP4B);

        separableBlur.setSampler("DiffuseSampler", SWAP4B);
        separableBlur.safeGetUniform("BlurDir").set(1f, 0f);
        separableBlur.safeGetUniform("Radius").set(7);
        separableBlur.safeGetUniform("OutSize").set((float)SWAP8A.width, (float)SWAP8A.height);
        blitShader(separableBlur, SWAP8A);

        separableBlur.setSampler("DiffuseSampler", SWAP8A);
        separableBlur.safeGetUniform("BlurDir").set(0f, 1f);
        separableBlur.safeGetUniform("Radius").set(7);
        separableBlur.safeGetUniform("OutSize").set((float)SWAP8B.width, (float)SWAP8B.height);
        blitShader(separableBlur, SWAP8B);

//        separableBlur.setSampler("DiffuseSampler", SWAP8B);
//        separableBlur.safeGetUniform("BlurDir").set(1f, 0f);
//        separableBlur.safeGetUniform("Radius").set(9);
//        separableBlur.safeGetUniform("OutSize").set((float)SWAP16A.width, (float)SWAP16A.height);
//        blitShader(separableBlur, SWAP16A);
//
//        separableBlur.setSampler("DiffuseSampler", SWAP16A);
//        separableBlur.safeGetUniform("BlurDir").set(0f, 1f);
//        separableBlur.safeGetUniform("Radius").set(9);
//        separableBlur.safeGetUniform("OutSize").set((float)SWAP16B.width, (float)SWAP16B.height);
//        blitShader(separableBlur, SWAP16B);

        unrealComposite.setSampler("DiffuseSampler", INPUT);
        unrealComposite.setSampler("BlurTexture1", SWAP2B);
        unrealComposite.setSampler("BlurTexture2", SWAP4B);
        unrealComposite.setSampler("BlurTexture3", SWAP8B);
//        unrealComposite.setSampler("BlurTexture4", SWAP16B);
        unrealComposite.safeGetUniform("BloomRadius").set(1f);
        blitShader(unrealComposite, OUTPUT);

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
