package com.lowdragmc.photon.uitest;

import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * Hides the HUD for a scenario's pictures and puts back what it was. The harness runs with it hidden: a scenario that
 * showed it on teardown left the crosshair, the hotbar and the hand in every later scenario's pictures, and pixel
 * checks that passed alone failed after it (kila_effects behind the light scenarios).
 */
final class HiddenGui {
    @Nullable
    private static Boolean before;

    private HiddenGui() {
    }

    static void hide(Minecraft minecraft) {
        if (before == null) before = minecraft.options.hideGui;
        minecraft.options.hideGui = true;
    }

    static void restore(Minecraft minecraft) {
        if (before == null) return;
        minecraft.options.hideGui = before;
        before = null;
    }
}
