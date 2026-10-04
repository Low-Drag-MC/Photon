package com.lowdragmc.photon.uitest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;

/**
 * Where the light scenarios build the demo scene, and how they put the world back. ⚠️ The scene wipes a
 * box, sets a clear midnight and stops the daylight cycle: left at the player's feet, every later scenario
 * in the run renders a different world (soft_particle failed on exactly that).
 */
final class LightSite {
    private static final int OFFSET = 64;
    private static Vec3 home;
    private static float yaw, pitch;
    private static boolean flying;
    private static long dayTime;
    private static boolean daylight;
    private static int clearTime, rainTime;
    private static boolean raining, thundering;

    private LightSite() {
    }

    /** Server thread. Remembers the player, the clock, the weather and the daylight rule; returns the scene origin. */
    static BlockPos claim(ServerPlayer player) {
        if (home == null) {
            home = player.position();
            yaw = player.getYRot();
            pitch = player.getXRot();
            flying = player.getAbilities().flying;
            var level = player.serverLevel();
            dayTime = level.getDayTime();
            daylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
            var data = (ServerLevelData) level.getLevelData();
            clearTime = data.getClearWeatherTime();
            rainTime = data.getRainTime();
            raining = data.isRaining();
            thundering = data.isThundering();
        }
        return BlockPos.containing(home).below().offset(OFFSET, 0, 0);
    }

    /** Server thread. */
    static void release(ServerPlayer player) {
        if (home == null) return;
        var level = player.serverLevel();
        level.setDayTime(dayTime);
        level.setWeatherParameters(clearTime, rainTime, raining, thundering);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight, level.getServer());
        player.getAbilities().flying = flying;
        player.onUpdateAbilities();
        player.teleportTo(level, home.x, home.y, home.z, yaw, pitch);
        home = null;
    }
}
