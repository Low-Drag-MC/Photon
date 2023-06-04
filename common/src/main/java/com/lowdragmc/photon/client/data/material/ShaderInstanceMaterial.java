package com.lowdragmc.photon.client.data.material;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Matrix4f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote ShaderInstanceMaterial
 */
@Environment(EnvType.CLIENT)
@ParametersAreNonnullByDefault
public abstract class ShaderInstanceMaterial implements IMaterial {

    public final ShaderTexture preview = new ShaderTexture();

    abstract public ShaderInstance getShader();

    public void setupUniform() {
    }

    @Override
    public final void begin(BufferBuilder builder, TextureManager textureManager, boolean isInstancing) {
        RenderSystem.setShader(this::getShader);
        setupUniform();
    }

    @Override
    public final void end(Tesselator tesselator, boolean isInstancing) {
    }

    @Override
    public final CompoundTag serializeNBT() {
        return IMaterial.super.serializeNBT();
    }

    @Override
    public IGuiTexture preview() {
        return preview;
    }

    public class ShaderTexture implements IGuiTexture {

        @Override
        public void draw(PoseStack stack, int mouseX, int mouseY, float x, float y, int width, int height) {
            //sub area is just different width and height
            float imageU = 0;
            float imageV = 0;
            float imageWidth = 1;
            float imageHeight = 1;
            Tesselator tessellator = Tesselator.getInstance();
            BufferBuilder bufferbuilder = tessellator.getBuilder();
            begin(bufferbuilder, Minecraft.getInstance().getTextureManager(), false);
            var lightTexture = Minecraft.getInstance().gameRenderer.lightTexture();
            lightTexture.turnOnLightLayer();
            Matrix4f mat = stack.last().pose();
            bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

            bufferbuilder.vertex(mat, x, y + height, 0).uv(imageU, imageV + imageHeight).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();
            bufferbuilder.vertex(mat, x + width, y + height, 0).uv(imageU + imageWidth, imageV + imageHeight).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();
            bufferbuilder.vertex(mat, x + width, y, 0).uv(imageU + imageWidth, imageV).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();
            bufferbuilder.vertex(mat, x, y, 0).uv(imageU, imageV).color(-1).uv2(LightTexture.FULL_BRIGHT).endVertex();

            tessellator.end();
            lightTexture.turnOffLightLayer();
        }
    }

}
