package com.lowdragmc.photon.client.emitter;

import com.lowdragmc.lowdraglib.client.shader.Shaders;
import com.lowdragmc.lowdraglib.utils.PositionedRect;
import com.lowdragmc.photon.client.postprocessing.BloomEffect;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import org.lwjgl.opengl.GL11;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote BloomParticleRenderType
 */
@Environment(value= EnvType.CLIENT)
@ParametersAreNonnullByDefault
public class BloomParticleRenderType extends PhotonParticleRenderType {

    public static final BloomParticleRenderType INSTANCE = new BloomParticleRenderType();

    @Override
    public void begin(BufferBuilder builder, TextureManager textureManager) {
        var input = BloomEffect.getInput();
        input.bindWrite(false);
        GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
        int i = GL11.GL_COLOR_BUFFER_BIT;
        if (input.useDepth) {
            GlStateManager._clearDepth(1.0);
            i |= GL11.GL_DEPTH_BUFFER_BIT;
        }
        GlStateManager._clear(i, Minecraft.ON_OSX);
    }

    @Override
    public void end(Tesselator tesselator) {
        var lastViewport = new PositionedRect(GlStateManager.Viewport.x(), GlStateManager.Viewport.y(), GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
        var input = BloomEffect.getInput();
        var output = BloomEffect.getOutput();
        var background = Minecraft.getInstance().getMainRenderTarget();
        if (lastViewport.position.x != 0 ||
                lastViewport.position.y != 0 ||
                lastViewport.size.width != background.width ||
                lastViewport.size.height != background.height){
            RenderSystem.viewport(0, 0, background.width, background.height);
        }

        BloomEffect.renderBloom(input.width, input.height,
                background.getColorTextureId(),
                input.getColorTextureId(),
                output);


        RenderSystem.assertOnRenderThread();
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);

        background.bindWrite(false);

        Shaders.getBlitShader().setSampler("DiffuseSampler", output.getColorTextureId());

        Shaders.getBlitShader().apply();
        GlStateManager._enableBlend();
        RenderSystem.defaultBlendFunc();

        BufferBuilder bufferbuilder = tesselator.getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bufferbuilder.vertex(-1, 1, 0).endVertex();
        bufferbuilder.vertex(-1, -1, 0).endVertex();
        bufferbuilder.vertex(1, -1, 0).endVertex();
        bufferbuilder.vertex(1, 1, 0).endVertex();
        BufferUploader.draw(bufferbuilder.end());
        Shaders.getBlitShader().clear();

        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableDepthTest();

        if (lastViewport.position.x != 0 ||
                lastViewport.position.y != 0 ||
                lastViewport.size.width != background.width ||
                lastViewport.size.height != background.height){
            RenderSystem.viewport(lastViewport.position.x, lastViewport.position.y, lastViewport.size.width, lastViewport.size.height);
        }
//        ShaderUtils.fastBlit(output, background);
//        background.bindWrite(false);
    }
}
