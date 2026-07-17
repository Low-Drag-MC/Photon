package com.lowdragmc.photon.client.postfx.graph;

/**
 * The pixel format of a pass's output render target. v1 allocates {@link #RGBA16F} only (the HDR
 * pipeline default); the remaining formats are reserved for the Phase-2 configurable-format target.
 */
public enum TargetFormat {
    RGBA16F,
    RGBA8,
    RG16F,
    R16F
}
