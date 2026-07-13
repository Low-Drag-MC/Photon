package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.FXObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Master-clock driver for a {@link Timeline}. Advanced once per tick from the always-on root object
 * (see {@code FXRuntime}), so it runs identically in the editor (deterministically replayed by
 * {@code SceneView.simulateTo}) and in-world.
 * <p>
 * It does not emit or remove objects — every object is emitted once by {@code FXRuntime.emit}. Each
 * tick the player resolves, per controlled object, two flags via {@link TimelineState}:
 * <ul>
 *   <li>{@code selfActive} (ticking) and {@code selfTimelineVisible} (rendering), set on the object;
 *       both are hierarchical so descendants inherit them.</li>
 *   <li>control-track clips additionally {@code reset()} the object (age 0, fresh) and reseed it at
 *       each clip start.</li>
 * </ul>
 * Objects not referenced by any track are left untouched (default active + visible).
 */
public class TimelinePlayer {
    private final FXRuntime runtime;
    private final Timeline timeline;
    /** Per-object previously-active control clip, used to detect restart (enter/switch) transitions. */
    private final Map<UUID, Clip> lastControlClip = new HashMap<>();
    /** Objects controlled on the previous evaluation, to revert ones that stop being controlled. */
    private final Set<UUID> lastControlled = new HashSet<>();
    /** Objects speed-controlled on the previous evaluation, to revert ones that stop (back to speed 1). */
    private final Set<UUID> lastSpeedControlled = new HashSet<>();
    @Nullable
    private IEffectExecutor effect;
    private long localTime = 0;
    /** Content end of the current playback, snapshotted from {@link Timeline#getDuration()} in
     *  {@link #begin} (in-world data never changes mid-play; the editor re-begins on every replay). */
    private double duration = 0;
    /** Set by {@link #stop()} (FX destroyed): the timeline is finished regardless of the clock. */
    private boolean stopped = false;
    /** Time of the most recent {@link #evaluate}, so {@link #frame} can interpolate within the tick. */
    private double lastEvalTime = 0;
    /** When recording (editor only), the per-frame re-apply is frozen so gizmo/inspector edits to the
     *  target persist between ticks instead of being stomped by the sampled pose. */
    private boolean recording = false;
    /** Whether signal tracks fire. Default true (in-world); the editor gates it to live playback only. */
    private boolean dispatchSignals = true;
    /** Highest tick already dispatched signals for, so a forward window never re-fires (reset on begin). */
    private double lastSignalTick = -1;
    /** Per-audio-track currently-playing sound instance and the clip that spawned it. */
    private final Map<AudioTrack, TimelineSoundInstance> audioInstances = new HashMap<>();
    private final Map<AudioTrack, AudioClip> audioClips = new HashMap<>();
    /** Whether audio tracks play. Default true (in-world); the editor gates it to live playback only. */
    private boolean audioEnabled = true;
    /** Editor preview: force non-positional audio so 3D/attenuated clips are still audible in the editor
     *  (the preview listener isn't at the clip's world position). Stays false in-world for real 3D. */
    private boolean editorPreview = false;

    public TimelinePlayer(FXRuntime runtime, Timeline timeline) {
        this.runtime = runtime;
        this.timeline = timeline;
    }

    public boolean isEmpty() {
        return timeline.isEmpty();
    }

    /** Called from {@code FXRuntime.emit} after all objects are emitted; resets the clock. */
    public void begin(IEffectExecutor effect) {
        this.effect = effect;
        this.localTime = 0;
        this.duration = timeline.getDuration();
        this.stopped = false;
        this.lastSignalTick = -1;
        this.lastControlClip.clear();
        this.lastControlled.clear();
        this.lastSpeedControlled.clear();
        stopAllAudio();
        // apply the t=0 state before any object ticks (avoids a one-tick artifact)
        evaluate(0);
    }

    /** Advance the master clock by one tick. */
    public void tick() {
        evaluate(localTime);
        localTime++;
    }

    /**
     * Whether this playback has no future content: the timeline is empty, {@link #stop()} was called,
     * or the master clock passed the content end. Boundary: {@link #tick()} evaluates at {@code localTime}
     * <i>then</i> increments, so content exactly at the duration (e.g. a signal at t=D, fired in the
     * window {@code (last, D]}) is evaluated before this turns true.
     * <p>
     * Objects keep-alive on this (see {@code FXRuntime}): while unfinished, timeline objects must stay
     * in the particle engine for future clips to reactivate/restart them.
     */
    public boolean isFinished() {
        return isEmpty() || stopped || localTime > duration;
    }

    /** The content end (ticks) snapshotted for the current playback; see {@link Timeline#getDuration()}. */
    public double getDuration() {
        return duration;
    }

    /** Finish this playback for good (FX destroyed): silences audio and makes {@link #isFinished()} true. */
    public void stop() {
        stopped = true;
        stopAllAudio();
    }

    /**
     * Per-frame pass (driven from the always-on root's frame update): re-applies the animation tracks at
     * the current fractional master time ({@code lastEvalTime + partialTicks}) so transform animation is
     * smooth between ticks instead of stepping 20×/s. {@code partialTicks} is 0 while paused (the editor
     * gates it), so the pose stays put when stopped.
     */
    public void frame(float partialTicks) {
        if (recording) return; // keep the user's live edits to the recording target
        applyAnimations(lastEvalTime + partialTicks);
    }

    /** Editor-only: freeze the per-frame re-apply so record mode can capture live target edits. */
    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    /** Editor gate: only dispatch signals during live forward playback (not scrub/preview replays). */
    public void setSignalDispatch(boolean dispatchSignals) {
        this.dispatchSignals = dispatchSignals;
    }

    /** Editor gate: only play audio during live forward playback. Turning it off stops all sounds now. */
    public void setAudioDispatch(boolean audioEnabled) {
        if (this.audioEnabled == audioEnabled) return;
        this.audioEnabled = audioEnabled;
        if (!audioEnabled) stopAllAudio();
    }

    /** Editor-only: force non-positional playback so 3D clips are still audible in the preview. */
    public void setEditorPreview(boolean editorPreview) {
        this.editorPreview = editorPreview;
    }

    private void evaluate(long time) {
        lastEvalTime = time;
        var leaves = timeline.leafTracks(false);
        var controlled = controlledObjectIds(leaves);
        // revert objects that are no longer controlled (e.g. their track/clip was deleted)
        for (var id : lastControlled) {
            if (!controlled.contains(id) && runtime.objects.get(id) instanceof FXObject object) {
                object.setSelfActive(true);
                object.setSelfTimelineVisible(true);
                lastControlClip.remove(id);
            }
        }
        lastControlled.clear();
        lastControlled.addAll(controlled);

        for (var id : controlled) {
            if (!(runtime.objects.get(id) instanceof FXObject object)) {
                continue;
            }
            boolean hasActivator = false;
            boolean activatorActive = false;
            boolean hasControl = false;
            Clip controlClip = null;
            for (var track : leaves) {
                if (track.mute()) {
                    continue;
                }
                if (track instanceof ActivatorTrack activator) {
                    if (id.equals(activator.targetId())) {
                        hasActivator = true;
                        if (activator.clipAt(time) != null) {
                            activatorActive = true;
                        }
                    }
                } else if (track instanceof ControlTrack control) {
                    for (var clip : control.clips()) {
                        if (id.equals(clip.targetId())) {
                            hasControl = true;
                            break;
                        }
                    }
                    if (controlClip == null) {
                        controlClip = control.clipForObjectAt(id, time);
                    }
                }
            }

            // control restart: reset + reseed when entering (or switching to) a control clip
            var previous = lastControlClip.get(id);
            if (controlClip != previous) {
                if (controlClip != null) {
                    restart(object, controlClip);
                }
                lastControlClip.put(id, controlClip);
            }

            var state = TimelineState.resolve(hasActivator, activatorActive, hasControl, controlClip != null);
            object.setSelfActive(state.tick());
            object.setSelfTimelineVisible(state.render());
        }

        applyAnimations(time);
        applySpeed(leaves, time);
        dispatchSignals(leaves, time);
        applyAudio(leaves, time);
    }

    /**
     * Drive audio-track clips: start a looping {@link TimelineSoundInstance} on clip enter/switch, stop
     * it on exit, and push live volume/pitch (and a moving target's position) while a clip stays active.
     * Gated to live playback by {@link #setAudioDispatch}; a clip shorter than its sound is cut off at
     * the clip end, a longer clip loops (the sound engine handles the looping).
     */
    private void applyAudio(List<Track> leaves, double time) {
        if (!audioEnabled) {
            stopAllAudio();
            return;
        }
        var soundManager = Minecraft.getInstance().getSoundManager();
        var seen = new HashSet<AudioTrack>();
        for (var track : leaves) {
            if (track.mute() || !(track instanceof AudioTrack audioTrack)) {
                continue;
            }
            seen.add(audioTrack);
            var clip = audioTrack.clipAt(time) instanceof AudioClip ac ? ac : null;
            var previous = audioClips.get(audioTrack);
            var instance = audioInstances.get(audioTrack);
            if (clip != previous) {
                if (instance != null) {
                    instance.requestStop();
                    audioInstances.remove(audioTrack);
                }
                audioClips.put(audioTrack, clip);
                if (clip != null && clip.sound() != null) {
                    var soundEvent = BuiltInRegistries.SOUND_EVENT.get(clip.sound());
                    if (soundEvent != null) {
                        var supplier = attenuatedSupplier(audioTrack, clip);
                        var newInstance = new TimelineSoundInstance(soundEvent, clip.category(), clip.volume(),
                                clip.pitch(), supplier != null, supplier);
                        soundManager.queueTickingSound(newInstance);
                        audioInstances.put(audioTrack, newInstance);
                    }
                }
            } else if (instance != null && clip != null) {
                instance.update(clip.volume(), clip.pitch(), attenuatedSupplier(audioTrack, clip));
            }
        }
        // stop instances whose track was removed / muted since last evaluation
        if (audioInstances.size() > seen.size()) {
            for (var track : new ArrayList<>(audioInstances.keySet())) {
                if (!seen.contains(track)) {
                    var instance = audioInstances.remove(track);
                    if (instance != null) instance.requestStop();
                    audioClips.remove(track);
                }
            }
        }
    }

    /**
     * A world-position source for positional (attenuated) playback, or null when the clip isn't
     * attenuated / has no bound target, or when in editor preview (forced non-positional so it stays
     * audible). A null supplier makes the sound play non-positional at the listener.
     */
    @Nullable
    private Supplier<Vector3f> attenuatedSupplier(AudioTrack track, AudioClip clip) {
        if (editorPreview || !clip.attenuation() || track.targetId() == null) {
            return null;
        }
        var id = track.targetId();
        return () -> {
            var object = runtime.objects.get(id);
            return object == null ? null : object.transform().position();
        };
    }

    /** Stop and forget every playing audio instance (on reset / pause / dispose). */
    public void stopAllAudio() {
        for (var instance : audioInstances.values()) {
            instance.requestStop();
        }
        audioInstances.clear();
        audioClips.clear();
    }

    /**
     * Drive speed-track targets' {@code selfTimeScale} (children inherit via hierarchical
     * {@code timeScale()}). Runs before the objects' own {@code tick()} consume the scale; objects that
     * stop being speed-controlled (track removed/muted/rebound) revert to 1.
     */
    private void applySpeed(java.util.List<Track> leaves, double time) {
        var controlled = new HashSet<UUID>();
        for (var track : leaves) {
            if (track.mute() || !(track instanceof SpeedTrack speed)) continue;
            var id = speed.targetId();
            if (id != null && runtime.objects.get(id) instanceof FXObject target) {
                target.setSelfTimeScale(speed.sampleSpeed(time));
                controlled.add(id);
            }
        }
        for (var id : lastSpeedControlled) {
            if (!controlled.contains(id) && runtime.objects.get(id) instanceof FXObject target) {
                target.setSelfTimeScale(1f);
            }
        }
        lastSpeedControlled.clear();
        lastSpeedControlled.addAll(controlled);
    }

    /**
     * Fire every signal whose tick falls in the forward window {@code (lastSignalTick, time]}. Gated to
     * live playback by {@link #setSignalDispatch}; the monotonic window + {@link #begin} reset prevent
     * any double-fire (the {@code evaluate(0)} repeat, or a replay seek).
     */
    private void dispatchSignals(java.util.List<Track> leaves, double time) {
        if (!dispatchSignals || time <= lastSignalTick) return;
        for (var track : leaves) {
            if (track.mute() || !(track instanceof SignalTrack signalTrack)) continue;
            var channel = signalTrack.displayName();
            for (var signal : signalTrack.signals()) {
                if (signal.time() > lastSignalTick && signal.time() <= time) {
                    if (effect != null) effect.onTimelineSignal(channel, signal.name(), signal.data(), signal.time());
                    PhotonSignals.fire(effect, channel, signal.name(), signal.data(), signal.time());
                }
            }
        }
        lastSignalTick = time;
    }

    /**
     * Drive animation-track targets' local transforms. Runs after the active/control resolution (so a
     * control restart's reset pose is overwritten by the animation for the same tick). Animation does
     * not gate active/visible, so it is a separate pass from {@link #controlledObjectIds(List)} ()}.
     */
    private void applyAnimations(double time) {
        for (var track : timeline.leafTracks(false)) {
            // SpeedTrack is an AnimationTrack subclass but drives timeScale (see applySpeed), not transform
            if (track.mute() || !(track instanceof AnimationTrack animation) || track instanceof SpeedTrack) {
                continue;
            }
            var id = animation.targetId();
            if (id != null && runtime.objects.get(id) instanceof FXObject target) {
                animation.sampleInto(target, time);
            }
        }
    }

    private void restart(FXObject object, Clip clip) {
        // restart the whole subtree so a control clip bound to a parent (e.g. an empty) also
        // restarts its children from age 0 (not just the bound object itself).
        var seed = effect != null && clip.randomSeed() ? effect.getRandomSource().nextLong() : clip.seed();
        resetSubtree(object, seed);
    }

    private void resetSubtree(FXObject object, long seed) {
        object.reset();
        if (effect != null) {
            object.setRandomSeed(seed);
        }
        for (var child : object.transform().children()) {
            if (child.sceneObject() instanceof FXObject childObject) {
                resetSubtree(childObject, seed);
            }
        }
    }

    /** Union of all objects referenced by activator track targets and control clip targets. */
    private Set<UUID> controlledObjectIds(List<Track> leaves) {
        var ids = new HashSet<UUID>();
        for (var track : leaves) {
            if (track.mute()) {
                continue;
            }
            if (track instanceof ActivatorTrack activator) {
                if (activator.targetId() != null) {
                    ids.add(activator.targetId());
                }
            } else if (track instanceof ControlTrack control) {
                for (var clip : control.clips()) {
                    if (clip.targetId() != null) {
                        ids.add(clip.targetId());
                    }
                }
            }
        }
        return ids;
    }
}
