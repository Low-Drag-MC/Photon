package com.lowdragmc.photon.client.compat.iris;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Optional always-on readout of the resolved shader-pack layout, for sweeping packs without
 * spamming chat. Off by default and free when off.
 */
public final class IrisOverlay {

    private static boolean enabled = false;

    private IrisOverlay() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 26.1's GUI is extraction-based: this appends to the frame's draw list rather than drawing. */
    public static void render(GuiGraphicsExtractor graphics) {
        if (!enabled || !IrisCompat.isModInstalled()) return;
        var font = Minecraft.getInstance().font;
        int y = 2;
        for (var line : IrisDiagnostics.compact(IrisCompat.diagnosticsTarget())) {
            graphics.text(font, line, 2, y, 0xFFFFFF55, true);
            y += font.lineHeight + 1;
        }
    }
}
