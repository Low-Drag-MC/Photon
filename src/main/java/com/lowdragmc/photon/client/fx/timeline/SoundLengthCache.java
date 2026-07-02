package com.lowdragmc.photon.client.fx.timeline;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort cache of a {@code SoundEvent}'s length in ticks, decoded off-thread from its OGG so an
 * {@link AudioClip} can draw one loop sub-division per repeat. The first lookup for an id kicks off an
 * async decode (via Minecraft's own {@link JOrbisAudioStream}) and returns 0 until it resolves; a
 * sound that can't be measured (missing / event-reference / decode error) caches 0 and is not retried.
 */
@OnlyIn(Dist.CLIENT)
public class SoundLengthCache {
    private static final Map<ResourceLocation, Double> LENGTHS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> PENDING = ConcurrentHashMap.newKeySet();

    /** Length of {@code soundId} in ticks, or 0 when unknown / not yet decoded (triggers a load). */
    public static double getTicks(@Nullable ResourceLocation soundId) {
        if (soundId == null) return 0;
        var cached = LENGTHS.get(soundId);
        if (cached != null) return cached;
        requestLoad(soundId);
        return 0;
    }

    private static void requestLoad(ResourceLocation soundId) {
        if (!PENDING.add(soundId)) return; // already loading
        CompletableFuture
                .supplyAsync(() -> computeTicks(soundId), Util.nonCriticalIoPool())
                .whenComplete((ticks, error) -> {
                    LENGTHS.put(soundId, error != null || ticks == null ? 0.0 : ticks);
                    PENDING.remove(soundId);
                });
    }

    private static double computeTicks(ResourceLocation soundId) {
        try {
            var mc = Minecraft.getInstance();
            var weighed = mc.getSoundManager().getSoundEvent(soundId);
            if (weighed == null) return 0;
            var sound = weighed.getSound(RandomSource.create());
            if (sound == null || sound.getType() != Sound.Type.FILE) return 0;
            try (var input = mc.getResourceManager().open(sound.getPath());
                 var stream = new JOrbisAudioStream(input)) {
                var format = stream.getFormat();
                var buffer = stream.readAll();
                var frameSize = format.getFrameSize();
                var frameRate = format.getFrameRate();
                if (frameSize <= 0 || frameRate <= 0) return 0;
                var frames = buffer.remaining() / frameSize;
                return frames / frameRate * 20.0; // seconds -> ticks
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
