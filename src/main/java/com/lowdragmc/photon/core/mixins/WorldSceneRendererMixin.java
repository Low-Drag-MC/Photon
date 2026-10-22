package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.TerrainDepthCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldSceneRenderer.class, remap = false)
public class WorldSceneRendererMixin {
    @Inject(method = "drawWorld", at = @At("HEAD"))
    private void photon$beginTerrainScene(CallbackInfo ci) {
        TerrainDepthCapture.beginEditor();
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void photon$captureTerrain(CallbackInfo ci) {
        if (!TerrainDepthCapture.needsEditorCapture()) return;
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        TerrainDepthCapture.captureEditor();
    }

    @Inject(method = "drawWorld", at = @At("RETURN"))
    private void photon$endTerrainScene(CallbackInfo ci) {
        TerrainDepthCapture.endEditor();
    }
}
