package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * Audio track. Like {@link SignalTrack} it needs no target to function; its lane holds {@link AudioClip}s
 * that each play a registered {@code SoundEvent} for their span (see {@link TimelinePlayer#applyAudio}).
 * The base {@link Track#targetId} is optional here — when bound it provides a world position for
 * 3D/attenuated playback; otherwise the sound plays non-positional (at the listener).
 */
@OnlyIn(Dist.CLIENT)
public class AudioTrack extends Track {

    public AudioTrack() {
    }

    @Override
    protected Clip createClip(double start, double duration, float speed) {
        return new AudioClip(start, duration, speed);
    }

    @Override
    protected void writeClipExtra(Clip clip, CompoundTag clipTag, HolderLookup.Provider provider) {
        if (clip instanceof AudioClip audio) {
            clipTag.putString("sound", audio.sound().toString());
            clipTag.put("volume", audio.volume().serializeWrapper());
            clipTag.put("pitch", audio.pitch().serializeWrapper());
            clipTag.putString("category", audio.category().name());
            clipTag.putBoolean("attenuation", audio.attenuation());
        }
    }

    @Override
    protected void readClipExtra(Clip clip, CompoundTag clipTag, HolderLookup.Provider provider) {
        if (clip instanceof AudioClip audio) {
            if (clipTag.contains("sound")) {
                var loc = ResourceLocation.tryParse(clipTag.getString("sound"));
                audio.sound(loc != null ? loc : AudioClip.DEFAULT_SOUND);
            }
            audio.volume(readEnvelope(clipTag, "volume"));
            audio.pitch(readEnvelope(clipTag, "pitch"));
            if (clipTag.contains("category")) audio.category(parseCategory(clipTag.getString("category")));
            audio.attenuation(clipTag.getBoolean("attenuation"));
        }
    }

    /** Volume/pitch under the same key in two shapes: pre-curve clips stored a bare float, which
     *  reads back as the constant envelope it always was. */
    private static NumberFunction readEnvelope(CompoundTag clipTag, String key) {
        if (clipTag.contains(key, Tag.TAG_COMPOUND)) {
            return NumberFunction.deserializeWrapper(clipTag.getCompound(key));
        }
        if (clipTag.contains(key)) {
            return NumberFunction.constant(clipTag.getFloat(key));
        }
        return NumberFunction.constant(1);
    }

    private static SoundSource parseCategory(String name) {
        try {
            return SoundSource.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SoundSource.MASTER;
        }
    }
}
