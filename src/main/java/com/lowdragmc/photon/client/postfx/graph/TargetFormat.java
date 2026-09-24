package com.lowdragmc.photon.client.postfx.graph;

import com.mojang.blaze3d.GpuFormat;

/**
 * The pixel format of a pass's output render target ({@link #RGBA16F} is the HDR pipeline
 * default). {@link #R8} is also what the CustomMask target uses (1 byte/px — the 8-bit group id
 * encoding was designed for it). Persisted by NAME (never ordinal), so appending formats is safe.
 * <p>
 * Every entry is a mandatory colour-attachment format on both backends.
 */
public enum TargetFormat {
    RGBA16F(GpuFormat.RGBA16_FLOAT),
    RGBA8(GpuFormat.RGBA8_UNORM),
    RG16F(GpuFormat.RG16_FLOAT),
    R16F(GpuFormat.R16_FLOAT),
    R8(GpuFormat.R8_UNORM);

    private final GpuFormat gpuFormat;

    TargetFormat(GpuFormat gpuFormat) {
        this.gpuFormat = gpuFormat;
    }

    public GpuFormat gpuFormat() {
        return gpuFormat;
    }
}
