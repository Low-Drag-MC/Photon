package com.lowdragmc.photon.client.light;

import com.lowdragmc.lowdraglib2.Platform;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TimerQuery;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GPU time of the stages of the light pass, in a dev environment only. A frame's stages run in three places (the
 * visibility maps with the light list, the pass, the haze after the particles); each run is {@link #start},
 * {@link #mark}s, {@link #stop}.
 * <p>
 * Measured with {@link GpuQuery} elapsed-time queries: {@link #mark} ends the running query (that time is the
 * stage's) and begins the next. Elapsed queries cannot nest, so the timer stands down while vanilla's own frame
 * profiler ({@link TimerQuery}) holds one. Results arrive a few frames late; a frame's queries are read back once
 * all of them are.
 */
public final class LightPassTimer {
    public enum Stage { VISIBILITY, COPY, LIGHT, BLUR, VOLUME, FOG, VOLUME_BLUR, COMPOSITE, HAZE }

    private static final boolean ENABLED = Platform.isDevEnv();
    /** Frames whose queries may still be in flight before timing is skipped. */
    private static final int MAX_PENDING = 8;

    private record Timed(GpuQuery query, int stage) {
    }

    private static final List<List<Timed>> PENDING = new ArrayList<>();
    private static final double[] FRAME = new double[Stage.values().length];
    @Nullable
    private static List<Timed> current;
    @Nullable
    private static GpuQuery open;
    @Nullable
    private static CommandEncoder encoder;
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

    /** A frame's light work begins: the previous frame's queries are all issued, and this one starts a list. */
    static void frame() {
        if (!ENABLED) return;
        stop();
        if (current != null && !current.isEmpty()) {
            PENDING.add(current);
            if (recording) CPU_MS.add(cpu);
        }
        current = null;
        cpu = 0;
        collect();
        if (PENDING.size() >= MAX_PENDING) return; // the GPU is far behind: skip timing this frame
        current = new ArrayList<>();
    }

    /** A run of stages starts: the time until the first mark belongs to none of them. */
    static void start() {
        begin();
    }

    /** A stage ends: the time since the previous start or mark is its. */
    static void mark(Stage stage) {
        if (open == null) return;
        end(stage.ordinal());
        begin();
    }

    /** A run of stages ends; the time since the last mark is dropped. */
    static void stop() {
        if (open != null) end(-1);
    }

    static void cpu(double millis) {
        cpu += millis;
    }

    private static void begin() {
        if (current == null || open != null || TimerQuery.getInstance().isRecording()) return;
        encoder = RenderSystem.getDevice().createCommandEncoder();
        open = encoder.timerQueryBegin();
    }

    private static void end(int stage) {
        var query = open;
        var enc = encoder;
        open = null;
        encoder = null;
        if (query == null || enc == null) return;
        enc.timerQueryEnd(query);
        if (current != null && stage >= 0) {
            current.add(new Timed(query, stage));
        } else {
            query.close();
        }
    }

    private static void collect() {
        var iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            var frame = iterator.next();
            boolean ready = true;
            for (var timed : frame) {
                if (timed.query().getValue().isEmpty()) {
                    ready = false;
                    break;
                }
            }
            if (!ready) continue;
            iterator.remove();
            Arrays.fill(FRAME, 0);
            for (var timed : frame) {
                FRAME[timed.stage()] += timed.query().getValue().orElse(0L) / 1e6;
                timed.query().close();
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
