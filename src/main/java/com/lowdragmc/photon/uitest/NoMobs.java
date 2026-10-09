package com.lowdragmc.photon.uitest;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;

import javax.annotation.Nullable;

/**
 * Keeps animals out of a scenario's pictures: the test world spawns them, and one wandering through between two shots
 * reads as a change the effect made. Server thread.
 */
final class NoMobs {
    @Nullable
    private static Boolean spawning;

    private NoMobs() {
    }

    /** Stops mob spawning and removes the mobs around {@code player}; {@link #release} puts the rule back. */
    static void claim(ServerPlayer player) {
        var level = player.level();
        if (spawning == null) spawning = level.getGameRules().get(GameRules.SPAWN_MOBS);
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
        level.getEntities((Entity) null, player.getBoundingBox().inflate(160), entity -> entity instanceof Mob)
                .forEach(Entity::discard);
    }

    static void release(ServerPlayer player) {
        if (spawning == null) return;
        var level = player.level();
        level.getGameRules().set(GameRules.SPAWN_MOBS, spawning, level.getServer());
        spawning = null;
    }
}
