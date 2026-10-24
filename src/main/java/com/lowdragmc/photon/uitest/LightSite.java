package com.lowdragmc.photon.uitest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Where the light scenarios build the demo scene, and how they put the world back. ⚠️ The scene wipes a
 * box, sets a clear midnight, stops the daylight cycle and random ticks: left at the player's feet, every later scenario
 * in the run renders a different world (soft_particle failed on exactly that).
 */
final class LightSite {
    private static final int OFFSET = 64;
    private static Vec3 home;
    private static float yaw, pitch;
    private static boolean flying;
    /** null when the dimension keeps no clock */
    private static Long dayTime;
    private static boolean daylight;
    private static int clearTime, rainTime;
    private static boolean raining, thundering;
    private static int randomTickSpeed;

    private LightSite() {
    }

    /** Server thread. Remembers the player, the clock, the weather and the daylight rule; returns the scene origin. */
    static BlockPos claim(ServerPlayer player) {
        if (home == null) {
            home = player.position();
            yaw = player.getYRot();
            pitch = player.getXRot();
            flying = player.getAbilities().flying;
            var level = player.level();
            var server = level.getServer();
            // 26.1 keeps the time on the dimension's clock and the weather on the server
            dayTime = level.dimensionTypeRegistration().value().defaultClock()
                    .map(clock -> server.clockManager().getTotalTicks(clock)).orElse(null);
            daylight = TestClock.advancing(level);
            var weather = server.getWeatherData();
            clearTime = weather.getClearWeatherTime();
            rainTime = weather.getRainTime();
            raining = weather.isRaining();
            thundering = weather.isThundering();
            randomTickSpeed = level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        }
        // blocks stay what the scene built: grass under a slab, say, would turn to dirt within the run
        player.level().getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, player.level().getServer());
        NoMobs.claim(player);
        return BlockPos.containing(home).below().offset(OFFSET, 0, 0);
    }

    /** Server thread. */
    static void release(ServerPlayer player) {
        if (home == null) return;
        var level = player.level();
        var server = level.getServer();
        if (dayTime != null) TestClock.set(level, dayTime);
        server.setWeatherParameters(clearTime, rainTime, raining, thundering);
        TestClock.setAdvancing(level, daylight);
        level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, randomTickSpeed, server);
        NoMobs.release(player);
        player.getAbilities().flying = flying;
        player.onUpdateAbilities();
        player.teleportTo(level, home.x, home.y, home.z, Set.<Relative>of(), yaw, pitch, true);
        home = null;
    }
}
