package com.lowdragmc.photon.client.postfx.runtime;


/**
 * M0 stub (original in git history, 1.21 branch). Was an LDLib2 {@code HDRTarget} subclass that
 * re-specified the color texture to a non-RGBA16F {@code TargetFormat} (RGBA8/RG16F/R16F/R8) via
 * raw GlStateManager calls.
 * <p>
 * TODO(M3): formats map directly to {@code com.mojang.blaze3d.textures.TextureFormat} when the
 * Photon target pool is rebuilt on GpuTexture — this class likely disappears entirely.
 */
public final class FormatTarget {
    private FormatTarget() {
    }
}
