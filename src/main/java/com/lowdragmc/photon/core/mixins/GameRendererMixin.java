package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.photon.client.postfx.PhotonPostFX;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The slot for Photon's custom post-effect chain when a shader pack is active.
 *
 * <p>Injecting <b>after the {@code LevelRenderer.renderLevel} call</b> rather than at either
 * method's RETURN/TAIL is deliberate: Iris runs its composite and final passes from a RETURN inject
 * inside that call, and its colour-space conversion from a TAIL inject on this method. Sitting on
 * the call site puts us squarely between the two with no dependence on mixin priority — a
 * RETURN/TAIL inject of our own would be racing Iris by priority number instead.
 *
 * <p>{@code RenderLevelStageEvent.AFTER_LEVEL} would not do: it fires inside {@code renderLevel},
 * before the pack's composites have run.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel("
                            + "Lnet/minecraft/client/DeltaTracker;Z"
                            + "Lnet/minecraft/client/Camera;"
                            + "Lnet/minecraft/client/renderer/GameRenderer;"
                            + "Lnet/minecraft/client/renderer/LightTexture;"
                            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER))
    private void photon$afterLevelRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        PhotonPostFX.onLevelRenderComplete();
    }
}
