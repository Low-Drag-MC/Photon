package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialRequirements;
import com.google.common.collect.Queues;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataBindings;
import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomSpace;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.TrailsSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ValueSpace;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldObject;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.SpawnFrame;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.DummyWorld;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.collision.CollisionDebug;
import com.lowdragmc.photon.client.collision.ContactMemory;
import com.lowdragmc.photon.client.collision.CollisionEvent;
import com.lowdragmc.photon.client.collision.CollisionScene;
import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.collision.ColliderTarget;
import com.lowdragmc.photon.client.collision.EntityTarget;
import com.lowdragmc.photon.client.collision.BlockBoxSource;
import com.lowdragmc.photon.client.gameobject.collider.ColliderObject;
import com.lowdragmc.photon.client.gameobject.collider.CollisionGizmos;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.light.LightProvider;
import com.lowdragmc.photon.client.light.LightSink;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import oshi.util.tuples.Pair;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

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

        // custom-data channels are per-CONFIG dynamic (variable stream/channel count), so they can't live
        // in the static RUNTIME_BINDINGS / cached type-level list — enumerate them per target instance.
        @Override
        public List<AnimatedPropertyType> animatableProperties(FXObject target) {
            if (!(target instanceof ParticleEmitter emitter)) {
                return animatableProperties();
            }
            return CustomDataBindings.appendAnimatable(animatableProperties(),
                    emitter.config.additionalGPUDataSetting.customDataStreams(),
                    o -> ((ParticleEmitter) o).runtime().customData);
        }

        @Override
        public RuntimeBinding resolveRuntimeBinding(FXObject target, String path) {
            var b = super.resolveRuntimeBinding(target, path);
            return b != null ? b : CustomDataBindings.resolve(path, o -> ((ParticleEmitter) o).runtime().customData);
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
            new RuntimeBinding("physics.removedWhenCollided", "PhysicsSetting.removedWhenCollided", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().physics.removedWhenCollided),
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
            new RuntimeBinding("physics.collision.blocks", "CollisionSetting.blocks", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().physics.collision.blocks),
            new RuntimeBinding("physics.collision.fluids", "CollisionSetting.fluids", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().physics.collision.fluids),
            new RuntimeBinding("physics.collision.entities", "CollisionSetting.entities", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().physics.collision.entities),
            new RuntimeBinding("physics.collision.colliders", "CollisionSetting.colliders", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().physics.collision.colliders),
            new RuntimeBinding("physics.collision.response.dampen", "CollisionSetting.dampen", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.collision.dampen),
            new RuntimeBinding("physics.collision.response.lifetimeLoss", "CollisionSetting.lifetimeLoss", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().physics.collision.lifetimeLoss),
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
            new RuntimeBinding("externalForces.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().externalForces.enable),
            new RuntimeBinding("externalForces.multiplier", "ExternalForcesSetting.multiplier", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().externalForces.multiplier),
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
            new RuntimeBinding("lightEmission.enable", "enable", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().lightEmission.enable),
            new RuntimeBinding("lightEmission.color", "LightEmissionSetting.color", ConfigValueType.COLOR,
                    o -> ((ParticleEmitter) o).runtime().lightEmission.color),
            new RuntimeBinding("lightEmission.intensity", "LightEmissionSetting.intensity", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().lightEmission.intensity),
            new RuntimeBinding("lightEmission.range", "LightEmissionSetting.range", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ParticleEmitter) o).runtime().lightEmission.range),
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
                    o -> ((ParticleEmitter) o).runtime().trails.colorOverLifetime),
            // ---- render settings (pass-level: animating any of these gives the emitter its own override
            //      pass; only per-frame-read fields with no GL baking are exposed — see ParticleRendererSetting.Runtime)
            new RuntimeBinding("renderer.orderInLayer", "photon.emitter.config.renderer.orderInLayer", ConfigValueType.INT,
                    o -> ((ParticleEmitter) o).runtime().renderer.orderInLayer),
            new RuntimeBinding("renderer.useGPUInstance", "ParticleRendererSetting.useGPUInstance", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().renderer.useGPUInstance),
            new RuntimeBinding("renderer.velocityScale", "photon.emitter.config.renderer.renderMode.stretchedBillboard.velocityScale", ConfigValueType.FLOAT,
                    o -> ((ParticleEmitter) o).runtime().renderer.velocityScale),
            new RuntimeBinding("renderer.lengthScale", "photon.emitter.config.renderer.renderMode.stretchedBillboard.lengthScale", ConfigValueType.FLOAT,
                    o -> ((ParticleEmitter) o).runtime().renderer.lengthScale),
            new RuntimeBinding("renderer.writeCustomMask", "photon.emitter.config.renderer.writeCustomMask", ConfigValueType.BOOL,
                    o -> ((ParticleEmitter) o).runtime().renderer.writeCustomMask),
            new RuntimeBinding("renderer.maskAlphaCutoff", "photon.emitter.config.renderer.maskAlphaCutoff", ConfigValueType.FLOAT,
                    o -> ((ParticleEmitter) o).runtime().renderer.maskAlphaCutoff));

    @Persisted(subPersisted = true)
    public final ParticleConfig config;

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private ParticleRuntime runtime;

    /** Below this queue size, parallelStream scheduling overhead outweighs the win — stay serial. */
    private static final int PARALLEL_UPDATE_MIN_PARTICLES = 128;

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

    public ParticleEmitter(ParticleConfig config) {
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
    public void preload(PreloadContext context) {
        config.particleRenderType.preload(context, context.isAnimated(this, "renderer.useGPUInstance"),
                context.isAnimated(this, "renderer.writeCustomMask"));
        var trails = config.trails;
        if (trails.isEnable() || context.isAnimated(this, "trails.enable")) {
            var trailPass = trails.getTrailType() == TrailsSetting.TrailType.ARA_TRAIL
                    ? trails.araConfig.particleRenderType : trails.config.particleRenderType;
            trailPass.preload(context, false, false);
        }
        if (config.subEmitters.isEnable() || context.isAnimated(this, "subEmitters.enable")) {
            for (var subEmitter : config.subEmitters.getEmitters()) {
                if (subEmitter.getFxLocation() != null) {
                    context.fx(subEmitter.getFxLocation());
                }
            }
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        config.buildConfigurator(father);
        MaterialRequirements.addRow(father, config.renderer::getMaterials, config.additionalGPUDataSetting, true,
                config.renderer::isUseGPUInstance, config.renderer::setUseGPUInstance);
    }

    protected TileParticle createNewParticle() {
        return new TileParticle(this, config);
    }

    /** Read-only identity fallbacks for world simulation space (never mutated by callers). */
    private static final Matrix4f IDENTITY = new Matrix4f();

    /**
     * Resolve the transform defining this emitter's simulation space, or null for world space (identity).
     * Custom space falls back to world space while the referenced transform is unresolved.
     */
    @Nullable
    protected Transform resolveSimSpaceTransform() {
        var space = config.getSimulationSpace();
        // null-guard: unknown enum names deserialize to null (legacy data)
        if (space == null || space == ParticleConfig.Space.World) {
            return null;
        }
        if (space == ParticleConfig.Space.Local) {
            return transform();
        }
        return config.customSpace.getTransform(getScene());
    }

    /** Cache for {@link #currentSpawnFrame()}, keyed on the matrix instances it was built from. */
    @Nullable
    private SpawnFrame cachedSpawnFrame;
    @Nullable
    private Matrix4f cachedFrameEmitterToWorld;
    @Nullable
    private Matrix4f cachedFrameSimToWorld;

    /**
     * The {@link SpawnFrame} particles emitted right now should be born into, or {@code null} in Local
     * simulation space (where the emitter's frame IS simulation space, so no conversion is needed).
     *
     * <p>Shared by every particle emitted while the emitter has not moved: {@link Transform} rebuilds
     * its cached matrices <b>as new instances</b> whenever the transform changes, so comparing the two
     * source matrices by reference is an exact staleness check — an emitter that moves gets a fresh
     * frame on its very next particle, exactly as if each particle had read the live matrices itself.
     */
    @Nullable
    public SpawnFrame currentSpawnFrame() {
        if (config.getSimulationSpace() == ParticleConfig.Space.Local) {
            return null;
        }
        var emitterToWorld = transform().localToWorldMatrix();
        var simToWorld = getSimToWorld();
        if (cachedSpawnFrame != null
                && cachedFrameEmitterToWorld == emitterToWorld
                && cachedFrameSimToWorld == simToWorld) {
            return cachedSpawnFrame;
        }
        cachedFrameEmitterToWorld = emitterToWorld;
        cachedFrameSimToWorld = simToWorld;
        cachedSpawnFrame = new SpawnFrame(emitterToWorld, transform().worldToLocalMatrix(),
                getWorldToSim(), simToWorld);
        return cachedSpawnFrame;
    }

    @Override
    public Matrix4f getSimToWorld() {
        var simSpace = resolveSimSpaceTransform();
        return simSpace == null ? IDENTITY : simSpace.localToWorldMatrix();
    }

    @Override
    public Matrix4f getWorldToSim() {
        var simSpace = resolveSimSpaceTransform();
        return simSpace == null ? IDENTITY : simSpace.worldToLocalMatrix();
    }

    @Override
    public Vector3f getSimSpaceScale() {
        var simSpace = resolveSimSpaceTransform();
        return simSpace == null ? new Vector3f(1) : simSpace.scale();
    }

    @Override
    public Quaternionf getSimSpaceRotation() {
        var simSpace = resolveSimSpaceTransform();
        return simSpace == null ? new Quaternionf() : simSpace.rotation();
    }

    @Override
    protected void onTickBegin() {
        super.onTickBegin();
        // Transform's lazy matrix caches are not synchronized; warm them on the game thread
        // before particles may update via parallelStream.
        transform().localToWorldMatrix();
        transform().worldToLocalMatrix();
        var simSpace = resolveSimSpaceTransform();
        if (simSpace != null) {
            simSpace.localToWorldMatrix();
            simSpace.worldToLocalMatrix();
            simSpace.scale();
            simSpace.rotation();
        }
        if (runtime().forceOverLifetime.getSimulationSpace() == ValueSpace.Custom) {
            CustomSpace.warm(config.forceOverLifetime.customSpace, getScene());
        }
        if (runtime().velocityOverLifetime.space.get() == ValueSpace.Custom) {
            CustomSpace.warm(config.velocityOverLifetime.customSpace, getScene());
        }
        var araConfig = config.trails.araConfig;
        if (araConfig.physicsSetting.gravitySpace == ValueSpace.Custom) {
            CustomSpace.warm(araConfig.physicsSetting.gravityCustomSpace, getScene());
        }
        if (araConfig.space == AraTrailConfig.TrailSpace.Custom) {
            CustomSpace.warm(araConfig.customSpace, getScene());
        }
        activeForceFields = gatherForceFields();
        collisionScene = buildCollisionScene();
        for (var queue : particles.values()) {
            for (var particle : queue) {
                particle.syncOrigin();
            }
        }
    }

    /** Immutable per-tick snapshot of the force fields affecting this emitter (read concurrently
     *  by parallel particle updates; see {@link #gatherForceFields}). */
    private List<ForceFieldObject> activeForceFields = List.of();

    /** Sub-emitter spawns from particle updates (possibly on workers), drained in particle order at the end of the tick. */
    private final Queue<Deferred> pendingSubEmitterSpawns = new ConcurrentLinkedQueue<>();

    private record Deferred(long order, Runnable action) {
    }

    public void scheduleSubEmitterSpawn(Runnable spawn) {
        scheduleSubEmitterSpawn(Long.MAX_VALUE, spawn);
    }

    /** @param order a particle's {@code nextSpawnOrder()}: lower runs first */
    public void scheduleSubEmitterSpawn(long order, Runnable spawn) {
        pendingSubEmitterSpawns.add(new Deferred(order, spawn));
    }

    private boolean deferredFailed;
    /** Removed with force: what is still queued, even mid-drain, is dropped. */
    private boolean forceRemoved;

    private void drainInOrder(Queue<Deferred> queue) {
        if (queue.isEmpty()) return;
        var batch = new ArrayList<Deferred>(queue.size());
        for (Deferred deferred; (deferred = queue.poll()) != null; ) batch.add(deferred);
        batch.sort(Comparator.comparingLong(Deferred::order));
        for (var deferred : batch) {
            if (forceRemoved) return;
            // a listener or sub-emitter that throws must not take the game down with it
            try {
                deferred.action().run();
            } catch (RuntimeException e) {
                if (!deferredFailed) {
                    deferredFailed = true;
                    Photon.LOGGER.error("A collision listener or sub-emitter spawn of {} failed; later failures are not logged", getName(), e);
                }
            }
        }
    }

    public List<ForceFieldObject> getActiveForceFields() {
        return activeForceFields;
    }

    private List<ForceFieldObject> gatherForceFields() {
        if (!runtime().externalForces.isEnable() || !(getScene() instanceof FXRuntime fxRuntime)) {
            return List.of();
        }
        var setting = config.externalForces;
        List<ForceFieldObject> list = null;
        for (var object : fxRuntime.objects.values()) {
            if (object instanceof ForceFieldObject field && field.isActive() && !field.isRemoved()
                    && setting.isInfluencedBy(field)) {
                // warm the field's transform caches for the parallel particle updates
                field.transform().localToWorldMatrix();
                field.transform().worldToLocalMatrix();
                field.transform().rotation();
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.add(field);
            }
        }
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Everything this emitter's particles collide with this tick; null while collision is off. */
    @Nullable
    private CollisionScene collisionScene;
    @Nullable
    private CollisionWorld collisionWorld;
    private final CollisionScene.Stats collisionStats = new CollisionScene.Stats();
    private final List<CollisionListener> collisionListeners = new CopyOnWriteArrayList<>();
    private final ContactMemory reportedTargets = new ContactMemory();
    /** This tick's de-duplication verdicts, so every consumer of one event (sub-emitters, listeners) agrees. */
    private final Map<CollisionEvent, Boolean> dedupeVerdicts = new IdentityHashMap<>();
    /** Per effect, the memories its emitters share by group name. */
    private static final Map<Object, Map<String, ContactMemory>> GROUP_MEMORY = Collections.synchronizedMap(new WeakHashMap<>());
    /** Entities that were around where the effect started; see {@code sourceExclusionRadius}. */
    @Nullable
    private IntOpenHashSet sourceEntities;
    /** Around the emitter, where the particles emitted this tick may already have got to. */
    private static final double NEW_PARTICLE_REACH = 8;
    private final Queue<Deferred> pendingCollisionReports = new ConcurrentLinkedQueue<>();
    private int particleSerial;
    private long collisionNanos;
    private boolean collisionParallel;
    private final Vector3f simOrigin = new Vector3f();
    private boolean simOriginValid;
    private double simMotionX, simMotionY, simMotionZ;
    private double targetMotionScale = 1;
    /** Last tick's stats for the editor: casts, box tests, contacts, depenetrations, misses. */
    private final long[] lastCollisionStats = new long[5];
    private double lastCollisionMillis;
    private boolean lastCollisionParallel;
    /** Recent contacts for the editor's collision view, position and normal each. */
    private static final int CONTACT_LOG = 256;
    @Nullable
    private float[] contactLog;
    private int contactLogSize;
    private int contactLogNext;

    /** Receives this emitter's particle collisions on the game thread, at the end of each tick. */
    @FunctionalInterface
    public interface CollisionListener {
        void onCollision(ParticleEmitter emitter, IParticle particle, CollisionEvent event);
    }

    public void addCollisionListener(CollisionListener listener) {
        collisionListeners.add(listener);
    }

    public void removeCollisionListener(CollisionListener listener) {
        collisionListeners.remove(listener);
    }

    @Nullable
    public CollisionScene collisionScene() {
        return collisionScene;
    }

    /** Whether anything consumes collision events: listeners, or the editor's collision view. */
    public boolean wantsCollisionEvents() {
        return !collisionListeners.isEmpty() || (CollisionDebug.enabled && getLevel() instanceof DummyWorld);
    }

    /** Queues an event for delivery on the game thread at the end of this tick. Any thread. */
    public void reportCollision(TileParticle particle, CollisionEvent event) {
        if (wantsCollisionEvents()) {
            pendingCollisionReports.add(new Deferred(particle.nextSpawnOrder(), () -> deliver(particle, event)));
        }
    }

    private void deliver(TileParticle particle, CollisionEvent event) {
        if (CollisionDebug.enabled && event.type() != CollisionEvent.Type.TRIGGER_EXIT) {
            if (contactLog == null) contactLog = new float[CONTACT_LOG * 6];
            int base = contactLogNext * 6;
            var position = event.position();
            var normal = event.normal();
            contactLog[base] = position.x;
            contactLog[base + 1] = position.y;
            contactLog[base + 2] = position.z;
            contactLog[base + 3] = normal.x;
            contactLog[base + 4] = normal.y;
            contactLog[base + 5] = normal.z;
            contactLogNext = (contactLogNext + 1) % CONTACT_LOG;
            contactLogSize = Math.min(contactLogSize + 1, CONTACT_LOG);
        }
        if (!passesDedupe(particle, event)) return;
        for (var listener : collisionListeners) {
            listener.onCollision(this, particle, event);
        }
    }

    /** Whether de-duplication applies to the event: impacts and contact deaths, while it is on. Any thread. */
    public boolean dedupes(CollisionEvent event) {
        return runtime().physics.collision.config().events.getDedupe() != CollisionSetting.Dedupe.Off
                && (event.type() == CollisionEvent.Type.IMPACT || event.type() == CollisionEvent.Type.DEATH);
    }

    /**
     * Whether a collision event survives this emitter's de-duplication; the first of its consumers decides for all.
     * Game thread, while the tick's deferred effects drain.
     */
    public boolean passesDedupe(TileParticle particle, CollisionEvent event) {
        if (!dedupes(event)) return true;
        var events = runtime().physics.collision.config().events;
        var dedupe = events.getDedupe();
        var verdict = dedupeVerdicts.get(event);
        if (verdict != null) return verdict;
        var memory = switch (dedupe) {
            case PerParticle -> particle.reportedTargets();
            case PerGroup -> groupMemory(events.getGroup());
            default -> reportedTargets;
        };
        // a target's impacts and its contact deaths are remembered apart
        long key = event.identity() << 1 | (event.type() == CollisionEvent.Type.DEATH ? 1 : 0);
        boolean passes = memory.tryReport(key, effectTick(), events.getCooldown());
        dedupeVerdicts.put(event, passes);
        return passes;
    }

    private ContactMemory groupMemory(@Nullable String group) {
        var scene = getScene();
        if (group == null || group.isBlank() || scene == null) return reportedTargets;
        return GROUP_MEMORY.computeIfAbsent(scene, key -> new HashMap<>()).computeIfAbsent(group, key -> new ContactMemory());
    }

    /** The effect's clock, the same for all of its emitters: its root's ticks. */
    private long effectTick() {
        return getScene() instanceof FXRuntime fxRuntime && fxRuntime.root instanceof FXObject root ? root.getLastTick() : getLastTick();
    }

    private void drainCollisionReports() {
        drainInOrder(pendingCollisionReports);
    }

    @Nullable
    private CollisionScene buildCollisionScene() {
        lastCollisionStats[0] = collisionStats.casts.sumThenReset();
        lastCollisionStats[1] = collisionStats.boxTests.sumThenReset();
        lastCollisionStats[2] = collisionStats.contacts.sumThenReset();
        lastCollisionStats[3] = collisionStats.depenetrations.sumThenReset();
        lastCollisionStats[4] = collisionStats.misses.sumThenReset();
        lastCollisionMillis = collisionNanos / 1e6;
        lastCollisionParallel = collisionParallel;
        collisionNanos = 0;
        collisionParallel = false;
        var physics = runtime().physics;
        var level = getLevel();
        if (!physics.isEnable() || !physics.hasCollision() || level == null) {
            collisionWorld = null;
            simOriginValid = false;
            return null;
        }
        // targets move relative to the simulation space, which drags Local and Custom particles along
        var origin = getSimToWorld().getTranslation(new Vector3f());
        simMotionX = simMotionY = simMotionZ = 0;
        if (simOriginValid && origin.distanceSquared(simOrigin) <= CollisionScene.MAX_TARGET_MOTION * CollisionScene.MAX_TARGET_MOTION) {
            simMotionX = origin.x - simOrigin.x;
            simMotionY = origin.y - simOrigin.y;
            simMotionZ = origin.z - simOrigin.z;
        }
        simOrigin.set(origin);
        simOriginValid = true;
        // motions are measured per game tick, and a tick's sub-steps simulate timeScale ticks
        float timeScale = timeScale();
        targetMotionScale = timeScale > 1e-6f ? 1 / timeScale : 0;
        var collision = physics.collision;
        collisionWorld = CollisionWorld.of(level);
        int mask = (collision.blocks.get() ? BlockBoxSource.BLOCKS : 0) | (collision.fluids.get() ? BlockBoxSource.FLUIDS : 0);
        var entities = collision.entities.get() ? gatherEntities(level, collision) : CollisionScene.NO_ENTITIES;
        var colliders = collision.colliders.get() ? gatherColliders(collision) : CollisionScene.NO_COLLIDERS;
        return new CollisionScene(mask == 0 ? null : collisionWorld, mask, mask == 0 ? null : collision.blockFilter(),
                entities, colliders, collisionStats);
    }

    private EntityTarget[] gatherEntities(Level level, CollisionSetting.Runtime collision) {
        var bounds = particleBounds();
        var filters = collision.config().filters;
        var executor = getEffectExecutor();
        var owner = filters.isIgnoreOwner() && executor != null ? executor.owner() : null;
        double radius = filters.getSourceExclusionRadius();
        if (radius > 0 && sourceEntities == null) sourceEntities = entitiesNearSource(level, radius);
        var excluded = radius > 0 ? sourceEntities : null;
        boolean trigger = filters.getEntityResponse() == CollisionSetting.EntityResponse.Trigger;
        var found = level.getEntities((Entity) null, bounds, entity -> entity != owner && entity.isAlive()
                && (excluded == null || !excluded.contains(entity.getId())) && collision.acceptsEntity(entity));
        if (found.isEmpty()) return CollisionScene.NO_ENTITIES;
        var targets = new EntityTarget[found.size()];
        for (int i = 0; i < targets.length; i++) {
            var entity = found.get(i);
            var box = entity.getBoundingBox();
            double mx = entity.getX() - entity.xo, my = entity.getY() - entity.yo, mz = entity.getZ() - entity.zo;
            if (mx * mx + my * my + mz * mz > CollisionScene.MAX_TARGET_MOTION * CollisionScene.MAX_TARGET_MOTION) {
                mx = my = mz = 0;
            }
            double k = targetMotionScale;
            targets[i] = new EntityTarget(entity, entity.getId(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    (mx - simMotionX) * k, (my - simMotionY) * k, (mz - simMotionZ) * k, trigger);
        }
        return targets;
    }

    /** The entities within {@code radius} of where the effect started, taken once per playback. */
    private IntOpenHashSet entitiesNearSource(Level level, double radius) {
        var origin = getScene() instanceof FXRuntime fxRuntime ? fxRuntime.root.transform().position() : transform().position();
        var near = new IntOpenHashSet();
        for (var entity : level.getEntities((Entity) null, new AABB(origin.x - radius, origin.y - radius, origin.z - radius,
                origin.x + radius, origin.y + radius, origin.z + radius), entity -> true)) {
            var box = entity.getBoundingBox();
            double dx = Math.max(0, Math.max(box.minX - origin.x, origin.x - box.maxX));
            double dy = Math.max(0, Math.max(box.minY - origin.y, origin.y - box.maxY));
            double dz = Math.max(0, Math.max(box.minZ - origin.z, origin.z - box.maxZ));
            if (dx * dx + dy * dy + dz * dz <= radius * radius) near.add(entity.getId());
        }
        return near;
    }

    /**
     * Where this emitter's particles can get to this tick: where they are, grown by how far the fastest moves,
     * plus a margin around the emitter for the ones it is about to emit.
     */
    private AABB particleBounds() {
        var origin = transform().position();
        double minX = origin.x - NEW_PARTICLE_REACH, minY = origin.y - NEW_PARTICLE_REACH, minZ = origin.z - NEW_PARTICLE_REACH;
        double maxX = origin.x + NEW_PARTICLE_REACH, maxY = origin.y + NEW_PARTICLE_REACH, maxZ = origin.z + NEW_PARTICLE_REACH;
        float steps = Math.max(1, timeScale());
        double reach = 1;
        for (var queue : particles.values()) {
            for (var particle : queue) {
                if (!(particle instanceof TileParticle tile) || !tile.isAlive()) continue;
                var pos = tile.getWorldPos(1);
                minX = Math.min(minX, pos.x);
                minY = Math.min(minY, pos.y);
                minZ = Math.min(minZ, pos.z);
                maxX = Math.max(maxX, pos.x);
                maxY = Math.max(maxY, pos.y);
                maxZ = Math.max(maxZ, pos.z);
                var size = tile.getRealSize(1);
                reach = Math.max(reach, tile.getRealVelocity().length() * steps + Math.max(size.x, Math.max(size.y, size.z)));
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(reach + 1);
    }

    private ColliderTarget[] gatherColliders(CollisionSetting.Runtime collision) {
        if (!(getScene() instanceof FXRuntime fxRuntime)) return CollisionScene.NO_COLLIDERS;
        var filters = collision.config().filters;
        List<ColliderTarget> list = null;
        for (var object : fxRuntime.objects.values()) {
            if (object instanceof ColliderObject collider && collider.isActive() && !collider.isRemoved()
                    && filters.accepts(collider)) {
                var target = collider.target();
                if (target == null) continue;
                if (list == null) list = new ArrayList<>();
                list.add(target.relativeTo(simMotionX, simMotionY, simMotionZ, targetMotionScale));
            }
        }
        return list == null ? CollisionScene.NO_COLLIDERS : list.toArray(new ColliderTarget[0]);
    }

    private void drawCollisionDebug() {
        if (contactLogSize == 0 || contactLog == null) return;
        var lines = new ArrayList<Pair<Vector3f, Vector3f>>(contactLogSize * 4);
        for (int i = 0; i < contactLogSize; i++) {
            int base = i * 6;
            var point = new Vector3f(contactLog[base], contactLog[base + 1], contactLog[base + 2]);
            float s = 0.05f;
            lines.add(new Pair<>(new Vector3f(point).add(-s, 0, 0), new Vector3f(point).add(s, 0, 0)));
            lines.add(new Pair<>(new Vector3f(point).add(0, -s, 0), new Vector3f(point).add(0, s, 0)));
            lines.add(new Pair<>(new Vector3f(point).add(0, 0, -s), new Vector3f(point).add(0, 0, s)));
            lines.add(new Pair<>(point, new Vector3f(point).add(
                    contactLog[base + 3] * 0.3f, contactLog[base + 4] * 0.3f, contactLog[base + 5] * 0.3f)));
        }
        CollisionGizmos.draw(new PoseStack(), lines, ColorPattern.ORANGE.color, 2);
    }

    @Override
    public void inspectSceneInformation(SceneView sceneView, UIElement container) {
        super.inspectSceneInformation(sceneView, container);
        var info = sceneView.fxObjectInfoView;
        container.addChildren(
                info.createInformation(Component.translatable("photon.gui.editor.fx_info.collision"),
                        () -> Component.literal(collisionScene == null ? "-"
                                : "%d casts, %d tests, %d contacts".formatted(lastCollisionStats[0], lastCollisionStats[1],
                                lastCollisionStats[2]))),
                info.createInformation(Component.translatable("photon.gui.editor.fx_info.collision_time"),
                        () -> Component.literal(collisionScene == null ? "-"
                                : "%.3f ms, %s".formatted(lastCollisionMillis, lastCollisionParallel ? "parallel" : "serial"))),
                info.createInformation(Component.translatable("photon.gui.editor.fx_info.collision_world"),
                        () -> Component.literal(collisionWorld == null ? "-"
                                : "%d sections, %d depenetrated, %d misses".formatted(collisionWorld.sectionCount(),
                                lastCollisionStats[3], lastCollisionStats[4]))));
    }

    @Override
    public void update(float dt) {
        if (!hasFirstUpdate) {
            hasFirstUpdate = true;
            int prewarm = runtime().prewarm.get();
            if (!isLooping()) {
                // prewarm >= duration would age a non-looping emitter to death before its first visible frame
                prewarm = Math.clamp(getLifetime() - 1, 0, prewarm);
            }
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
        updateLightRegistration();
        // calculate distance (scaled by this step's dt)
        accumulatedDistance += getVelocity().length() * dt;
        // emit new particle (maxParticles may be timeline-overridden; authored value is the fallback)
        var maxParticles = runtime().maxParticles.get();
        var particleAmount = getParticleAmount(); // streams all queues — compute once per call
        if (!removed && particleAmount < maxParticles) {
            var emissionCount = runtime().emission.getEmissionCount(this, getRandomSource(), dt);
            var available = Math.min(emissionCount, maxParticles - particleAmount);
            particleBatchCount = Math.max(1, available);
            particleBatchCursor = 0;
            for (int i = 0; i < available; i++) {
                emitParticle(createNewParticle());
            }
        }

        // particles life cycle
        if (!waitToAdded.isEmpty()) {
            for (var p : waitToAdded) {
                // modest initial capacity: maxParticles is timeline-drivable and may be huge
                particles.computeIfAbsent(p.getRenderType(), type -> new ArrayDeque<>(Math.min(maxParticles, 256))).add(p);
            }
            waitToAdded.clear();
        }

        var parallelAllowed = useParallelUpdate();
        var trailScene = blockScene();
        var world = collisionScene != null ? collisionWorld : trailScene != null ? (CollisionWorld) trailScene.blocks : null;
        long updateStart = System.nanoTime();
        for (var queue : particles.values()) {
            if (parallelAllowed && queue.size() >= PARALLEL_UPDATE_MIN_PARTICLES) { // parallel stream for particles tick.
                queue.removeIf(p -> !p.isAlive());
                setParallelLightPhase(true);
                if (world != null) world.beginParallel();
                collisionParallel = true;
                try {
                    queue.parallelStream().forEach(p -> p.updateTick(dt));
                } finally {
                    if (world != null) world.endParallel();
                    setParallelLightPhase(false);
                }
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
        if (parallelAllowed) {
            // refresh every light position requested this tick on the game thread
            // (parallel reads only consult the cache; misses used the last value)
            rebuildLightCache();
        }

        if (collisionScene != null) collisionNanos += System.nanoTime() - updateStart;

        // side effects of the (possibly parallel) particle updates, in particle order
        drainInOrder(pendingSubEmitterSpawns);
        drainCollisionReports();
        dedupeVerdicts.clear();
        // a finished emitter must not keep the entities (and their level) of its last tick
        if (collisionScene != null && particles.values().stream().allMatch(Collection::isEmpty)) collisionScene = null;
    }

    @Override
    protected boolean needsBlockScene() {
        var trails = config.trails;
        return runtime().trails.isEnable() && trails.getTrailType() == TrailsSetting.TrailType.ARA_TRAIL
                && trails.araConfig.physicsSetting.isEnable() && trails.araConfig.physicsSetting.collideWithBlocks;
    }

    /** Parallel particle updates, when enabled; with collision only where {@link CollisionWorld#isParallelSafe} allows. */
    private boolean useParallelUpdate() {
        if (!runtime().parallelUpdate.get()) return false;
        var physics = runtime().physics;
        if ((!physics.isEnable() || !physics.hasCollision()) && !needsBlockScene()) return true;
        var level = getLevel();
        return PhotonConfig.INSTANCE.collisionParallel.get() && CollisionWorld.isParallelSafe(level);
    }

    @Override
    protected boolean clearsLightCacheOnTickBegin() {
        // parallel emitters keep the cache across the tick (reads are cache-only during the
        // parallel phase) and rebuild it at tick end instead
        return !useParallelUpdate();
    }

    @Override
    public boolean isLooping() {
        return runtime().looping.get();
    }

    public void emitParticle(IParticle particle) {
        // it can die while being set up (no ground to snap to)
        if (particle instanceof TileParticle tile && tile.isRemoved()) return;
        waitToAdded.add(particle);
    }

    /** Emission order of the next particle; orders what particle updates defer. */
    public int nextParticleSerial() {
        return particleSerial++;
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
        this.pendingSubEmitterSpawns.clear();
        this.pendingCollisionReports.clear();
        this.collisionScene = null;
        this.simOriginValid = false;
        this.reportedTargets.clear();
        this.dedupeVerdicts.clear();
        this.sourceEntities = null;
        var groups = getScene() == null ? null : GROUP_MEMORY.get(getScene());
        if (groups != null) groups.remove(config.physics.collision.events.getGroup());
        this.collisionStats.reset();
        this.contactLogSize = 0;
        this.contactLogNext = 0;
        this.forceRemoved = false;
        this.particleSerial = 0;
        this.hasFirstUpdate = false;
        this.particleBatchCount = 1;
        this.particleBatchCursor = 0;
        this.accumulatedDistance = 0;
        this.emissionRateAccum = 0;
    }

    @Override
    public boolean useTranslucentPipeline() {
        // slot-or-config layer: honour a per-instance layer override for the pipeline choice too
        return runtime().renderer.getLayer() == RendererSetting.Layer.Translucent;
    }

    /** The render pass this emitter draws through (per-instance override pass, or the shared singleton). */
    public PhotonFXRenderPass effectiveRenderPass() {
        return runtime().effectiveRenderPass();
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {
        if (isVisible()) {
            // the emitter's OWN (tile) particles draw through its single effective pass — the override
            // pass when overridden, else the shared config singleton (equal effective passes merge).
            // Sub-particles spawned into this emitter with a DIFFERENT render type (the Trails module's
            // trail / aratrail particles) keep their OWN pass: piping them through the tile pass would
            // silently drop them, since the tile renderer only emits TileParticles.
            var effectivePass = effectiveRenderPass();
            for (var entry : this.particles.entrySet()) {
                var queue = entry.getValue();
                if (queue.isEmpty()) continue;
                var pass = entry.getKey() == config.particleRenderType ? effectivePass : entry.getKey();
                buffer.pipeQueue(pass, queue);
            }
        }
    }

    @Override
    public int getParticleAmount() {
        // plain loop: called every tick from isAlive/isPlaying — a Stream here is pure allocation churn
        var amount = waitToAdded.size();
        for (var queue : getParticles().values()) {
            amount += queue.size();
        }
        return amount;
    }

    @Override
    @Nullable
    public AABB getCullBox(float partialTicks) {
        // per-emitter cull (slot-or-config); not a batching concern, so it never forces an override pass
        var cull = runtime().renderer.getCull();
        return cull.isEnable() ? cull.getCullAABB(this, partialTicks) : null;
    }

    // one instance: the manager holds providers weakly and removes them by identity
    private final LightProvider lightProvider = this::submitParticleLights;
    @Nullable
    private Level lightLevel;

    private void updateLightRegistration() {
        registerLights(runtime().lightEmission.isEnable() ? getLevel() : null);
    }

    private void registerLights(@Nullable Level level) {
        if (level == lightLevel) return;
        if (lightLevel != null) DynamicLightManager.removeProvider(lightLevel, lightProvider);
        if (level != null) DynamicLightManager.addProvider(level, lightProvider);
        lightLevel = level;
    }

    private void submitParticleLights(LightSink sink, float partialTick) {
        if (!runtime().lightEmission.isEnable() || !isAlive() || !isVisible() || isDiscarded()) return;
        runtime().lightEmission.submit(particles.values(), sink, partialTick);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        registerLights(null);
        collisionScene = null;
        collisionWorld = null;
    }

    @Override
    public void remove(boolean force) {
        super.remove(force);
        if (force) {
            forceRemoved = true;
            registerLights(null);
            particles.clear();
            pendingSubEmitterSpawns.clear();
            pendingCollisionReports.clear();
            collisionScene = null;
            collisionWorld = null;
            if (runtime != null) {
                runtime.clearRenderOverride(); // free the per-instance override pass's GL on force removal
            }
        }
    }

    @Override
    public void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {
        if(scene.sceneView().isShapeVisible()) {
            runtime().shape.drawGuideLines(bufferSource, partialTicks, this);
        }
        if (CollisionDebug.enabled) {
            drawCollisionDebug();
        }
    }

    public int nextParticleBatchIndex() {
        return particleBatchCursor++;
    }

    public int getParticleBatchCount() {
        return particleBatchCount;
    }
}
