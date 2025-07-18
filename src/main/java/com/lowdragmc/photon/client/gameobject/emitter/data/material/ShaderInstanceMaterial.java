package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.HolderLookup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote ShaderInstanceMaterial
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public abstract class ShaderInstanceMaterial implements IMaterial {

    public final ShaderTexture preview = new ShaderTexture();

    abstract public ShaderInstance getShader(MaterialContext context);

    public void setupUniform(MaterialContext context) {
    }

    @Override
    public ShaderInstance begin(MaterialContext context) {
        setupUniform(context);
        return getShader(context);
    }

    @Override
    public final CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return IMaterial.super.serializeNBT(provider);
    }

    @Override
    public IGuiTexture preview() {
        return preview;
    }

    public class ShaderTexture implements IGuiTexture {

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, float width, float height, float partialTicks) {
            //sub area is just different width and height
            RenderSystem.enableBlend();
            float imageU = 0;
            float imageV = 0;
            float imageWidth = 1;
            float imageHeight = 1;
            var shader = begin(MaterialContext.PREVIEW);
            var lightTexture = Minecraft.getInstance().gameRenderer.lightTexture();
            lightTexture.turnOnLightLayer();
            var mat = graphics.pose().last().pose();
            var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

            buffer.addVertex(mat, x, y + height, 0).setUv(imageU, imageV + imageHeight).setColor(-1).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
            buffer.addVertex(mat, x + width, y + height, 0).setUv(imageU + imageWidth, imageV + imageHeight).setColor(-1).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
            buffer.addVertex(mat, x + width, y, 0).setUv(imageU + imageWidth, imageV).setColor(-1).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
            buffer.addVertex(mat, x, y, 0).setUv(imageU, imageV).setColor(-1).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);

            RenderSystem.setShader(() -> shader);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            end(MaterialContext.PREVIEW);
            lightTexture.turnOffLightLayer();
        }
    }

}
