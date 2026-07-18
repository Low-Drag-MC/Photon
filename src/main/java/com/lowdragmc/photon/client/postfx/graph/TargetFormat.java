package com.lowdragmc.photon.client.postfx.graph;

/**
 * The pixel format of a pass's output render target ({@link #RGBA16F} is the HDR pipeline
 * default). {@link #R8} is also what the CustomMask target uses (1 byte/px — the 8-bit group id
 * encoding was designed for it). Persisted by NAME (never ordinal), so appending formats is safe.
 */
public enum TargetFormat {
    RGBA16F,
    RGBA8,
    RG16F,
    R16F,
    R8
}
