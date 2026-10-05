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
 * GPU timestamps between the stages of the light pass, in a dev environment only. Results arrive a few
 * frames late, so each frame owns a ring slot that is reused only after its timestamps were read back.
 */
@OnlyIn(Dist.CLIENT)
public final class LightPassTimer {
    public enum Stage { COPY, LIGHT, BLUR, VOLUME, COMPOSITE }

    private static final boolean ENABLED = Platform.isDevEnv();
    private static final int RING = 8;
    private static final int MARKS = Stage.values().length + 1;
    private static int[] queries;
    private static final boolean[] PENDING = new boolean[RING];
    private static final boolean[][] MARKED = new boolean[RING][MARKS];
    private static int cursor;
    private static int active = -1;

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

    static void begin() {
        active = -1;
        if (!ENABLED) return;
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
        cursor = (cursor + 1) % RING;
        Arrays.fill(MARKED[active], false);
        mark(0);
    }

    static void mark(Stage stage) {
        mark(stage.ordinal() + 1);
    }

    private static void mark(int index) {
        if (active < 0) return;
        GL33.glQueryCounter(queries[active * MARKS + index], GL33.GL_TIMESTAMP);
        MARKED[active][index] = true;
    }

    static void end(double cpuMillis) {
        if (active < 0) return;
        PENDING[active] = true;
        active = -1;
        if (recording) CPU_MS.add(cpuMillis);
    }

    private static void collect() {
        for (int slot = 0; slot < RING; slot++) {
            if (!PENDING[slot]) continue;
            int last = -1;
            for (int i = MARKS - 1; i >= 0; i--) {
                if (MARKED[slot][i]) {
                    last = i;
                    break;
                }
            }
            if (last <= 0) {
                PENDING[slot] = false;
                continue;
            }
            if (GL15.glGetQueryObjecti(queries[slot * MARKS + last], GL15.GL_QUERY_RESULT_AVAILABLE) != GL11.GL_TRUE) {
                continue;
            }
            PENDING[slot] = false;
            long previous = GL33.glGetQueryObjecti64(queries[slot * MARKS], GL15.GL_QUERY_RESULT);
            long start = previous;
            for (int i = 1; i < MARKS; i++) {
                double ms = 0;
                if (MARKED[slot][i]) {
                    long t = GL33.glGetQueryObjecti64(queries[slot * MARKS + i], GL15.GL_QUERY_RESULT);
                    ms = (t - previous) / 1e6;
                    previous = t;
                }
                if (recording) STAGE_MS[i - 1].add(ms);
            }
            double total = (previous - start) / 1e6;
            smoothed = smoothed < 0 ? total : smoothed * 0.9 + total * 0.1;
            if (recording) TOTAL_MS.add(total);
        }
    }
}
