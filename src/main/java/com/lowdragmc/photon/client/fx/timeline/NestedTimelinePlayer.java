package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.FXObject;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Plays an FX instance's source timeline on the instance's own clock (see {@link FXInstanceObject}).
 * The source's root is the instance: what the source's tracks do to their root goes to the instance's inner
 * layer, so it stacks with what the timelines outside do to the instance. An object or channel a timeline
 * further out drives is left to it, so the outer timeline always wins.
 */
public class NestedTimelinePlayer extends TimelinePlayer {
    private final FXInstanceObject owner;

    public NestedTimelinePlayer(FXRuntime runtime, Timeline timeline, FXInstanceObject owner) {
        super(runtime, timeline);
        this.owner = owner;
    }

    @Override
    protected Set<UUID> controlledObjectIds(List<Track> leaves) {
        var ids = super.controlledObjectIds(leaves);
        ids.removeIf(id -> !owns(id) && drivenAbove(player -> player.gates(id)));
        return ids;
    }

    @Override
    protected void gate(FXObject object, boolean tick, boolean render) {
        if (object == owner) {
            owner.setInnerGate(tick, render);
        } else {
            super.gate(object, tick, render);
        }
    }

    @Override
    protected void release(FXObject object) {
        // an object a timeline further out has taken over keeps what that timeline says
        if (object == owner || !drivenAbove(player -> player.gates(object.id()))) {
            gate(object, true, true);
        }
    }

    @Override
    protected void speed(FXObject object, float speed) {
        if (object == owner) {
            owner.setInnerTimeScale(speed);
        } else {
            super.speed(object, speed);
        }
    }

    @Override
    protected boolean drivesSpeedOf(UUID id) {
        return owns(id) || !drivenAbove(player -> player.speeds(id));
    }

    @Override
    protected boolean drivesProperty(UUID id, String key) {
        // the instance's own transform is where it is placed, which belongs to the timelines outside
        return !owns(id) && !drivenAbove(player -> player.animates(id, key));
    }

    @Override
    protected void restart(FXObject object, Clip clip) {
        if (object != owner) {
            super.restart(object, clip);
            return;
        }
        // restarting the source's root restarts what the instance placed, not the instance's own clock
        var seed = effect != null && clip.randomSeed() ? effect.getRandomSource().nextLong() : clip.seed();
        for (var child : owner.transform().children()) {
            if (child.sceneObject() instanceof FXObject childObject) {
                resetSubtree(childObject, seed);
            }
        }
    }

    @Override
    protected boolean signalDispatch() {
        return runtime.timelinePlayer.signalDispatch();
    }

    @Override
    protected boolean audioDispatch() {
        return runtime.timelinePlayer.audioDispatch();
    }

    @Override
    protected boolean editorPreview() {
        return runtime.timelinePlayer.editorPreview();
    }

    @Override
    protected boolean recording() {
        return runtime.timelinePlayer.recording();
    }

    private boolean owns(UUID id) {
        return owner.id().equals(id);
    }

    private boolean drivenAbove(Predicate<TimelinePlayer> test) {
        for (var parent = owner.transform().parent(); parent != null; parent = parent.parent()) {
            if (parent.sceneObject() instanceof FXInstanceObject instance) {
                var player = instance.nestedPlayer();
                if (player != null && test.test(player)) return true;
            }
        }
        return test.test(runtime.timelinePlayer);
    }
}
