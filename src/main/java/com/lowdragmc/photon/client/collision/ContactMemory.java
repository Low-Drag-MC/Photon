package com.lowdragmc.photon.client.collision;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/** Which targets reported a collision event, and until when they stay quiet: event de-duplication. Not thread-safe. */
public final class ContactMemory {
    private static final int PRUNE_MIN = 1024;
    /** Target identity to the tick it may report again; {@link Long#MAX_VALUE}: never. */
    private final Long2LongOpenHashMap quietUntil = new Long2LongOpenHashMap();
    private int pruneAt = PRUNE_MIN;

    public ContactMemory() {
        quietUntil.defaultReturnValue(Long.MIN_VALUE);
    }

    /**
     * Whether the target {@code identity} may report at {@code tick}, remembering it when so: it stays quiet for
     * {@code cooldown} ticks after, or for good with a cooldown of 0. Memories shared by emitters with different
     * cooldowns keep each report's own.
     */
    public boolean tryReport(long identity, long tick, int cooldown) {
        if (tick < quietUntil.get(identity)) return false;
        quietUntil.put(identity, cooldown > 0 ? tick + cooldown : Long.MAX_VALUE);
        // a target that may report again is as good as forgotten: drop those, or a long-running emitter that
        // keeps meeting new targets would remember them all
        if (quietUntil.size() >= pruneAt) {
            quietUntil.long2LongEntrySet().removeIf(entry -> entry.getLongValue() <= tick);
            pruneAt = Math.max(PRUNE_MIN, quietUntil.size() * 2);
        }
        return true;
    }

    public void clear() {
        quietUntil.clear();
        pruneAt = PRUNE_MIN;
    }

    int size() {
        return quietUntil.size();
    }
}
