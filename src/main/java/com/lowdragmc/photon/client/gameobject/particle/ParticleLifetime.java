package com.lowdragmc.photon.client.gameobject.particle;

/** How a particle's lifetime turns into its normalized age {@code T}, and when it dies. */
public final class ParticleLifetime {
    private ParticleLifetime() {
    }

    public enum Mode {
        /** T runs from 0 to 1 once and the particle dies at the end of its lifetime. */
        Once,
        /** T runs from 0 to 1 and starts over every lifetime; the particle lives until its emitter is stopped. */
        Loop,
        /** T runs from 0 to 1 and back again; the particle lives until its emitter is stopped. */
        PingPong;

        public boolean cyclic() {
            return this != Once;
        }
    }

    /** T at {@code age} for a particle whose lifetime (one cycle) is {@code lifetime} ticks; 0 without a lifetime. */
    public static float t(Mode mode, float age, int lifetime) {
        if (lifetime <= 0) return 0;
        var phase = age / lifetime;
        if (mode == Mode.Once) return Math.min(Math.max(phase, 0), 1);
        var cycle = (float) Math.floor(phase);
        var into = phase - cycle;
        if (mode == Mode.Loop) return into;
        return ((long) cycle & 1) == 0 ? into : 1 - into;
    }

    /** T of a cyclic particle ending at {@code endAge} (negative: not ending); from there on it holds its last value. */
    public static float t(Mode mode, float age, int lifetime, float endAge) {
        return endAge >= 0 && age >= endAge ? finalT(mode, endAge, lifetime) : t(mode, age, lifetime);
    }

    /** The age a cyclic particle stopped at {@code age} dies at: the end of the cycle it is in. */
    public static float endOfCycle(float age, int lifetime) {
        return (float) ((Math.floor(age / lifetime) + 1) * lifetime);
    }

    /** The T a cyclic particle's last cycle ends on, when that cycle ends at {@code endAge}. */
    public static float finalT(Mode mode, float endAge, int lifetime) {
        if (mode != Mode.PingPong) return 1;
        var cycle = Math.round(endAge / lifetime) - 1;
        return (cycle & 1) == 0 ? 1 : 0;
    }
}
