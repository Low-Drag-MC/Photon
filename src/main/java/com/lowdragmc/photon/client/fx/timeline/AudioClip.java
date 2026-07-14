package com.lowdragmc.photon.client.fx.timeline;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A {@link Clip} on an {@link AudioTrack}: plays a registered {@code SoundEvent} for the clip's span.
 * The sound is stored by its {@link ResourceLocation} (registry id) so it serializes cleanly, plus
 * volume / pitch / category / attenuation. It is intentionally a client-side subclass (unlike the
 * {@code net.minecraft}-free base {@link Clip}) so it can reference the sound registry types directly.
 * <p>
 * The sound is <b>always looping</b>; the clip length decides when the timeline stops it (a clip
 * shorter than the sound cuts it early, a longer one loops into the next round). {@link #duration()}
 * is a purely visual hint (ticks per loop) used to draw the repeat sub-divisions inside the clip.
 */
@OnlyIn(Dist.CLIENT)
public class AudioClip extends Clip {
    /** Default sound of a freshly created clip (a short, always-present vanilla sound). */
    public static final ResourceLocation DEFAULT_SOUND = SoundEvents.UI_BUTTON_CLICK.value().getLocation();

    /** The SoundEvent registry id to play; never null (defaults to {@link #DEFAULT_SOUND}). */
    private ResourceLocation sound = DEFAULT_SOUND;
    private float volume = 1f;
    private float pitch = 1f;
    private SoundSource category = SoundSource.MASTER;
    /** When true (and the track has a bound target) the sound plays positional/attenuated at the target. */
    private boolean attenuation = false;

    public AudioClip() {
        super();
    }

    public AudioClip(double start, double duration, float speed) {
        super(start, duration, speed);
    }

    public ResourceLocation sound() {
        return sound;
    }

    public AudioClip sound(ResourceLocation sound) {
        this.sound = sound;
        return this;
    }

    public float volume() {
        return volume;
    }

    public AudioClip volume(float volume) {
        this.volume = volume;
        return this;
    }

    public float pitch() {
        return pitch;
    }

    public AudioClip pitch(float pitch) {
        this.pitch = pitch;
        return this;
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
        clip.volume = volume;
        clip.pitch = pitch;
        clip.category = category;
        clip.attenuation = attenuation;
        return clip;
    }
}
