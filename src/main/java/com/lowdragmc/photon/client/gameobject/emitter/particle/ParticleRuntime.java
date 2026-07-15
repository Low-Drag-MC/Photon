package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.photon.client.gameobject.emitter.data.ColorOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ColorBySpeedSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataRuntime;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ExternalForcesSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ForceOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.LifetimeByEmitterSpeedSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.NoiseSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RotationBySpeedSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SizeBySpeedSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhysicsSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RotationOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ShapeSetting;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.SizeOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.TrailsSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.VelocityOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import org.jetbrains.annotations.Nullable;

/**
 * Per-{@link ParticleEmitter}-instance runtime layer. It aggregates one runtime per animatable
 * sub-setting (each {@code Setting.Runtime} is co-located with its config setting and holds named
 * {@link com.lowdragmc.photon.client.gameobject.RuntimeValue} slots written by the timeline + the
 * value-use behaviour that used to live on the setting). The authored {@code config} stays immutable pure
 * data; a slot with no timeline value falls back to it. All reads are direct field access — no map, no
 * reflection.
 */
public class ParticleRuntime {
    public final ParticleConfig config;
    public final EmissionSetting.Runtime emission;
    public final ShapeSetting.Runtime shape;
    public final PhysicsSetting.Runtime physics;
    public final SizeOverLifetimeSetting.Runtime sizeOverLifetime;
    public final RotationOverLifetimeSetting.Runtime rotationOverLifetime;
    public final ForceOverLifetimeSetting.Runtime forceOverLifetime;
    public final ExternalForcesSetting.Runtime externalForces;
    public final LightOverLifetimeSetting.Runtime lights;
    public final ColorOverLifetimeSetting.Runtime colorOverLifetime;
    public final VelocityOverLifetimeSetting.Runtime velocityOverLifetime;
    public final InheritVelocitySetting.Runtime inheritVelocity;
    public final LifetimeByEmitterSpeedSetting.Runtime lifetimeByEmitterSpeed;
    public final ColorBySpeedSetting.Runtime colorBySpeed;
    public final SizeBySpeedSetting.Runtime sizeBySpeed;
    public final RotationBySpeedSetting.Runtime rotationBySpeed;
    public final NoiseSetting.Runtime noise;
    public final UVAnimationSetting.Runtime uvAnimation;
    public final TrailsSetting.Runtime trails;
    public final SubEmittersSetting.Runtime subEmitters;
    /** Per-instance override of the additional-GPU custom-data VALUES (structure stays from config). */
    public final CustomDataRuntime customData;

    // top-level ParticleConfig values (read directly by the emitter/particle, not via a setting)
    public final RuntimeValue<NumberFunction> startColor;
    public final RuntimeValue<NumberFunction> startDelay;
    public final RuntimeValue<NumberFunction> startLifetime;
    public final RuntimeValue<NumberFunction> startSpeed;
    public final RuntimeValue<NumberFunction3> startSize;
    public final RuntimeValue<NumberFunction3> startRotation;
    public final RuntimeValue<Integer> duration;
    public final RuntimeValue<Integer> prewarm;
    public final RuntimeValue<Integer> maxParticles;
    public final RuntimeValue<Boolean> looping;
    public final RuntimeValue<Boolean> parallelUpdate;

    /** Co-located per-instance render-override slots (material / renderer settings): {@code getX()} reads
     *  slot-or-config per field, no whole-renderer copy. Sits alongside the other {@code createRuntime()}
     *  layers. Batching-safe via equals-merge (see the lazy {@link #overridePass}). */
    public final ParticleRendererSetting.Runtime renderer;
    /** The per-emitter override render pass (lazy; wraps {@link #renderer}), or null while no slot is set. */
    @Nullable private PhotonFXRenderPass overridePass;

    public ParticleRuntime(ParticleConfig config) {
        this.config = config;
        this.startColor = new RuntimeValue<>(config::getStartColor);
        this.startDelay = new RuntimeValue<>(config::getStartDelay);
        this.startLifetime = new RuntimeValue<>(config::getStartLifetime);
        this.startSpeed = new RuntimeValue<>(config::getStartSpeed);
        this.startSize = new RuntimeValue<>(config::getStartSize);
        this.startRotation = new RuntimeValue<>(config::getStartRotation);
        this.duration = new RuntimeValue<>(config::getDuration);
        this.prewarm = new RuntimeValue<>(config::getPrewarm);
        this.maxParticles = new RuntimeValue<>(config::getMaxParticles);
        this.looping = new RuntimeValue<>(config::isLooping);
        this.parallelUpdate = new RuntimeValue<>(config::isParallelUpdate);
        this.emission = config.emission.createRuntime();
        this.shape = config.shape.createRuntime();
        this.physics = config.physics.createRuntime();
        this.sizeOverLifetime = config.sizeOverLifetime.createRuntime();
        this.rotationOverLifetime = config.rotationOverLifetime.createRuntime();
        this.forceOverLifetime = config.forceOverLifetime.createRuntime();
        this.externalForces = config.externalForces.createRuntime();
        this.lights = config.lights.createRuntime();
        this.colorOverLifetime = config.colorOverLifetime.createRuntime();
        this.velocityOverLifetime = config.velocityOverLifetime.createRuntime();
        this.inheritVelocity = config.inheritVelocity.createRuntime();
        this.lifetimeByEmitterSpeed = config.lifetimeByEmitterSpeed.createRuntime();
        this.colorBySpeed = config.colorBySpeed.createRuntime();
        this.sizeBySpeed = config.sizeBySpeed.createRuntime();
        this.rotationBySpeed = config.rotationBySpeed.createRuntime();
        this.noise = config.noise.createRuntime();
        this.uvAnimation = config.uvAnimation.createRuntime();
        this.trails = config.trails.createRuntime();
        this.subEmitters = config.subEmitters.createRuntime();
        this.customData = new CustomDataRuntime(config.additionalGPUDataSetting);
        this.renderer = config.renderer.createRuntime();
    }

    /**
     * The render pass this emitter draws through: its per-instance override pass when any render slot is
     * overridden (lazily built to wrap {@link #renderer}), else the shared {@code config.particleRenderType}
     * (fast path, unchanged batching). Reverting all slots disposes the override pass.
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

    /** Whether this emitter currently has any render-override slot set (drives {@link #effectiveRenderPass()}). */
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
        startColor.clear();
        startDelay.clear();
        startLifetime.clear();
        startSpeed.clear();
        startSize.clear();
        startRotation.clear();
        duration.clear();
        prewarm.clear();
        maxParticles.clear();
        looping.clear();
        parallelUpdate.clear();
        emission.clear();
        shape.clear();
        physics.clear();
        sizeOverLifetime.clear();
        rotationOverLifetime.clear();
        forceOverLifetime.clear();
        externalForces.clear();
        lights.clear();
        colorOverLifetime.clear();
        velocityOverLifetime.clear();
        inheritVelocity.clear();
        lifetimeByEmitterSpeed.clear();
        colorBySpeed.clear();
        sizeBySpeed.clear();
        rotationBySpeed.clear();
        noise.clear();
        uvAnimation.clear();
        trails.clear();
        subEmitters.clear();
        customData.clear();
        clearRenderOverride();
    }
}
