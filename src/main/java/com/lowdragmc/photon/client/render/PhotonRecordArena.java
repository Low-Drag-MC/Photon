package com.lowdragmc.photon.client.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * This frame's instance records on the CPU, held from the bake to the upload that merges the runs drawn alike
 * ({@code PhotonWorldRenderState.resolve}). Chunks are kept from frame to frame; what a frame took is given back at its
 * end. Render thread only.
 */
final class PhotonRecordArena {
    private static final int CHUNK = 1 << 20;
    /** Chunks unused for this many frames in a row beyond the first are freed. */
    private static final int IDLE_FRAMES = 600;

    private static final List<ByteBuffer> CHUNKS = new ArrayList<>();
    private static int chunk;
    private static int used;
    private static int highWater;
    private static int idle;

    private PhotonRecordArena() {
    }

    /** {@code bytes} of this frame, 16-byte aligned, valid until {@link #endFrame}. */
    static ByteBuffer allocate(int bytes) {
        var aligned = (bytes + 15) & ~15;
        if (chunk < CHUNKS.size() && used + aligned > CHUNKS.get(chunk).capacity()) {
            chunk++;
            used = 0;
        }
        if (chunk == CHUNKS.size()) {
            CHUNKS.add(MemoryUtil.memAlignedAlloc(16, Math.max(CHUNK, aligned)));
        } else if (aligned > CHUNKS.get(chunk).capacity()) {
            MemoryUtil.memAlignedFree(CHUNKS.get(chunk));
            CHUNKS.set(chunk, MemoryUtil.memAlignedAlloc(16, aligned));
        }
        var slice = MemoryUtil.memSlice(CHUNKS.get(chunk), used, bytes);
        used += aligned;
        return slice;
    }

    static void endFrame() {
        highWater = Math.max(highWater, used > 0 || chunk > 0 ? chunk + 1 : 0);
        chunk = 0;
        used = 0;
        if (++idle >= IDLE_FRAMES) {
            while (CHUNKS.size() > Math.max(1, highWater)) {
                MemoryUtil.memAlignedFree(CHUNKS.removeLast());
            }
            idle = 0;
            highWater = 0;
        }
    }
}
