package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.gui.texture.rendering.RegisteredGuiTextureRenderer;
import com.lowdragmc.lowdraglib2.gui.texture.rendering.TransformTextureRenderer;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
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

    void drawInternal(GUIContext graphics, float x, float y, float width, float height) {
        // render color bar
        GradientColorTexture.drawGradient(graphics, x, y, width, height / 2, gradientColor0);
        GradientColorTexture.drawGradient(graphics, x, y + height / 2, width, height / 2, gradientColor1);
    }

    /**
     * The two stacked gradient bars of a random gradient.
     * <p>
     * LDLib2 26.1 draws {@link com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture}s through
     * {@code GuiTextureRendererRegistry} (looked up by class, walking superclasses, falling back to a
     * renderer that only handles {@code GuiTexture} lambdas) — the interface no longer has a draw method,
     * so the 1.21-shaped {@code drawInternal} was dead code and this texture rendered nothing.
     */
    @LDLRegisterClient(name = "photon_random_gradient_color", registry = "ldlib2:gui_texture_renderer")
    public static final class Renderer implements RegisteredGuiTextureRenderer<RandomGradientColorTexture, Renderer> {
        @Override
        public Class<RandomGradientColorTexture> type() {
            return RandomGradientColorTexture.class;
        }

        @Override
        public void draw(RandomGradientColorTexture texture, GUIContext context, float x, float y, float width, float height) {
            TransformTextureRenderer.draw(texture, context, x, y, width, height, RandomGradientColorTexture::drawInternal);
        }
    }
}
