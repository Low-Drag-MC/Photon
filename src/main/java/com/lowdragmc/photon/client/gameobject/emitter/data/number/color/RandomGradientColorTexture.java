package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.client.shader.LDLibRenderTypes;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.gui.GuiGraphics;

public class RandomGradientColorTexture extends TransformTexture {

    public final GradientColor gradientColor0;
    public final GradientColor gradientColor1;

    public RandomGradientColorTexture(GradientColor gradientColor0, GradientColor gradientColor1) {
        this.gradientColor0 = gradientColor0;
        this.gradientColor1 = gradientColor1;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected void drawInternal(GuiGraphics graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
        // render color bar
        var mat = graphics.pose().last().pose();

        var buffer = graphics.bufferSource().getBuffer(LDLibRenderTypes.guiOverlay());
        RenderSystem.disableDepthTest();

        GradientColorTexture.drawGradient(mat, buffer, x, y, width, height / 2, gradientColor0);
        GradientColorTexture.drawGradient(mat, buffer, x, y + height / 2, width, height / 2, gradientColor1);
    }

}
