package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.google.common.collect.Queues;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/**
 * @author KilaBash
 * @date 2023/5/25
 * @implNote ParticleEmitter
 */
@ParametersAreNonnullByDefault
public class ParticleEmitter extends Emitter {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "particle");
    @LDLRegisterClient(name = "particle_emitter", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new ParticleEmitter();
        }

        @Override
        public IGuiTexture icon() {
            return ICON;
        }

        @Override
        public int version() {
            return 2;
        }

        @Override
        public List<RuntimeBinding> runtimeBindings() {
            return RUNTIME_BINDINGS;
        }
    };

    /** Timeline-animatable config values backed by named {@link ParticleRuntime} slots (no map/reflection). */
    public static final List<RuntimeBinding> RUNTIME_BINDINGS = List.of(
            new RuntimeBinding("startDelay", "ParticleConfig.startDelay", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().startDelay),
            new RuntimeBinding("startLifetime", "ParticleConfig.startLifetime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().startLifetime),
            new RuntimeBinding("startSpeed", "ParticleConfig.startSpeed", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().startSpeed),
            new RuntimeBinding("startSize", "ParticleConfig.startSize", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().startSize),
            new RuntimeBinding("startRotation", "ParticleConfig.startRotation", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().startRotation),
            new RuntimeBinding("duration", "ParticleConfig.duration", ConfigValueType.INT,
                    o -> ((ParticleEmitter) o).runtime().duration),
            new RuntimeBinding("prewarm", "ParticleConfig.prewarm", ConfigValueType.INT,
                    o -> ((ParticleEmitter) o).runtime().prewarm),
            new RuntimeBinding("maxParticles", "ParticleConfig.maxParticles", ConfigValueType.INT,
                    o -> ((ParticleEmitter) o).runtime().maxParticles),
            new RuntimeBinding("looping", "ParticleConfig.looping", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().looping),
            new RuntimeBinding("parallelUpdate", "ParticleConfig.parallelUpdate", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().parallelUpdate),
            new RuntimeBinding("emission.emissionRate", "EmissionSetting.emissionRate", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().emission.emissionRate),
            new RuntimeBinding("emission.distanceRate", "EmissionSetting.distanceRate", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().emission.distanceRate),
            new RuntimeBinding("shape.position", "NoiseSetting.position", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().shape.position),
            new RuntimeBinding("shape.rotation", "NoiseSetting.rotation", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().shape.rotation),
            new RuntimeBinding("shape.scale", "ShapeSetting.scale", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().shape.scale),
            new RuntimeBinding("physics.enable", "PhysicsSetting.enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().physics.enable),
            new RuntimeBinding("physics.hasCollision", "PhysicsSetting.hasCollision", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().physics.hasCollision),
            new RuntimeBinding("physics.friction", "PhysicsSetting.friction", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.friction),
            new RuntimeBinding("physics.collidedFriction", "PhysicsSetting.collidedFriction", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.collidedFriction),
            new RuntimeBinding("physics.gravity", "PhysicsSetting.gravity", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.gravity),
            new RuntimeBinding("physics.bounceChance", "PhysicsSetting.bounceChance", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.bounceChance),
            new RuntimeBinding("physics.bounceRate", "PhysicsSetting.bounceRate", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.bounceRate),
            new RuntimeBinding("physics.bounceSpreadRate", "PhysicsSetting.bounceSpreadRate", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.bounceSpreadRate),
            new RuntimeBinding("sizeOverLifetime.size", "NoiseSetting.size", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().sizeOverLifetime.size),
            new RuntimeBinding("rotationOverLifetime.roll", "RotationBySpeedSetting.roll", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().rotationOverLifetime.roll),
            new RuntimeBinding("rotationOverLifetime.pitch", "RotationBySpeedSetting.pitch", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().rotationOverLifetime.pitch),
            new RuntimeBinding("rotationOverLifetime.yaw", "RotationBySpeedSetting.yaw", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().rotationOverLifetime.yaw),
            new RuntimeBinding("forceOverLifetime.force", "ForceOverLifetimeSetting.force", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().forceOverLifetime.force),
            new RuntimeBinding("lights.skyLight", "LightOverLifetimeSetting.skyLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().lights.skyLight),
            new RuntimeBinding("lights.blockLight", "LightOverLifetimeSetting.blockLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().lights.blockLight),
            new RuntimeBinding("startColor", "ParticleConfig.startColor", ConfigValueType.COLOR,
                    o -> ((ParticleEmitter) o).runtime().startColor),
            new RuntimeBinding("colorOverLifetime.color", "ParticleConfig.colorOverLifetime.color", ConfigValueType.COLOR,
                    o -> ((ParticleEmitter) o).runtime().colorOverLifetime.color),
            // ---- animatable enables for every ToggleGroup setting (Part 2) ----
            new RuntimeBinding("colorOverLifetime.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().colorOverLifetime.enable),
            new RuntimeBinding("sizeOverLifetime.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().sizeOverLifetime.enable),
            new RuntimeBinding("rotationOverLifetime.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().rotationOverLifetime.enable),
            new RuntimeBinding("forceOverLifetime.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().forceOverLifetime.enable),
            new RuntimeBinding("lights.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().lights.enable),
            new RuntimeBinding("velocityOverLifetime.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().velocityOverLifetime.enable),
            new RuntimeBinding("inheritVelocity.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().inheritVelocity.enable),
            new RuntimeBinding("lifetimeByEmitterSpeed.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().lifetimeByEmitterSpeed.enable),
            new RuntimeBinding("colorBySpeed.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().colorBySpeed.enable),
            new RuntimeBinding("sizeBySpeed.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().sizeBySpeed.enable),
            new RuntimeBinding("rotationBySpeed.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().rotationBySpeed.enable),
            new RuntimeBinding("noise.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().noise.enable),
            new RuntimeBinding("uvAnimation.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().uvAnimation.enable),
            new RuntimeBinding("trails.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().trails.enable),
            new RuntimeBinding("subEmitters.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().subEmitters.enable),
            // ---- migrated setting values (Part 1) ----
            new RuntimeBinding("velocityOverLifetime.linear", "VelocityOverLifetimeSetting.linear", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().velocityOverLifetime.linear),
            new RuntimeBinding("velocityOverLifetime.orbital", "VelocityOverLifetimeSetting.orbital", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().velocityOverLifetime.orbital),
            new RuntimeBinding("velocityOverLifetime.offset", "VelocityOverLifetimeSetting.offset", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().velocityOverLifetime.offset),
            new RuntimeBinding("velocityOverLifetime.radial", "VelocityOverLifetimeSetting.radial", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().velocityOverLifetime.radial),
            new RuntimeBinding("velocityOverLifetime.speedModifier", "VelocityOverLifetimeSetting.speedModifier", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().velocityOverLifetime.speedModifier),
            new RuntimeBinding("inheritVelocity.multiply", "InheritVelocitySetting.multiply", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().inheritVelocity.multiply),
            new RuntimeBinding("lifetimeByEmitterSpeed.multiplier", "LifetimeByEmitterSpeedSetting.multiplier", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().lifetimeByEmitterSpeed.multiplier),
            new RuntimeBinding("colorBySpeed.color", "ColorBySpeedSetting.color", ConfigValueType.COLOR,
                    o -> ((ParticleEmitter) o).runtime().colorBySpeed.color),
            new RuntimeBinding("sizeBySpeed.size", "NoiseSetting.size", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().sizeBySpeed.size),
            new RuntimeBinding("rotationBySpeed.roll", "RotationBySpeedSetting.roll", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().rotationBySpeed.roll),
            new RuntimeBinding("rotationBySpeed.pitch", "RotationBySpeedSetting.pitch", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().rotationBySpeed.pitch),
            new RuntimeBinding("rotationBySpeed.yaw", "RotationBySpeedSetting.yaw", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().rotationBySpeed.yaw),
            new RuntimeBinding("noise.frequency", "NoiseSetting.frequency", ConfigValueType.FLOAT,
                    o -> ((ParticleEmitter) o).runtime().noise.frequency),
            new RuntimeBinding("noise.position", "NoiseSetting.position", ConfigValueType.NUMBER_FUNCTION3,
                    o -> ((ParticleEmitter) o).runtime().noise.position),
            new RuntimeBinding("noise.rotation", "NoiseSetting.rotation", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().noise.rotation),
            new RuntimeBinding("noise.size", "NoiseSetting.size", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().noise.size),
            new RuntimeBinding("uvAnimation.frameOverTime", "UVAnimationSetting.frameOverTime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().uvAnimation.frameOverTime),
            new RuntimeBinding("uvAnimation.startFrame", "UVAnimationSetting.startFrame", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().uvAnimation.startFrame),
            new RuntimeBinding("uvAnimation.cycle", "UVAnimationSetting.cycle", ConfigValueType.FLOAT,
                    o -> ((ParticleEmitter) o).runtime().uvAnimation.cycle),
            new RuntimeBinding("trails.ratio", "TrailsSetting.ratio", ConfigValueType.FLOAT,
                    o -> ((ParticleEmitter) o).runtime().trails.ratio),
            new RuntimeBinding("trails.lifetime", "TrailsSetting.lifetime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().trails.lifetime),
            new RuntimeBinding("trails.dieWithParticles", "TrailsSetting.dieWithParticles", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().trails.dieWithParticles),
            new RuntimeBinding("trails.sizeAffectsWidth", "TrailsSetting.sizeAffectsWidth", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().trails.sizeAffectsWidth),
            new RuntimeBinding("trails.sizeAffectsLifetime", "TrailsSetting.sizeAffectsLifetime", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().trails.sizeAffectsLifetime),
            new RuntimeBinding("trails.inheritParticleColor", "TrailsSetting.inheritParticleColor", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().trails.inheritParticleColor),
            new RuntimeBinding("trails.colorOverLifetime", "TrailsSetting.colorOverLifetime", ConfigValueType.COLOR,
                    o -> ((ParticleEmitter) o).runtime().trails.colorOverLifetime));

    @Persisted(subPersisted = true)
    public final ParticleConfig config;

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private ParticleRuntime runtime;

    // runtime
    protected boolean hasFirstUpdate = false;
    @Getter @Setter
    protected float accumulatedDistance = 0;
    /** Fractional carry of the time-based emission rate, so a scaled {@code dt} emits whole particles. */
    @Getter @Setter
    protected float emissionRateAccum = 0;
    @Getter
    protected final Map<PhotonFXRenderPass, Queue<IParticle>> particles = new LinkedHashMap<>();
    public final Queue<IParticle> waitToAdded = Queues.newArrayDeque();
    protected int particleBatchCount = 1;
    protected int particleBatchCursor = 0;

    public ParticleEmitter() {
        this(new ParticleConfig());
    }

    protected ParticleEmitter(ParticleConfig config) {
        this.config = config;
    }

    /** This emitter's per-instance runtime layer (lazily created; timeline overrides live here). */
    public ParticleRuntime runtime() {
        if (runtime == null) {
            runtime = new ParticleRuntime(config);
        }
        return runtime;
    }

    @Override
    public IGuiTexture getIcon() {
        return ICON;
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

    @Override
    public ParticleEmitter shallowCopy() {
        return new ParticleEmitter(config);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        config.buildConfigurator(father);
    }

    protected TileParticle createNewParticle() {
        return new TileParticle(this, config);
    }

    @Override
    protected void onTickBegin() {
        super.onTickBegin();
        for (var queue : particles.values()) {
            for (var particle : queue) {
                particle.syncOrigin();
            }
        }
    }

    @Override
    public void update(float dt) {
        if (!hasFirstUpdate) {
            hasFirstUpdate = true;
            var prewarm = runtime().prewarm.get();
            if (prewarm > 0) {
                for (int i = 0; i < prewarm; i++) {
                    emitParticle(1f); // prewarm always simulates whole ticks
                    super.update(1f);
                    if (removed) {
                        return;
                    }
                }
            }
        }
        emitParticle(dt);
        super.update(dt);
    }

    public void emitParticle(float dt) {
        // calculate distance (scaled by this step's dt)
        accumulatedDistance += getVelocity().length() * dt;
        // emit new particle (maxParticles may be timeline-overridden; authored value is the fallback)
        var maxParticles = runtime().maxParticles.get();
        var available = maxParticles - getParticleAmount();
        if (!removed && getParticleAmount() < maxParticles) {
            var emissionCount = runtime().emission.getEmissionCount(this, getRandomSource(), dt);
            available = Math.min(emissionCount, available);
            particleBatchCount = Math.max(1, available);
            particleBatchCursor = 0;
            for (int i = 0; i < available; i++) {
                emitParticle(createNewParticle());
            }
        }

        // particles life cycle
        if (!waitToAdded.isEmpty()) {
            for (var p : waitToAdded) {
                particles.computeIfAbsent(p.getRenderType(), type -> new ArrayDeque<>(maxParticles)).add(p);
            }
            waitToAdded.clear();
        }

        for (var queue : particles.values()) {
            if (runtime().parallelUpdate.get() && (!runtime().physics.isEnable() || !runtime().physics.hasCollision())) { // parallel stream for particles tick.
                queue.removeIf(p -> !p.isAlive());
                queue.parallelStream().forEach(p -> p.updateTick(dt));
            } else {
                var iter = queue.iterator();
                while (iter.hasNext()) {
                    var particle = iter.next();
                    if (!particle.isAlive()) {
                        iter.remove();
                    } else {
                        particle.updateTick(dt);
                    }
                }
            }
        }
    }

    @Override
    public boolean isLooping() {
        return runtime().looping.get();
    }

    public void emitParticle(IParticle particle) {
        waitToAdded.add(particle);
    }

    @Override
    public int getLifetime() {
        return runtime().duration.get();
    }

    @Override
    protected void updateOrigin() {
        super.updateOrigin();
        setLifetime(getLifetime());
    }

    @Override
    public void reset() {
        super.reset();
        if (runtime != null) {
            runtime.clear(); // drop timeline overrides; fall back to authored config
        }
        this.particles.clear();
        this.hasFirstUpdate = false;
        this.particleBatchCount = 1;
        this.particleBatchCursor = 0;
        this.accumulatedDistance = 0;
        this.emissionRateAccum = 0;
    }

    @Override
    public boolean useTranslucentPipeline() {
        return config.renderer.getLayer() == RendererSetting.Layer.Translucent;
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {
        if (isVisible()) {
            for(var entry : this.particles.entrySet()) {
                var pass = entry.getKey();
                var queue = entry.getValue();
                if (!queue.isEmpty()) {
                    buffer.pipeQueue(pass, queue);
                }
            }
        }
    }

    @Override
    public int getParticleAmount() {
        return getParticles().values().stream().mapToInt(Collection::size).sum() + waitToAdded.size();
    }

    @Override
    @Nullable
    public AABB getCullBox(float partialTicks) {
        return config.renderer.getCull().isEnable() ? config.renderer.getCull().getCullAABB(this, partialTicks) : null;
    }

    @Override
    public void remove(boolean force) {
        super.remove(force);
        if (force) {
            particles.clear();
        }
    }

    @Override
    public void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {
        if(scene.sceneView().isShapeVisible()) {
            runtime().shape.drawGuideLines(bufferSource, partialTicks, this);
        }
    }

    public int nextParticleBatchIndex() {
        return particleBatchCursor++;
    }

    public int getParticleBatchCount() {
        return particleBatchCount;
    }
}
