package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.FXObject;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Master-clock driver for a {@link Timeline}. Advanced once per tick from the always-on root object
 * (see {@code FXRuntime}), so it runs identically in the editor (deterministically replayed by
 * {@code SceneView.simulateTo}) and in-world.
 * <p>
 * It does not emit or remove objects — every object is emitted once by {@code FXRuntime.emmit}. Each
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
    @Nullable
    private IEffectExecutor effect;
    private long localTime = 0;
    /** Time of the most recent {@link #evaluate}, so {@link #frame} can interpolate within the tick. */
    private double lastEvalTime = 0;

    public TimelinePlayer(FXRuntime runtime, Timeline timeline) {
        this.runtime = runtime;
        this.timeline = timeline;
    }

    public boolean isEmpty() {
        return timeline.isEmpty();
    }

    /** Called from {@code FXRuntime.emmit} after all objects are emitted; resets the clock. */
    public void begin(IEffectExecutor effect) {
        this.effect = effect;
        this.localTime = 0;
        this.lastControlClip.clear();
        this.lastControlled.clear();
        // apply the t=0 state before any object ticks (avoids a one-tick artifact)
        evaluate(0);
    }

    /** Advance the master clock by one tick. */
    public void tick() {
        evaluate(localTime);
        localTime++;
    }

    /**
     * Per-frame pass (driven from the always-on root's frame update): re-applies the animation tracks at
     * the current fractional master time ({@code lastEvalTime + partialTicks}) so transform animation is
     * smooth between ticks instead of stepping 20×/s. {@code partialTicks} is 0 while paused (the editor
     * gates it), so the pose stays put when stopped.
     */
    public void frame(float partialTicks) {
        applyAnimations(lastEvalTime + partialTicks);
    }

    private void evaluate(long time) {
        lastEvalTime = time;
        var controlled = controlledObjectIds();
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
            for (var track : timeline.tracks()) {
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
    }

    /**
     * Drive animation-track targets' local transforms. Runs after the active/control resolution (so a
     * control restart's reset pose is overwritten by the animation for the same tick). Animation does
     * not gate active/visible, so it is a separate pass from {@link #controlledObjectIds()}.
     */
    private void applyAnimations(double time) {
        for (var track : timeline.tracks()) {
            if (track.mute() || !(track instanceof AnimationTrack animation)) {
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
    private Set<UUID> controlledObjectIds() {
        var ids = new HashSet<UUID>();
        for (var track : timeline.tracks()) {
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
