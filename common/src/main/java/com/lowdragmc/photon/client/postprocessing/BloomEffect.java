package com.lowdragmc.photon.client.postprocessing;

import com.lowdragmc.photon.IrisFramebufferUtils;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.impl.launch.knot.Knot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote BloomEffect
 */
@Environment(EnvType.CLIENT)
public class BloomEffect {
    private static final Minecraft MC = Minecraft.getInstance();
    private static int LAST_WIDTH, LAST_HEIGHT;
    private static RenderTarget INPUT, TRANSLUCENT_INPUT, GUI_INPUT, OUTPUT;
    private static RenderTarget SWAP2A, SWAP4A, SWAP8A, SWAP2B, SWAP4B, SWAP8B;
    private static final ShaderInstance PARTICLE = loadShader("photon:particle");
    private static final ShaderInstance SEPARABLE_BLUR = loadShader("photon:separable_blur");
    private static final ShaderInstance UNREAL_COMPOSITE = loadShader("photon:unreal_composite");
    private static Field lastFramebuffer;

    private static ShaderInstance loadShader(String shaderName) {
        try {
            return new ShaderInstance(Minecraft.getInstance().getResourceManager(), shaderName, DefaultVertexFormat.POSITION);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static RenderTarget getInput() {
        if (GUI_INPUT == null) {
            GUI_INPUT = resize(null, MC.getWindow().getWidth(), MC.getWindow().getHeight(), true);
            hookDepthBuffer(GUI_INPUT, Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
            hookColorBuffer(GUI_INPUT, Minecraft.getInstance().getMainRenderTarget().getColorTextureId(), GL30.GL_COLOR_ATTACHMENT1);

            //hook main target texture to bloom target attachment1
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        }

        if (INPUT == null || IrisFramebufferUtils.getFboCachedField() != lastFramebuffer) {
            lastFramebuffer = IrisFramebufferUtils.getFboCachedField();
            INPUT = resize(null, MC.getWindow().getWidth(), MC.getWindow().getHeight(), true);
            hookDepthBuffer(INPUT, Photon.getDepthTextureID());
            hookColorBuffer(INPUT, Photon.getSolidTextureID(), GL30.GL_COLOR_ATTACHMENT1);

            //hook main target texture to bloom target attachment1
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});

            TRANSLUCENT_INPUT = resize(null, MC.getWindow().getWidth(), MC.getWindow().getHeight(), true);
            hookDepthBuffer(TRANSLUCENT_INPUT, Photon.getDepthTextureID());
            ((BloomTarget)TRANSLUCENT_INPUT).resetColorTexture(INPUT.getColorTextureId());
            hookColorBuffer(TRANSLUCENT_INPUT, INPUT.getColorTextureId(), GL30.GL_COLOR_ATTACHMENT0);
            hookColorBuffer(TRANSLUCENT_INPUT, Photon.getTranslucentTextureID(), GL30.GL_COLOR_ATTACHMENT1);

            //hook main target texture to bloom target attachment1
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        }

        if (!Photon.isUsingShaderPack() || IrisFramebufferUtils.isRenderingGUIScreen()) {
            return GUI_INPUT;
        }

        return PhotonParticleRenderType.checkLayer(RendererSetting.Layer.Translucent) ? TRANSLUCENT_INPUT : INPUT;
    }

    public static ShaderInstance getBloomShader() {
        return PARTICLE;
    }

    public static void bindBloomShader() {
        RenderSystem.setShader(() -> PARTICLE);
    }

    public static void setBloomColor(Vector4f color) {
        if (RenderSystem.getShader() != null) {
            RenderSystem.getShader().safeGetUniform("BloomColor").set(color);
        }
    }

    public static RenderTarget getOutput() {
        OUTPUT = resize(OUTPUT, MC.getWindow().getWidth(), MC.getWindow().getHeight(), false);
        return OUTPUT;
    }

    public static void hookDepthBuffer(RenderTarget fbo, int depthBuffer) {
        //Hook DepthBuffer
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.frameBufferId);
        if (!Photon.isStencilEnabled(fbo))
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
        else if (Photon.useCombinedDepthStencilAttachment()) {
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
        } else {
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_TEXTURE_2D, depthBuffer, 0);
        }
    }

    public static void hookColorBuffer(RenderTarget fbo, int colorBuffer, int colorAttachment) {
        //Hook ColorBuffer
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.frameBufferId);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, colorAttachment, GL30.GL_TEXTURE_2D, colorBuffer, 0);
    }

    public static void updateScreenSize(int width, int height) {
        if (LAST_WIDTH == width && LAST_HEIGHT == height) return;

        INPUT = resize(null, width, height, true);
        hookDepthBuffer(INPUT, Photon.getDepthTextureID());
        hookColorBuffer(INPUT, Photon.getSolidTextureID(), GL30.GL_COLOR_ATTACHMENT1);

        //hook main target texture to bloom target attachment1
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});

        TRANSLUCENT_INPUT = resize(null, MC.getWindow().getWidth(), MC.getWindow().getHeight(), true);
        hookDepthBuffer(TRANSLUCENT_INPUT, Photon.getDepthTextureID());
        ((BloomTarget)TRANSLUCENT_INPUT).resetColorTexture(INPUT.getColorTextureId());
        hookColorBuffer(TRANSLUCENT_INPUT, INPUT.getColorTextureId(), GL30.GL_COLOR_ATTACHMENT0);
        hookColorBuffer(TRANSLUCENT_INPUT, Photon.getTranslucentTextureID(), GL30.GL_COLOR_ATTACHMENT1);

        //hook main target texture to bloom target attachment1
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});

        GUI_INPUT = resize(null, MC.getWindow().getWidth(), MC.getWindow().getHeight(), true);
        hookDepthBuffer(GUI_INPUT, Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
        hookColorBuffer(GUI_INPUT, Minecraft.getInstance().getMainRenderTarget().getColorTextureId(), GL30.GL_COLOR_ATTACHMENT1);

        //hook main target texture to bloom target attachment1
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});

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

    private static RenderTarget resize(@Nullable RenderTarget target, int width, int height, boolean useDepth) {
        if (target == null) {
            target = new BloomTarget(width, height, useDepth, Minecraft.ON_OSX);
            target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        }
        target.resize(width, height, Minecraft.ON_OSX);
        target.setFilterMode(GL11.GL_LINEAR);
        return target;
    }

    public static void renderBloom(int width, int height, int background, int input, RenderTarget output) {
        updateScreenSize(width, height);

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        SEPARABLE_BLUR.setSampler("DiffuseSampler", input);
        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(1f, 0f);
        SEPARABLE_BLUR.safeGetUniform("Radius").set(3);
        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP2A.width, (float)SWAP2A.height);
        blitShader(SEPARABLE_BLUR, SWAP2A);

        SEPARABLE_BLUR.setSampler("DiffuseSampler", SWAP2A);
        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(0f, 1f);
        SEPARABLE_BLUR.safeGetUniform("Radius").set(3);
        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP2B.width, (float)SWAP2B.height);
        blitShader(SEPARABLE_BLUR, SWAP2B);

        SEPARABLE_BLUR.setSampler("DiffuseSampler", SWAP2B);
        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(1f, 0f);
        SEPARABLE_BLUR.safeGetUniform("Radius").set(5);
        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP4A.width, (float)SWAP4A.height);
        blitShader(SEPARABLE_BLUR, SWAP4A);

        SEPARABLE_BLUR.setSampler("DiffuseSampler", SWAP4A);
        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(0f, 1f);
        SEPARABLE_BLUR.safeGetUniform("Radius").set(5);
        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP4B.width, (float)SWAP4B.height);
        blitShader(SEPARABLE_BLUR, SWAP4B);

        SEPARABLE_BLUR.setSampler("DiffuseSampler", SWAP4B);
        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(1f, 0f);
        SEPARABLE_BLUR.safeGetUniform("Radius").set(7);
        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP8A.width, (float)SWAP8A.height);
        blitShader(SEPARABLE_BLUR, SWAP8A);

        SEPARABLE_BLUR.setSampler("DiffuseSampler", SWAP8A);
        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(0f, 1f);
        SEPARABLE_BLUR.safeGetUniform("Radius").set(7);
        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP8B.width, (float)SWAP8B.height);
        blitShader(SEPARABLE_BLUR, SWAP8B);

//        SEPARABLE_BLUR.setSampler("DiffuseSampler", SWAP8B);
//        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(1f, 0f);
//        SEPARABLE_BLUR.safeGetUniform("Radius").set(9);
//        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP16A.width, (float)SWAP16A.height);
//        blitShader(SEPARABLE_BLUR, SWAP16A);
//
//        SEPARABLE_BLUR.setSampler("DiffuseSampler", SWAP16A);
//        SEPARABLE_BLUR.safeGetUniform("BlurDir").set(0f, 1f);
//        SEPARABLE_BLUR.safeGetUniform("Radius").set(9);
//        SEPARABLE_BLUR.safeGetUniform("OutSize").set((float)SWAP16B.width, (float)SWAP16B.height);
//        blitShader(SEPARABLE_BLUR, SWAP16B);

        UNREAL_COMPOSITE.setSampler("DiffuseSampler", background);
//        UNREAL_COMPOSITE.setSampler("HighLight", input);
        UNREAL_COMPOSITE.setSampler("BlurTexture1", SWAP2B);
        UNREAL_COMPOSITE.setSampler("BlurTexture2", SWAP4B);
        UNREAL_COMPOSITE.setSampler("BlurTexture3", SWAP8B);
//        UNREAL_COMPOSITE.setSampler("BlurTexture4", SWAP16B);
        UNREAL_COMPOSITE.safeGetUniform("BloomRadius").set(1f);
        blitShader(UNREAL_COMPOSITE, output);

        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableDepthTest();
    }

    public static void blitShader(ShaderInstance shaderInstance, RenderTarget dist) {
        dist.clear(Minecraft.ON_OSX);
        dist.bindWrite(false);
        shaderInstance.apply();
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferbuilder = tesselator.getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bufferbuilder.vertex(-1, 1, 0).endVertex();
        bufferbuilder.vertex(-1, -1, 0).endVertex();
        bufferbuilder.vertex(1, -1, 0).endVertex();
        bufferbuilder.vertex(1, 1, 0).endVertex();
        BufferUploader.draw(bufferbuilder.end());
        shaderInstance.clear();
    }

    public static class BloomTarget extends TextureTarget {

        public BloomTarget(int width, int height, boolean useDepth, boolean clearError) {
            super(width, height, useDepth, clearError);
        }

        public void resetColorTexture(int textureId) {
            if (this.colorTextureId > -1) {
                TextureUtil.releaseTextureId(this.colorTextureId);
            }

            this.colorTextureId = textureId;
        }
    }
}
