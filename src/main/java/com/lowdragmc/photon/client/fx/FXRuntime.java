package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.DummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.TimelinePlayer;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A live, playable instance of an {@link FX} definition.
 * <p>
 * Created via {@link FX#createRuntime()}, it owns the object tree (rooted at an always-present
 * {@link EmptyFXObject} with id {@link #ROOT_UUID}) and the {@link TimelinePlayer}. Playback starts
 * with {@link #emit(IEffectExecutor)}, which hands every object to the owning particle engine; the
 * engine then drives ticking/rendering, while the root object drives the timeline clock.
 */
@Getter
public class FXRuntime implements IScene {
    public final static UUID ROOT_UUID = new UUID(0, 0);
    public final FXData fxData;
    public final Map<UUID, IFXObject> objects = new LinkedHashMap<>();
    public final IFXObject root;
    public final TimelinePlayer timelinePlayer;
    /** Whether {@link #emit} ran (a runtime is never finished/valid before its first emit). */
    private boolean emitted = false;
    /** Whether {@link #destroy} ran (re-{@link #emit}ting revives the runtime, e.g. editor replay). */
    private boolean destroyed = false;
    /** The particle engine this runtime was emitted into (vanilla or an editor manager); see {@link #isValid()}. */
    @Nullable
    private ParticleTickHost host;
    /** The host's wipe generation at emit time; a later generation means our particles were discarded. */
    private int generationAtEmit;

    public FXRuntime(FXData fxData) {
        this.fxData = fxData;
        // created before any object is added, so addSceneObjectInternal is the ONE place that wires
        // per-object keep-alive — for construction and editor additions alike
        this.timelinePlayer = new TimelinePlayer(this, fxData.timeline());
        this.root = new EmptyFXObject();
        this.root.transform()._setInternalID(ROOT_UUID);
        addSceneObject(root);
        root.setName("root");
        initRuntime();
        // the always-on root drives the timeline once per tick (editor + in-world)
        if (root instanceof FXObject fxRoot) {
            fxRoot.setOnUpdateTick(() -> {
                if (!timelinePlayer.isEmpty()) {
                    timelinePlayer.tick();
                }
            });
            // and the per-frame animation pass for smooth (interpolated) transform animation
            fxRoot.setOnUpdateFrame(partialTicks -> {
                if (!timelinePlayer.isEmpty()) {
                    timelinePlayer.frame(partialTicks);
                }
            });
        }
    }

    private void initRuntime() {
        for (var fxObject : fxData.objects()) {
            addSceneObjectInternal(fxObject);
        }
        for (var fxObject : fxData.objects()) {
            fxObject.setScene(this);
        }
        for (var fxObject : fxData.objects()) {
            if (fxObject.transform().parent() == null) {
                fxObject.transform().parent(root.transform(), false);
            }
            fxObject.transform().rebuildChildOrder();
        }
    }

    @Nullable
    @Override
    public ISceneObject getSceneObject(UUID uuid) {
        // unknown ids return null (a stale parent ref is re-parented to root by initRuntime, and
        // Transform.awake tolerates null); silently substituting the root here masked lookup bugs
        return objects.get(uuid);
    }

    @Override
    public Collection<ISceneObject> getAllSceneObjects() {
        return objects.values().stream().map(ISceneObject.class::cast).toList();
    }

    @Override
    public void addSceneObjectInternal(ISceneObject sceneObject) {
        if (sceneObject instanceof IFXObject fxObject) {
            var previous = objects.put(fxObject.id(), fxObject);
            if (previous != null) {
                if (previous != fxObject) {
                    Photon.LOGGER.warn("Duplicate fx runtime object id %s is replaced".formatted(fxObject.id()));
                }
            }
            // while the timeline has future content, every object must stay in the particle engine:
            // the root to drive the clock, the rest so clips can reactivate/restart them (an empty
            // timeline is always finished, so the supplier is inert for legacy FX)
            if (fxObject instanceof FXObject object) {
                object.setKeepAlive(() -> !timelinePlayer.isFinished());
            }
        } else {
            throw new IllegalArgumentException("%s is not an instance of IFXObject".formatted(sceneObject));
        }
    }

    @Override
    public void removeSceneObjectInternal(ISceneObject sceneObject) {
        if (sceneObject instanceof IFXObject fxObject) {
            objects.remove(fxObject.id());
        } else {
            throw new IllegalArgumentException("%s is not an instance of IFXObject".formatted(sceneObject));
        }
    }

    /** Start playback: emit every object into the executor's particle engine and reset the timeline. */
    public void emit(IEffectExecutor effect) {
        emit(effect, 0);
    }

    /**
     * Start playback with a start delay (in ticks). Every object is emitted once up front; the
     * timeline only gates active/visibility and restarts objects via the {@link TimelinePlayer}.
     */
    public void emit(IEffectExecutor effect, int delay) {
        emitted = true;
        destroyed = false;
        // track the owning engine for isValid(): same resolution as IFXObject.emit's engine pick
        this.host = effect.getLevel() instanceof DummyWorld dummyWorld
                && dummyWorld.getParticleManager() instanceof ParticleTickHost tickHost
                ? tickHost : VanillaParticleHost.INSTANCE;
        this.generationAtEmit = host.generation();
        if (root instanceof FXObject fxRoot) {
            fxRoot.setTickHost(host);
            fxRoot.setLastHostTick(host.tickCount());
        }
        for (var fxObject : objects.values()) {
            fxObject.emit(effect);
            // set after emit: emit() resets the object, which zeroes its delay
            fxObject.setDelay(delay);
        }
        timelinePlayer.begin(effect);
    }

    /** @deprecated typo; use {@link #emit(IEffectExecutor)}. */
    @Deprecated
    public void emmit(IEffectExecutor effect) {
        emit(effect);
    }

    /** @deprecated typo; use {@link #emit(IEffectExecutor, int)}. */
    @Deprecated
    public void emmit(IEffectExecutor effect, int delay) {
        emit(effect, delay);
    }

    /**
     * The FX has nothing left to do: the timeline has no future content AND no object is still
     * playing. A looping emitter never finishes on its own (destroy it via its executor). A destroyed
     * runtime finishes once its remnants drain (immediately when destroyed with {@code force}).
     */
    public boolean isFinished() {
        return timelinePlayer.isFinished()
                && objects.values().stream().noneMatch(IFXObject::isPlaying);
    }

    /** Inverse of {@link #isFinished()} — "the FX is still running". Prefer isFinished() in new code. */
    public boolean isAlive() {
        return !isFinished();
    }

    /**
     * Whether this runtime's particles are still tracked by their particle engine. This is the safe
     * check for callers who CACHE a runtime: the vanilla {@code ParticleEngine} silently discards all
     * particles on level change, {@code /photon_client clear_particles}, or when another mod reaches
     * in — a cached runtime has no other way to notice.
     * <p>
     * False before {@link #emit}, after {@link #destroy}, once the FX {@link #isFinished() finished}
     * and drained, and after the engine discarded its particles. O(1) and cheap to call every tick:
     * the two known wipe paths invalidate immediately (wipe generation); anything else that stops the
     * engine from ticking the root is caught at most one engine tick late (heartbeat). A paused game
     * does not invalidate (the heartbeat clock only advances when particles actually tick).
     */
    public boolean isValid() {
        if (!emitted || destroyed) {
            return false;
        }
        if (host == null || !(root instanceof FXObject fxRoot)) {
            return true; // untrackable host: degrade to the state flags above
        }
        if (generationAtEmit != host.generation()) {
            return false;
        }
        // <=1: isValid may be queried mid-engine-tick, before the root has ticked this round
        return host.tickCount() - fxRoot.getLastHostTick() <= 1;
    }

    /**
     * Stop this runtime for good: removes every object ({@code force} drops visible remnants
     * immediately; otherwise they drain) and finishes the timeline so no future clip/signal/audio
     * plays while remnants drain. A later {@link #emit} revives the runtime (editor replay).
     */
    public void destroy(boolean force) {
        destroyed = true;
        timelinePlayer.stop();
        for (var fxObject : objects.values()) {
            fxObject.remove(force);
        }
    }

    @Nullable
    public IFXObject findObject(String name) {
        for (var fxObject : objects.values()) {
            if (fxObject.getName().equals(name)) {
                return fxObject;
            }
        }
        return null;
    }

    public List<IFXObject> findObjects(String name) {
        var list = new ArrayList<IFXObject>();
        for (var fxObject : objects.values()) {
            if (fxObject.getName().equals(name)) {
                list.add(fxObject);
            }
        }
        return list;
    }
}
