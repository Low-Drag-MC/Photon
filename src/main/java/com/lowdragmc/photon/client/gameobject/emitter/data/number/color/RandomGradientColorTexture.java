package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.mojang.blaze3d.vertex.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

public class RandomGradientColorTexture extends TransformTexture {

    public final GradientColor gradientColor0;
    public final GradientColor gradientColor1;

    public RandomGradientColorTexture(GradientColor gradientColor0, GradientColor gradientColor1) {
        this.gradientColor0 = gradientColor0;
        this.gradientColor1 = gradientColor1;
    }

    // TODO(M4): register a GuiTextureRenderer so this draws through the 26.1 texture registry
    protected void drawInternal(GUIContext graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
        // render color bar
        GradientColorTexture.drawGradient(graphics, x, y, width, height / 2, gradientColor0);
        GradientColorTexture.drawGradient(graphics, x, y + height / 2, width, height / 2, gradientColor1);
    }

}
