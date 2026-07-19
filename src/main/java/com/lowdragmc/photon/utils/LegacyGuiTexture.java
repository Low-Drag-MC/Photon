package com.lowdragmc.photon.utils;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

/**
 * Adapter for the 1.21-era functional {@code IGuiTexture} lambdas
 * {@code (graphics, mouseX, mouseY, x, y, width, height, partialTicks) -> ...}. 26.1's functional
 * texture ({@link GuiTexture}) is 5-arg with mouse/partial-tick carried on the {@link GUIContext};
 * this keeps Photon's many editor draw lambdas source-compatible via a cast.
 */
@FunctionalInterface
public interface LegacyGuiTexture extends GuiTexture {

    void drawLegacy(GUIContext graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks);

    @Override
    default void draw(GUIContext context, float x, float y, float width, float height) {
        drawLegacy(context, context.mouseX, context.mouseY, x, y, width, height, context.partialTick);
    }
}
