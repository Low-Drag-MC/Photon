package com.lowdragmc.photon.client.fx.timeline;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * The looping, tickable sound instance an {@link AudioClip} plays while it is the active clip. Its
 * lifecycle is driven by {@link  TimelinePlayer#applyAudio}: created (and queued) on clip enter,
 * {@link #update} each tick to reflect live volume/pitch edits and a moving target, and
 * {@link #requestStop() stopped} on clip exit/switch. When positional it follows a supplied world
 * position; otherwise it plays relative to the listener at full attenuation.
 */
@OnlyIn(Dist.CLIENT)
public class TimelineSoundInstance extends AbstractTickableSoundInstance {
    @Nullable
    private Supplier<Vector3f> positionSupplier;
    private boolean stopRequested = false;

    public TimelineSoundInstance(SoundEvent soundEvent, SoundSource source, float volume, float pitch,
                                 boolean attenuated, @Nullable Supplier<Vector3f> positionSupplier) {
        super(soundEvent, source, RandomSource.create());
        this.looping = true;
        this.delay = 0;
        this.volume = volume;
        this.pitch = pitch;
        if (attenuated && positionSupplier != null) {
            this.positionSupplier = positionSupplier;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.relative = false;
            applyPosition(positionSupplier.get());
        } else {
            this.positionSupplier = null;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = true; // non-positional: play at the listener
        }
    }

    /** Push live volume/pitch (and, when positional, an updated position source) onto the instance. */
    public void update(float volume, float pitch, @Nullable Supplier<Vector3f> positionSupplier) {
        this.volume = volume;
        this.pitch = pitch;
        if (this.positionSupplier != null && positionSupplier != null) {
            this.positionSupplier = positionSupplier;
        }
    }

    /**
     * Our volume is an envelope driven from the outside, so starting at zero is normal — without this
     * the engine refuses to start the sound at all ({@code SoundEngine.play}: "volume was zero"), and
     * since the instance is only created on clip enter it would never get a second chance, so a clip
     * whose volume curve begins at 0 would be silent for its whole span. Only the initial start is
     * gated; the per-tick loop happily rides a volume of 0 and back up again.
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    /** Ask the instance to stop on its next {@link #tick}; the sound engine then drops it. */
    public void requestStop() {
        this.stopRequested = true;
    }

    @Override
    public void tick() {
        if (stopRequested) {
            stop();
            return;
        }
        if (positionSupplier != null) {
            applyPosition(positionSupplier.get());
        }
    }

    private void applyPosition(@Nullable Vector3f pos) {
        if (pos != null) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
        }
    }
}
