package com.lowdragmc.photon.uitest;

import net.minecraft.client.Minecraft;

/**
 * Hides the HUD for a scenario's pictures and puts back what it was. The harness runs with it hidden: a scenario that
 * showed it on teardown left the crosshair, the hotbar and the hand in every later scenario's pictures, and pixel
 * checks that passed alone failed after it (kila_effects behind the light scenarios).
 */
final class HiddenGui {
    /** Whether {@link #hide} toggled the hud off, and so {@link #restore} must toggle it back. */
    private static boolean toggled;

    private HiddenGui() {
    }

    // the flag lives on the hud, which only exposes a toggle
    static void hide(Minecraft minecraft) {
        if (toggled || minecraft.gui.hud.isHidden()) return;
        minecraft.gui.hud.toggle();
        toggled = true;
    }

    static void restore(Minecraft minecraft) {
        if (!toggled) return;
        if (minecraft.gui.hud.isHidden()) minecraft.gui.hud.toggle();
        toggled = false;
    }
}
