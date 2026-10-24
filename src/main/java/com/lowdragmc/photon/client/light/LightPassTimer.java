package com.lowdragmc.photon.client.light;

import com.lowdragmc.lowdraglib2.Platform;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GPU time of the stages of the light pass, in a dev environment only. A frame's stages run in three places (the
 * visibility maps with the light list, the pass, the haze after the particles); each run is {@link #start},
 * {@link #mark}s, {@link #stop}.
 * <p>
 * Measured with GPU timestamps, one query pool per frame: {@link #start} and every {@link #mark} write one, and a
 * stage's time is its mark's timestamp minus the one before. Written between passes only. Results arrive a few frames
 * late; a frame's timestamps are read back once all of them are.
 */
public final class LightPassTimer {
    public enum Stage { VISIBILITY, COPY, LIGHT, BLUR, VOLUME, FOG, VOLUME_BLUR, COMPOSITE, HAZE }

    private static final boolean ENABLED = Platform.isDevEnv();
    /** Frames whose timestamps may still be in flight before timing is skipped. */
    private static final int MAX_PENDING = 8;
    /** Timestamps a frame may write: three runs of at most ten each. */
    private static final int STAMPS = 32;

    /** One frame's timestamps; {@code stages[i]} is the stage ending at stamp i, -1 for none. */
    private static final class Frame {
        final GpuQueryPool pool;
        final int[] stages = new int[STAMPS];
        int written;

        Frame(GpuQueryPool pool) {
            this.pool = pool;
        }
    }

    private static final List<Frame> PENDING = new ArrayList<>();
    private static final ArrayDeque<GpuQueryPool> FREE = new ArrayDeque<>();
    private static final double[] FRAME = new double[Stage.values().length];
    @Nullable
    private static Frame current;
    private static boolean running;
    private static double cpu;

    /** Filled while {@link #recording}: milliseconds per frame, per stage, in total and on the CPU. */
    public static final DoubleArrayList[] STAGE_MS = Arrays.stream(Stage.values()).map(stage -> new DoubleArrayList()).toArray(DoubleArrayList[]::new);
    public static final DoubleArrayList TOTAL_MS = new DoubleArrayList();
    public static final DoubleArrayList CPU_MS = new DoubleArrayList();
    public static boolean recording;
    private static double smoothed = -1;

    private LightPassTimer() {
    }

    public static double smoothedMillis() {
        return smoothed;
    }

    public static void clearSamples() {
        for (var list : STAGE_MS) list.clear();
        TOTAL_MS.clear();
        CPU_MS.clear();
    }

    /** Per recorded frame, the time these stages took together. */
    public static DoubleArrayList sum(Stage... stages) {
        var sum = new DoubleArrayList();
        for (int i = 0; i < TOTAL_MS.size(); i++) {
            double ms = 0;
            for (var stage : stages) ms += STAGE_MS[stage.ordinal()].getDouble(i);
            sum.add(ms);
        }
        return sum;
    }

    /** A frame's light work begins: the previous frame's timestamps are all written, and this one starts a pool. */
    static void frame() {
        if (!ENABLED) return;
        running = false;
        if (current != null) {
            if (current.written > 0) {
                PENDING.add(current);
                if (recording) CPU_MS.add(cpu);
            } else {
                FREE.add(current.pool);
            }
        }
        current = null;
        cpu = 0;
        collect();
        if (PENDING.size() >= MAX_PENDING) return; // the GPU is far behind: skip timing this frame
        var pool = FREE.poll();
        current = new Frame(pool != null ? pool : RenderSystem.getDevice().createTimestampQueryPool(STAMPS));
    }

    /** Frees every query pool; timing starts over with new ones. */
    static void release() {
        if (current != null) current.pool.close();
        current = null;
        running = false;
        PENDING.forEach(frame -> frame.pool.close());
        PENDING.clear();
        FREE.forEach(GpuQueryPool::close);
        FREE.clear();
    }

    /** A run of stages starts: the time until the first mark belongs to none of them. */
    static void start() {
        if (stamp(-1)) running = true;
    }

    /** A stage ends: the time since the previous start or mark is its. */
    static void mark(Stage stage) {
        if (running) stamp(stage.ordinal());
    }

    /** A run of stages ends; the time since the last mark is dropped. */
    static void stop() {
        running = false;
    }

    static void cpu(double millis) {
        cpu += millis;
    }

    private static boolean stamp(int stage) {
        var frame = current;
        if (frame == null || frame.written >= STAMPS) return false;
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(frame.pool, frame.written);
        frame.stages[frame.written++] = stage;
        return true;
    }

    private static void collect() {
        var period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
        var iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            var frame = iterator.next();
            var values = frame.pool.getValues(0, frame.written);
            boolean ready = true;
            for (var value : values) {
                if (value.isEmpty()) {
                    ready = false;
                    break;
                }
            }
            if (!ready) continue;
            iterator.remove();
            FREE.add(frame.pool);
            Arrays.fill(FRAME, 0);
            for (int i = 1; i < frame.written; i++) {
                int stage = frame.stages[i];
                if (stage < 0) continue;
                FRAME[stage] += (values[i].getAsLong() - values[i - 1].getAsLong()) * period / 1e6;
            }
            double total = 0;
            for (int stage = 0; stage < FRAME.length; stage++) {
                total += FRAME[stage];
                if (recording) STAGE_MS[stage].add(FRAME[stage]);
            }
            smoothed = smoothed < 0 ? total : smoothed * 0.9 + total * 0.1;
            if (recording) TOTAL_MS.add(total);
        }
    }
}
