package com.lowdragmc.photon.core.mixins;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;
import com.lowdragmc.photon.client.PhotonShaders;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin {
    @Shadow @Final @Nullable public Uniform SCREEN_SIZE;

    @Inject(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At("TAIL"), require = 0)
    private void photon$countCreated(CallbackInfo ci) {
        PhotonShaders.onShaderInstanceCreated();
    }

    /**
     * Vanilla takes ScreenSize from the game window, but a UI in its own OS window (a popped-out or floated editor)
     * draws into an off-screen target of that window's size, where {@code gl_FragCoord / ScreenSize} then samples
     * the scene captures at the wrong texel. Lines are LDLib2's: it sizes them by the viewport.
     */
    @Inject(method = "setDefaultUniforms", at = @At("TAIL"))
    private void photon$screenSizeOfSurface(VertexFormat.Mode mode, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                            Window window, CallbackInfo ci) {
        if (SCREEN_SIZE == null || mode == VertexFormat.Mode.LINES || mode == VertexFormat.Mode.LINE_STRIP) return;
        var surface = UISurface.current();
        if (surface.isMainWindow()) return;
        SCREEN_SIZE.set((float) surface.framebufferWidth(), (float) surface.framebufferHeight());
    }
}
