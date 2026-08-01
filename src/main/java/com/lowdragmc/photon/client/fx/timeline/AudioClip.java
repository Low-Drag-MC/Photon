package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A {@link Clip} on an {@link AudioTrack}: plays a registered {@code SoundEvent} for the clip's span.
 * The sound is stored by its {@link Identifier} (registry id) so it serializes cleanly, plus
 * volume / pitch / category / attenuation. It is intentionally a client-side subclass (unlike the
 * {@code net.minecraft}-free base {@link Clip}) so it can reference the sound registry types directly.
 * <p>
 * The sound is <b>always looping</b>; {@link #duration()} is the clip's span on the timeline and so
 * decides when the timeline stops it (a clip shorter than the sound cuts it early, a longer one loops
 * into the next round). The repeat sub-divisions the editor draws inside the clip come from the
 * sound's own length, not from the clip.
 * <p>
 * Volume and pitch are {@link NumberFunction}s sampled at the normalized clip progress, like
 * {@link PostProcessClip}'s weight — a curve over the clip IS the fade / sweep envelope. The sound
 * engine re-reads both from a tickable instance every tick, so they take effect live.
 */
public class AudioClip extends Clip {
    /** Default sound of a freshly created clip (a short, always-present vanilla sound). */
    public static final Identifier DEFAULT_SOUND = SoundEvents.UI_BUTTON_CLICK.value().location();

    /** The SoundEvent registry id to play; never null (defaults to {@link #DEFAULT_SOUND}). */
    private Identifier sound = DEFAULT_SOUND;
    /** Volume over the clip, sampled at normalized clip progress. */
    private NumberFunction volume = NumberFunction.constant(1);
    /** Pitch over the clip, sampled like {@link #volume}. */
    private NumberFunction pitch = NumberFunction.constant(1);
    private SoundSource category = SoundSource.MASTER;
    /** When true (and the track has a bound target) the sound plays positional/attenuated at the target. */
    private boolean attenuation = false;

    public AudioClip() {
        super();
    }

    public AudioClip(double start, double duration, float speed) {
        super(start, duration, speed);
    }

    public Identifier sound() {
        return sound;
    }

    public AudioClip sound(Identifier sound) {
        this.sound = sound;
        return this;
    }

    public NumberFunction volume() {
        return volume;
    }

    public AudioClip volume(NumberFunction volume) {
        this.volume = volume == null ? NumberFunction.constant(1) : volume;
        return this;
    }

    public NumberFunction pitch() {
        return pitch;
    }

    public AudioClip pitch(NumberFunction pitch) {
        this.pitch = pitch == null ? NumberFunction.constant(1) : pitch;
        return this;
    }

    /** Volume at clip-local time; never negative (the sound engine treats <0 as garbage). */
    public float volumeAt(double localTime) {
        return Math.max(0f, volume.get(progress(localTime), this::lerpValue).floatValue());
    }

    /** Pitch at clip-local time, clamped to the range the sound engine accepts. */
    public float pitchAt(double localTime) {
        return Math.clamp(pitch.get(progress(localTime), this::lerpValue).floatValue(), 0.5f, 2f);
    }

    public SoundSource category() {
        return category;
    }

    public AudioClip category(SoundSource category) {
        this.category = category;
        return this;
    }

    public boolean attenuation() {
        return attenuation;
    }

    public AudioClip attenuation(boolean attenuation) {
        this.attenuation = attenuation;
        return this;
    }

    @Override
    public AudioClip copy() {
        var clip = new AudioClip(start(), duration(), speed());
        clip.targetId(targetId()).seed(seed()).randomSeed(randomSeed());
        clip.sound = sound;
        clip.volume = volume.copy();
        clip.pitch = pitch.copy();
        clip.category = category;
        clip.attenuation = attenuation;
        return clip;
    }
}
