package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import java.util.Locale;

/**
 * Audio track. Like {@link SignalTrack} it needs no target to function; its lane holds {@link AudioClip}s
 * that each play a registered {@code SoundEvent} for their span (see {@link TimelinePlayer#applyAudio}).
 * The base {@link Track#targetId} is optional here — when bound it provides a world position for
 * 3D/attenuated playback; otherwise the sound plays non-positional (at the listener).
 */
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
                var loc = Identifier.tryParse(clipTag.getStringOr("sound", ""));
                audio.sound(loc != null ? loc : AudioClip.DEFAULT_SOUND);
            }
            audio.volume(readEnvelope(clipTag, "volume"));
            audio.pitch(readEnvelope(clipTag, "pitch"));
            if (clipTag.contains("category")) audio.category(parseCategory(clipTag.getStringOr("category", "")));
            audio.attenuation(clipTag.getBooleanOr("attenuation", false));
        }
    }

    /** Volume/pitch under the same key in two shapes: pre-curve clips stored a bare float, which
     *  reads back as the constant envelope it always was. */
    private static NumberFunction readEnvelope(CompoundTag clipTag, String key) {
        var envelope = clipTag.getCompound(key);
        if (envelope.isPresent()) {
            return NumberFunction.deserializeWrapper(envelope.get());
        }
        return clipTag.getFloat(key).map(NumberFunction::constant).orElseGet(() -> NumberFunction.constant(1));
    }

    private static SoundSource parseCategory(String name) {
        try {
            return SoundSource.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SoundSource.MASTER;
        }
    }
}
