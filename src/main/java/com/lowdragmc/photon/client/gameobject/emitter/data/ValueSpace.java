package com.lowdragmc.photon.client.gameobject.emitter.data;

/**
 * Which axes an authored per-particle vector is given in — Unity's "Space" dropdown, and deliberately
 * independent of the emitter's simulation space.
 *
 * <p>{@link #Local}: the emitter's own axes, so {@code (0,1,0)} means "the emitter's up" and rotating
 * the effect re-aims it. {@link #World}: absolute world axes, unaffected by how the emitter is oriented.
 *
 * <p>Every module that offers this choice must resolve {@code Local} the same way, through the
 * particle's {@code SpawnFrame}: live while the emitter IS the simulation space, frozen at spawn
 * otherwise — so particles deliberately left behind in the world are not re-aimed afterwards. Shared
 * rather than re-declared per module precisely so the two cannot drift apart again.
 *
 * <p>Constant names must stay stable: they are persisted by name.
 */
public enum ValueSpace {
    Local,
    World
}
