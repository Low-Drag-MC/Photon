package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.render.PhotonGlobals;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records what the engine writes into the {@code Globals} UBO each frame, so Photon can hand its own
 * views a copy of it with one field changed.
 * <p>
 * A view that renders on its own clock — the editor's timeline, which pauses and scrubs — needs
 * {@code Globals.GameTime} to be THAT clock for the duration of its draws. 26.1 has no
 * {@code setShaderGameTime} any more, and the engine's buffer can be neither mapped for reading nor
 * copied ({@code USAGE_UNIFORM | USAGE_COPY_DST}), so the only way to produce a modified copy is to
 * know the values that went in. Capturing them here means Photon never guesses a single field, and if
 * Minecraft ever changes the layout this stops compiling instead of silently writing garbage.
 *
 * @see PhotonGlobals
 */
@Mixin(GlobalSettingsUniform.class)
public class GlobalSettingsUniformMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void photon$captureInputs(int width, int height, double glintAlpha, long gameTime,
                                      DeltaTracker deltaTracker, int menuBlurRadius, Vec3 cameraPos,
                                      boolean useRgss, CallbackInfo ci) {
        // the time arguments are deliberately dropped: the clock is the one field Photon replaces
        PhotonGlobals.capture(width, height, glintAlpha, menuBlurRadius, cameraPos, useRgss);
    }
}
