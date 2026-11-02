package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Counters and timers of Photon's frame for the render benchmark ({@code photon_render_bench}): CPU time per stage,
 * draws, pipeline binds, passes and uploaded bytes, and the GPU time of Photon's work from timestamps written around it.
 * Costs one branch per hook unless {@link #recording}. Render thread only.
 */
public final class PhotonRenderStats {

    public enum Cpu { TICK, EXTRACT, PREPARE, RECORD }

    /** Pairs of timestamps a frame may write. */
    private static final int SPANS = 32;
    private static final int MAX_PENDING = 8;

    public static boolean recording;

    private static final long[] CPU_NS = new long[Cpu.values().length];
    private static int draws;
    private static int binds;
    private static int passes;
    private static long uploaded;

    /** One sample per recorded frame, except {@link Cpu#TICK}: one per client tick. */
    public static final DoubleArrayList[] CPU_MS = Arrays.stream(Cpu.values()).map(cpu -> new DoubleArrayList())
            .toArray(DoubleArrayList[]::new);
    public static final DoubleArrayList GPU_MS = new DoubleArrayList();
    public static final DoubleArrayList DRAWS = new DoubleArrayList();
    public static final DoubleArrayList BINDS = new DoubleArrayList();
    public static final DoubleArrayList PASSES = new DoubleArrayList();
    public static final DoubleArrayList UPLOAD_KB = new DoubleArrayList();

    private record GpuFrame(GpuQueryPool pool, int stamps) {
    }

    private static final List<GpuFrame> PENDING = new ArrayList<>();
    private static final ArrayDeque<GpuQueryPool> FREE = new ArrayDeque<>();
    @Nullable
    private static GpuQueryPool pool;
    private static int stamps;

    private PhotonRenderStats() {
    }

    public static void clear() {
        for (var list : CPU_MS) list.clear();
        GPU_MS.clear();
        DRAWS.clear();
        BINDS.clear();
        PASSES.clear();
        UPLOAD_KB.clear();
    }

    /** A start time for {@link #end}, or 0 when not recording. */
    public static long begin() {
        return recording ? System.nanoTime() : 0;
    }

    public static void end(Cpu cpu, long begin) {
        if (begin != 0) {
            CPU_NS[cpu.ordinal()] += System.nanoTime() - begin;
        }
    }

    /** A client tick of Photon's objects: one sample of its own. */
    public static void endTick(long begin) {
        if (begin != 0) {
            CPU_MS[Cpu.TICK.ordinal()].add((System.nanoTime() - begin) / 1e6);
        }
    }

    public static void draw() {
        if (recording) draws++;
    }

    public static void bind() {
        if (recording) binds++;
    }

    public static void pass() {
        if (recording) passes++;
    }

    public static void uploaded(long bytes) {
        if (recording) uploaded += bytes;
    }

    /** Before GPU work outside a pass; {@code pass}: inside it. Paired with {@link #gpuEnd}. */
    public static void gpuBegin(@Nullable RenderPass pass) {
        stamp(pass);
    }

    public static void gpuEnd(@Nullable RenderPass pass) {
        stamp(pass);
    }

    private static void stamp(@Nullable RenderPass pass) {
        if (!recording || stamps >= SPANS * 2) {
            return;
        }
        if (pool == null) {
            if (PENDING.size() >= MAX_PENDING) {
                return;
            }
            pool = FREE.isEmpty() ? RenderSystem.getDevice().createTimestampQueryPool(SPANS * 2) : FREE.pop();
            stamps = 0;
        }
        if (pass != null) {
            pass.writeTimestamp(pool, stamps++);
        } else {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, stamps++);
        }
    }

    public static void endFrame() {
        if (recording) {
            for (var cpu : Cpu.values()) {
                if (cpu != Cpu.TICK) CPU_MS[cpu.ordinal()].add(CPU_NS[cpu.ordinal()] / 1e6);
            }
            DRAWS.add(draws);
            BINDS.add(binds);
            PASSES.add(passes);
            UPLOAD_KB.add(uploaded / 1024.0);
        }
        Arrays.fill(CPU_NS, 0);
        draws = binds = passes = 0;
        uploaded = 0;
        if (pool != null) {
            // an odd count is a span cut off by the cap: dropped
            PENDING.add(new GpuFrame(pool, stamps & ~1));
            pool = null;
        }
        readBack();
    }

    private static void readBack() {
        var it = PENDING.iterator();
        while (it.hasNext()) {
            var frame = it.next();
            var values = frame.pool().getValues(0, frame.stamps());
            var ready = true;
            for (var value : values) {
                if (value.isEmpty()) {
                    ready = false;
                    break;
                }
            }
            if (!ready) {
                continue;
            }
            long total = 0;
            for (int i = 0; i + 1 < values.length; i += 2) {
                total += Math.max(0, values[i + 1].getAsLong() - values[i].getAsLong());
            }
            if (recording) GPU_MS.add(total / 1e6);
            FREE.push(frame.pool());
            it.remove();
        }
    }
}
