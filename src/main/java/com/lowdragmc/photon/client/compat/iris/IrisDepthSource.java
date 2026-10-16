package com.lowdragmc.photon.client.compat.iris;

public record IrisDepthSource(int framebuffer, int texture, int width, int height, int version) {
    public boolean matches(int texture, int width, int height, int version) {
        return this.texture == texture && this.width == width && this.height == height && this.version == version;
    }
}
