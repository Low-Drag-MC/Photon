package com.lowdragmc.photon.client.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This frame's instance records on the CPU, held from the bake to the upload that merges the runs drawn alike
 * ({@code PhotonWorldRenderState.resolve}). Each thread allocates from its own chunks ({@link PhotonBakeWork} bakes on
 * workers too), kept from frame to frame; everything a frame took is given back at its end.
 */
final class PhotonRecordArena {
    private static final int CHUNK = 1 << 20;
    /** Chunks beyond the most a thread used over this many frames are freed. */
    private static final int IDLE_FRAMES = 600;

    private static final List<Arena> ARENAS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Arena> ARENA = ThreadLocal.withInitial(() -> {
        var arena = new Arena();
        ARENAS.add(arena);
        return arena;
    });

    private PhotonRecordArena() {
    }

    /** {@code bytes} of this frame, 16-byte aligned, valid until {@link #endFrame}. */
    static ByteBuffer allocate(int bytes) {
        return ARENA.get().allocate(bytes);
    }

    /** Render thread, while no bake runs. */
    static void endFrame() {
        for (var arena : ARENAS) {
            arena.endFrame();
        }
    }

    private static final class Arena {
        private final List<ByteBuffer> chunks = new ArrayList<>();
        private int chunk;
        private int used;
        private int highWater;
        private int idle;

        ByteBuffer allocate(int bytes) {
            var aligned = (bytes + 15) & ~15;
            if (chunk < chunks.size() && used + aligned > chunks.get(chunk).capacity()) {
                chunk++;
                used = 0;
            }
            if (chunk == chunks.size()) {
                chunks.add(MemoryUtil.memAlignedAlloc(16, Math.max(CHUNK, aligned)));
            } else if (aligned > chunks.get(chunk).capacity()) {
                MemoryUtil.memAlignedFree(chunks.get(chunk));
                chunks.set(chunk, MemoryUtil.memAlignedAlloc(16, aligned));
            }
            var slice = MemoryUtil.memSlice(chunks.get(chunk), used, bytes);
            used += aligned;
            return slice;
        }

        void endFrame() {
            highWater = Math.max(highWater, used > 0 || chunk > 0 ? chunk + 1 : 0);
            chunk = 0;
            used = 0;
            if (++idle >= IDLE_FRAMES) {
                while (chunks.size() > Math.max(1, highWater)) {
                    MemoryUtil.memAlignedFree(chunks.removeLast());
                }
                idle = 0;
                highWater = 0;
            }
        }
    }
}
