package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataRuntime;
import com.lowdragmc.photon.client.gameobject.emitter.data.InstancedRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import org.jetbrains.annotations.Nullable;

/**
 * Per-{@link AraTrailEmitter}-instance runtime layer. Mirrors {@code ParticleRuntime}: it holds the named
 * {@link RuntimeValue} slots written by the timeline over the immutable {@link AraTrailConfig}, plus the
 * {@link AraPhysicsSetting.Runtime} layer for the physics sub-setting. A slot with no timeline value falls
 * back to the authored config. All reads are direct field access — no map, no reflection.
 */
public class AraTrailRuntime {
    public final AraTrailConfig config;
    public final AraPhysicsSetting.Runtime physics;

    public final RuntimeValue<Integer> duration;
    public final RuntimeValue<Boolean> looping;
    public final RuntimeValue<Float> thickness;
    public final RuntimeValue<NumberFunction> thicknessOverLength;
    public final RuntimeValue<NumberFunction> colorOverLength;
    public final RuntimeValue<NumberFunction> thicknessOverTime;
    public final RuntimeValue<NumberFunction> thicknessOverSegmentTime;
    public final RuntimeValue<NumberFunction> colorOverTime;
    public final RuntimeValue<NumberFunction> colorOverSegmentTime;
    public final RuntimeValue<Float> initialThickness;
    public final RuntimeValue<Float> time;
    public final RuntimeValue<Float> minDistance;
    public final RuntimeValue<Float> timeInterval;
    /** Per-instance override of the additional-GPU custom-data VALUES (structure stays from config). */
    public final CustomDataRuntime customData;

    /** Co-located per-instance render-override slots (material / renderer settings), batching-safe. */
    public final InstancedRendererSetting.Runtime renderer;
    /** The per-emitter override render pass (lazy; wraps {@link #renderer}), or null while no slot is set. */
    @Nullable private PhotonFXRenderPass overridePass;

    public AraTrailRuntime(AraTrailConfig config) {
        this.config = config;
        this.duration = new RuntimeValue<>(() -> config.duration);
        this.looping = new RuntimeValue<>(() -> config.looping);
        this.thickness = new RuntimeValue<>(() -> config.thickness);
        this.thicknessOverLength = new RuntimeValue<>(() -> config.thicknessOverLength);
        this.colorOverLength = new RuntimeValue<>(() -> config.colorOverLength);
        this.thicknessOverTime = new RuntimeValue<>(() -> config.thicknessOverTime);
        this.thicknessOverSegmentTime = new RuntimeValue<>(() -> config.thicknessOverSegmentTime);
        this.colorOverTime = new RuntimeValue<>(() -> config.colorOverTime);
        this.colorOverSegmentTime = new RuntimeValue<>(() -> config.colorOverSegmentTime);
        this.initialThickness = new RuntimeValue<>(() -> config.initialThickness);
        this.time = new RuntimeValue<>(() -> config.time);
        this.minDistance = new RuntimeValue<>(() -> config.minDistance);
        this.timeInterval = new RuntimeValue<>(() -> config.timeInterval);
        this.physics = config.physicsSetting.createRuntime();
        this.customData = new CustomDataRuntime(config.additionalGPUDataSetting);
        this.renderer = config.renderer.createRuntime();
    }

    /**
     * The render pass this emitter draws through: its per-instance override pass when any render slot is
     * overridden (lazily built to wrap {@link #renderer}), else the shared {@code config.particleRenderType}.
     */
    public PhotonFXRenderPass effectiveRenderPass() {
        if (renderer.hasOverride()) {
            if (overridePass == null) {
                overridePass = config.createRenderPass(renderer);
            }
            return overridePass;
        }
        if (overridePass != null) {
            overridePass.clearInstance();
            overridePass = null;
        }
        return config.particleRenderType;
    }

    /** Whether this emitter currently has any render-override slot set. */
    public boolean hasRenderOverride() {
        return renderer.hasOverride();
    }

    /** Drop every render-override slot (revert to the shared pass) and free the override pass's GL. */
    public void clearRenderOverride() {
        renderer.clear();
        if (overridePass != null) {
            overridePass.clearInstance();
            overridePass = null;
        }
    }

    /** Clear every timeline override (fall back to authored config). Called on emitter reset. */
    public void clear() {
        duration.clear();
        looping.clear();
        thickness.clear();
        thicknessOverLength.clear();
        colorOverLength.clear();
        thicknessOverTime.clear();
        thicknessOverSegmentTime.clear();
        colorOverTime.clear();
        colorOverSegmentTime.clear();
        initialThickness.clear();
        time.clear();
        minDistance.clear();
        timeInterval.clear();
        physics.clear();
        customData.clear();
        clearRenderOverride();
    }
}
