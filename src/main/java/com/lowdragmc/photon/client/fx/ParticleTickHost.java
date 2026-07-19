package com.lowdragmc.photon.client.fx;


/**
 * The engine that owns a runtime's particles, as far as {@link FXRuntime#isValid()} is concerned:
 * the vanilla {@code ParticleEngine} ({@link VanillaParticleHost}) or an editor/preview
 * {@code PhotonParticleManager}. Exposes two O(1) counters:
 * <ul>
 *   <li>{@link #tickCount()} — advances once per engine tick, i.e. only when particles actually
 *       tick (a paused game does not advance it). A live root particle records it every tick, so a
 *       stale reading means the engine no longer ticks the runtime — whatever discarded it.</li>
 *   <li>{@link #generation()} — bumped whenever the engine discards particles without killing them
 *       (level change, clear commands), for immediate invalidation of the known wipe paths.</li>
 * </ul>
 */
public interface ParticleTickHost {
    /** Monotonic engine tick counter; advances only when the engine ticks its particles. */
    long tickCount();

    /** Wipe generation; bumped whenever the engine mass-discards particles. */
    int generation();
}
