package com.lowdragmc.photon.uitest;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The time of day and whether it runs, for the scenarios that hold the world at noon or midnight. 26.1 keeps the
 * time on the dimension's clock and its running on the {@code advance_time} rule; 1.21 had both on the level.
 */
final class TestClock {
    private TestClock() {
    }

    /** Ticks on the dimension's clock — either side. */
    static long time(Level level) {
        return level.getDefaultClockTime();
    }

    /** Server thread. */
    static void set(ServerLevel level, long ticks) {
        level.dimensionType().defaultClock()
                .ifPresent(clock -> level.getServer().clockManager().setTotalTicks(clock, ticks));
    }

    static boolean advancing(ServerLevel level) {
        return level.getGameRules().get(GameRules.ADVANCE_TIME);
    }

    /** Server thread. */
    static void setAdvancing(ServerLevel level, boolean advancing) {
        level.getGameRules().set(GameRules.ADVANCE_TIME, advancing, level.getServer());
    }
}
