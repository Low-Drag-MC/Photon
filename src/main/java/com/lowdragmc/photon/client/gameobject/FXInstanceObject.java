package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.fx.instance.InstanceBaker;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.timeline.NestedTimelinePlayer;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * An FX placed inside another, Unity's prefab instance: the objects of source {@link #getFx()} hang under it,
 * placed when the containing FX is read (see {@code InstanceBaker}). The source's timeline plays on this object's
 * clock, which starts from 0 the first time the instance is active after a reset, pauses while it is inactive and
 * runs at its speed.
 */
@ParametersAreNonnullByDefault
public class FXInstanceObject extends FXObject {

    @LDLRegisterClient(name = InstanceTags.INSTANCE_TYPE, registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new FXInstanceObject();
        }

        @Override
        public IGuiTexture icon() {
            return PhotonIcons.FX_FILE;
        }
    };

    @Getter
    @Setter
    @Nullable
    @Persisted
    private ResourceLocation fx;
    /** The record ({@code InstanceRecord}) in the saved form; read and written by the containing FX's data, empty once placed. */
    @Getter
    @Setter
    @Nullable
    @Persisted
    private CompoundTag overrides;
    /** The source's own timeline with its ids placed. */
    @Getter
    @Persisted
    private final Timeline timeline = new Timeline();
    /** What this instance was placed from, for saving it as a record; set when the containing FX is read. */
    @Getter
    @Setter
    @Nullable
    private InstanceBaker.Placement placement;

    @Nullable
    private NestedTimelinePlayer player;
    private boolean begun;
    private boolean innerActive = true;
    private boolean innerVisible = true;
    private float innerTimeScale = 1;

    public FXInstanceObject() {
        this.onUpdateTick = this::tickTimeline;
        this.onUpdateFrame = this::frameTimeline;
    }

    public FXInstanceObject(ResourceLocation fx) {
        this();
        this.fx = fx;
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

    @Override
    public IGuiTexture getIcon() {
        return PhotonIcons.FX_FILE;
    }

    @Nullable
    public NestedTimelinePlayer nestedPlayer() {
        if (!(getScene() instanceof FXRuntime runtime)) {
            return null;
        }
        if (player == null || player.runtime() != runtime) {
            if (player != null) player.stopAllAudio();
            player = new NestedTimelinePlayer(runtime, timeline, this);
        }
        return player;
    }

    public void beginTimeline(IEffectExecutor effect) {
        begun = true;
        var player = nestedPlayer();
        if (player != null && !player.isEmpty()) {
            player.begin(effect);
        }
    }

    public boolean isTimelineRunning() {
        return begun && player != null && !player.isEmpty() && !player.isFinished();
    }

    /** Running and not paused: only the timelines outside could resume a paused one, and they keep alive on their own. */
    public boolean isTimelinePlaying() {
        return isOuterActive() && isTimelineRunning();
    }

    private void tickTimeline() {
        var player = nestedPlayer();
        if (player == null || player.isEmpty()) {
            return;
        }
        if (!isOuterActive()) {
            // a paused clock evaluates nothing, so nothing else would stop what it was playing
            player.stopAllAudio();
            return;
        }
        if (!begun) {
            // normally started by the evaluation that activated it; this covers anything else that did
            if (effectExecutor == null) return;
            beginTimeline(effectExecutor);
        }
        player.tick(outerTimeScale());
    }

    private void frameTimeline(float partialTicks) {
        if (begun && player != null && !player.isEmpty() && isOuterActive()) {
            player.frame(partialTicks * outerTimeScale());
        }
    }

    /** Active as far as the timelines outside are concerned, i.e. whether this instance's clock runs. */
    public boolean isOuterActive() {
        if (!isEnabled() || !selfActive) return false;
        var parent = transform().parent();
        return parent == null || !(parent.sceneObject() instanceof IFXObject object) || object.isActive();
    }

    /** The speed the timelines outside give this instance, which its clock runs at. */
    public float outerTimeScale() {
        var self = Math.max(0, selfTimeScale);
        var parent = transform().parent();
        return parent != null && parent.sceneObject() instanceof IFXObject object ? self * object.timeScale() : self;
    }

    public void setInnerGate(boolean active, boolean visible) {
        this.innerActive = active;
        this.innerVisible = visible;
    }

    public void setInnerTimeScale(float timeScale) {
        this.innerTimeScale = timeScale;
    }

    @Override
    public boolean isSelfActive() {
        return selfActive && innerActive;
    }

    @Override
    public boolean isSelfTimelineVisible() {
        return selfTimelineVisible && innerVisible;
    }

    @Override
    public float getSelfTimeScale() {
        return selfTimeScale * innerTimeScale;
    }

    @Override
    public boolean isPlaying() {
        if (!isOuterActive()) {
            return false;
        }
        return isTimelineRunning() || super.isPlaying();
    }

    @Override
    public void reset() {
        super.reset();
        innerActive = true;
        innerVisible = true;
        innerTimeScale = 1;
        begun = false;
        stopAudio();
    }

    @Override
    public void remove(boolean force) {
        super.remove(force);
        if (player != null) {
            player.stop();
        }
    }

    public void stopAudio() {
        if (player != null) {
            player.stopAllAudio();
        }
    }

    public boolean beginIfActive(IEffectExecutor effect) {
        if (begun || !isOuterActive()) {
            return false;
        }
        beginTimeline(effect);
        return true;
    }

    /** Whether {@code object} is, or hangs in, an instance that is playing its timeline. */
    public static boolean keepsAlive(IFXObject object) {
        for (var at = object.transform(); at != null; at = at.parent()) {
            if (at.sceneObject() instanceof FXInstanceObject instance && instance.isTimelinePlaying()) {
                return true;
            }
        }
        return false;
    }
}
