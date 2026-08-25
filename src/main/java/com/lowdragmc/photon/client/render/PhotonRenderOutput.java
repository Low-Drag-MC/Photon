package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * Where drawing is going right now — the 26.1 answer to "the surface I am compositing into".
 *
 * <p>{@code Minecraft.getMainRenderTarget()} means "the game window", and those two are the same thing
 * only while the window is the only place a frame can land. That assumption is already wrong here: a UI
 * element with a mask or a sub-one opacity renders through LDLib2's picture-in-picture pass, and a UI
 * hosted in its own operating-system window has a different target entirely. Anything Photon copies from,
 * sizes itself after, or writes back into has to follow the redirect or it composites into a frame
 * nobody is looking at. (1.21 expressed this as {@code UISurface.currentTarget()}; there is no render
 * target to name in 26.1 — "where drawing goes" IS the pair of output overrides on {@link RenderSystem},
 * which the command encoder resolves when it opens a pass.)
 *
 * <p>Code that genuinely means the game's own frame regardless of where UI is being drawn — the Iris
 * bridge reading the pack's buffers, say — should keep asking Minecraft directly.
 *
 * <p>Render thread only (the overrides are render-thread state).
 */
public final class PhotonRenderOutput {

    private PhotonRenderOutput() {
    }

    /** The colour attachment being drawn into, or null when the main target has none. */
    @Nullable
    public static GpuTextureView color() {
        return RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
    }

    /** The depth attachment being drawn into, or null when the target has none. */
    @Nullable
    public static GpuTextureView depth() {
        return RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride
                : Minecraft.getInstance().getMainRenderTarget().getDepthTextureView();
    }
}
