package com.lowdragmc.photon.client.render;

/**
 * The window in which Photon's RGBA8-declared texel buffers are really RGBA32F. 26.1 texel buffers have no float
 * format, so {@code GlConstMixin} remaps the declared RGBA8 to {@code GL_RGBA32F} while one is open — the same
 * respec {@link PhotonInstancedDrawState} has always opened around an instanced draw, for draws that are not
 * instanced: the dynamic-light passes and anything lit by them.
 * <p>
 * ⚠️ No texture may be created inside it: {@code toGlInternalId} also sizes new textures. Render thread only.
 */
public final class PhotonFloatTexelBuffers {
    private static int depth;

    private PhotonFloatTexelBuffers() {
    }

    public static boolean active() {
        return depth > 0;
    }

    public static void run(Runnable draw) {
        depth++;
        try {
            draw.run();
        } finally {
            depth--;
        }
    }
}
