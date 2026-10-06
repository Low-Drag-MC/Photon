package com.lowdragmc.photon.client.light;

import com.lowdragmc.lowdraglib2.Platform;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.Arrays;

/**
 * GPU timestamps around the stages of the light pass, in a dev environment only. A frame's stages run in three places
 * (the visibility maps with the light list, the pass, the haze after the particles), each run starting with a timestamp
 * of its own. Results arrive a few frames late, so each frame owns a ring slot that is reused only after its timestamps
 * were read back.
 */
@OnlyIn(Dist.CLIENT)
public final class LightPassTimer {
    public enum Stage { VISIBILITY, COPY, LIGHT, BLUR, VOLUME, FOG, VOLUME_BLUR, COMPOSITE, HAZE }

    private static final boolean ENABLED = Platform.isDevEnv();
    private static final int RING = 8;
    private static final int MARKS = 16;
    private static int[] queries;
    private static final boolean[] PENDING = new boolean[RING];
    // per slot and timestamp: the stage ending there, or -1 where a run starts
    private static final int[][] ENDS = new int[RING][MARKS];
    private static final int[] USED = new int[RING];
    private static final double[] FRAME = new double[Stage.values().length];
    private static int cursor;
    private static int active = -1;
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

    /** A frame's light work begins: the previous frame's timestamps are all issued, and this one takes a slot. */
    static void frame() {
        if (!ENABLED) return;
        if (active >= 0 && USED[active] > 0) {
            PENDING[active] = true;
            if (recording) CPU_MS.add(cpu);
            cursor = (cursor + 1) % RING;
        }
        active = -1;
        cpu = 0;
        var caps = GL.getCapabilities();
        if (!caps.OpenGL33 && !caps.GL_ARB_timer_query) return;
        if (queries == null) {
            queries = new int[RING * MARKS];
            for (int i = 0; i < queries.length; i++) {
                queries[i] = GL15.glGenQueries();
            }
        }
        collect();
        if (PENDING[cursor]) return; // the GPU is a whole ring behind: skip timing this frame
        active = cursor;
        USED[active] = 0;
    }

    /** A run of stages starts: the time until the next mark belongs to none of them. */
    static void start() {
        stamp(-1);
    }

    /** A stage ends: the time since the previous timestamp is its. */
    static void mark(Stage stage) {
        stamp(stage.ordinal());
    }

    static void cpu(double millis) {
        cpu += millis;
    }

    private static void stamp(int stage) {
        if (active < 0 || USED[active] == MARKS) return;
        int i = USED[active]++;
        GL33.glQueryCounter(queries[active * MARKS + i], GL33.GL_TIMESTAMP);
        ENDS[active][i] = stage;
    }

    private static void collect() {
        for (int slot = 0; slot < RING; slot++) {
            if (!PENDING[slot]) continue;
            int base = slot * MARKS;
            int used = USED[slot];
            if (GL15.glGetQueryObjecti(queries[base + used - 1], GL15.GL_QUERY_RESULT_AVAILABLE) != GL11.GL_TRUE) {
                continue;
            }
            PENDING[slot] = false;
            Arrays.fill(FRAME, 0);
            long previous = 0;
            for (int i = 0; i < used; i++) {
                long t = GL33.glGetQueryObjecti64(queries[base + i], GL15.GL_QUERY_RESULT);
                if (i > 0 && ENDS[slot][i] >= 0) FRAME[ENDS[slot][i]] += (t - previous) / 1e6;
                previous = t;
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
