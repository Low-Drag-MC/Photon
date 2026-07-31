package com.lowdragmc.photon.client.compat.iris;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

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

    public static void render(GuiGraphics graphics) {
        if (!enabled || !IrisCompat.isModInstalled()) return;
        var font = Minecraft.getInstance().font;
        int y = 2;
        for (var line : IrisDiagnostics.compact(IrisCompat.diagnosticsTarget())) {
            graphics.drawString(font, line, 2, y, 0xFFFFFF55, true);
            y += font.lineHeight + 1;
        }
    }
}
