package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.google.common.collect.Queues;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataBindings;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldObject;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.SpawnFrame;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailConfig;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle;
import com.lowdragmc.photon.client.gameobject.particle.renderer.AraTrailParticleRenderer;
import com.lowdragmc.photon.client.gameobject.particle.renderer.TileParticleRenderer;
import com.lowdragmc.photon.client.gameobject.particle.renderer.TrailParticleRenderer;
import com.lowdragmc.photon.client.render.PhotonCameraUtils;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonViewSettings;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

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
        activeForceFields = gatherForceFields();
        for (var queue : particles.values()) {
            for (var particle : queue) {
                particle.syncOrigin();
            }
        }
    }

    /** Immutable per-tick snapshot of the force fields affecting this emitter (read concurrently
     *  by parallel particle updates; see {@link #gatherForceFields}). */
    private List<ForceFieldObject> activeForceFields = List.of();

    /** Sub-emitter spawns scheduled from particle updateTick (possibly on parallelStream workers);
     *  drained on the game thread at the end of {@link #emitParticle(float)}, same tick. */
    private final Queue<Runnable> pendingSubEmitterSpawns = new ConcurrentLinkedQueue<>();

    public void scheduleSubEmitterSpawn(Runnable spawn) {
        pendingSubEmitterSpawns.add(spawn);
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

    @Override
    public void update(float dt) {
        if (!hasFirstUpdate) {
            hasFirstUpdate = true;
            int prewarm = runtime().prewarm.get();
            if (!isLooping()) {
                // prewarm >= duration would age a non-looping emitter to death before its first visible frame
                prewarm = Math.min(prewarm, Math.max(getLifetime() - 1, 0));
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
        for (var queue : particles.values()) {
            if (parallelAllowed && queue.size() >= PARALLEL_UPDATE_MIN_PARTICLES) { // parallel stream for particles tick.
                queue.removeIf(p -> !p.isAlive());
                setParallelLightPhase(true);
                try {
                    queue.parallelStream().forEach(p -> p.updateTick(dt));
                } finally {
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

        // drain sub-emitter spawns collected during (possibly parallel) particle updates
        for (Runnable spawn; (spawn = pendingSubEmitterSpawns.poll()) != null; ) {
            spawn.run();
        }
    }

    /** Parallel particle updates are allowed when enabled and collision is off (level reads). */
    private boolean useParallelUpdate() {
        return runtime().parallelUpdate.get() && (!runtime().physics.isEnable() || !runtime().physics.hasCollision());
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
        this.pendingSubEmitterSpawns.clear();
        this.hasFirstUpdate = false;
        this.particleBatchCount = 1;
        this.particleBatchCursor = 0;
        this.accumulatedDistance = 0;
        this.emissionRateAccum = 0;
    }

    /** The render pass this emitter draws through (per-instance override pass, or the shared singleton). */
    public PhotonFXRenderPass effectiveRenderPass() {
        return runtime().effectiveRenderPass();
    }

    /** Lazily built CPU renderers (were owned by the 1.21 RenderPasses): quads for the tiles, ribbon/
     *  triangle renderers for the embedded per-particle trails. Transient render-only state. */
    @Nullable
    private transient TileParticleRenderer extractRenderer;
    @Nullable
    private transient TrailParticleRenderer trailExtractRenderer;
    @Nullable
    private transient AraTrailParticleRenderer araTrailExtractRenderer;

    @Override
    public com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting.Runtime rendererRuntime() {
        return runtime().renderer;
    }

    @Override
    protected void bakeBatches(PhotonViewSettings settings,
                               java.util.List<PhotonWorldRenderState.DrawJob> out,
                               Camera camera, float partialTicks) {
        // GPU-instanced tile quads when the config qualifies; CPU baking otherwise
        if (!(canInstanceTiles() && extractInstancedTiles(settings, out, camera, partialTicks))) {
            super.bakeBatches(settings, out, camera, partialTicks); // the tile quads (CPU)
        }
        // The embedded per-particle trails (TrailsSetting) are separate geometry groups: their own
        // sub-config renderer/materials and primitive mode, mirroring TrailEmitter/AraTrailEmitter.
        // Queues are homogeneous (keyed by the spawning config's render pass), so the head's type
        // identifies each queue's group.
        var trails = config.trails;
        if (hasParticlesOf(TrailParticle.class)) {
            if (trailExtractRenderer == null) {
                trailExtractRenderer = new TrailParticleRenderer();
            }
            var s = trails.config.additionalGPUDataSetting;
            var instanced = trails.config.defaultRenderRuntime.isUseGPUInstance();
            if (instanced) {
                // capacity is PER TRAIL: each writes up to tails+1 segments and tails+3 points
                // (pushed head + 2 pads) — the 1.21 pre-scan bounds
                var tails = 0;
                var trailCount = 0;
                for (var queue : particles.values()) {
                    if (queue.peek() instanceof TrailParticle) {
                        for (var p : queue) {
                            tails += ((TrailParticle) p).getTails().size();
                            trailCount++;
                        }
                    }
                }
                var offset = new Vector3f(PhotonCameraUtils.facingEye(camera))
                        .sub(PhotonCameraUtils.renderOrigin(camera));
                instanced = bakeInstancedGroup(settings, out, camera, trails.config.defaultRenderRuntime, s,
                        PhotonPipelines.InstancedVariant.TRAIL,
                        BaseMesh.quads(PhotonWorldRenderState.segmentQuad(), 6),
                        (tails + trailCount) * 4, (tails + 3 * trailCount) * 12, offset,
                        (instances, points, data, custom) -> {
                            var count = 0;
                            for (var queue : particles.values()) {
                                if (queue.peek() instanceof TrailParticle) {
                                    count += trailExtractRenderer.fillInstances(queue, camera, partialTicks,
                                            instances, points, s, data, custom);
                                }
                            }
                            return count;
                        });
            }
            if (!instanced) {
                bakeGroup(settings, out, camera, partialTicks, trails.config.defaultRenderRuntime,
                        VertexFormat.Mode.TRIANGLE_STRIP, (geometry, cam, pt) ->
                                renderQueuesOf(TrailParticle.class,
                                        queue -> trailExtractRenderer.renderQueue(geometry, queue, cam, pt)));
            }
        }
        if (hasParticlesOf(AraTrailParticle.class)) {
            if (araTrailExtractRenderer == null) {
                araTrailExtractRenderer = new AraTrailParticleRenderer(trails.araConfig);
            }
            var s = trails.araConfig.additionalGPUDataSetting;
            var araTube = trails.araConfig.section.isEnable();
            var instanced = trails.araConfig.defaultRenderRuntime.isUseGPUInstance()
                    && (araTube || !(trails.araConfig.highQualityCorners
                            && trails.araConfig.alignment != AraTrailConfig.TrailAlignment.Local));
            if (instanced) {
                var pointCapacity = 2;
                for (var queue : particles.values()) {
                    if (queue.peek() instanceof AraTrailParticle) {
                        for (var p : queue) {
                            pointCapacity += ((AraTrailParticle) p).getPoints().size()
                                    * Math.max(1, trails.araConfig.smoothness) + 2;
                        }
                    }
                }
                var araMesh = araTube ? araTrailExtractRenderer.tubeMesh()
                        : BaseMesh.quads(PhotonWorldRenderState.araQuad(), 6);
                instanced = araMesh != null
                        && bakeInstancedGroup(settings, out, camera, trails.araConfig.defaultRenderRuntime, s,
                        araTube ? PhotonPipelines.InstancedVariant.ARA_TUBE : PhotonPipelines.InstancedVariant.ARA,
                        araMesh,
                        pointCapacity * (araTube ? 1 : 3), pointCapacity * 16, new Vector3f(),
                        (instances, points, data, custom) -> {
                            var count = 0;
                            for (var queue : particles.values()) {
                                if (queue.peek() instanceof AraTrailParticle) {
                                    count += araTrailExtractRenderer.fillInstances(queue, camera, partialTicks,
                                            araTube, instances, points, data, custom);
                                }
                            }
                            return count;
                        });
            }
            if (!instanced) {
                bakeGroup(settings, out, camera, partialTicks, trails.araConfig.defaultRenderRuntime,
                        VertexFormat.Mode.TRIANGLES, (geometry, cam, pt) ->
                                renderQueuesOf(AraTrailParticle.class,
                                        queue -> araTrailExtractRenderer.renderQueue(geometry, queue, cam, pt)));
            }
        }
    }

    /**
     * Instanced eligibility: billboard modes + Model, no additional-GPU-data streams (their
     * layouts change the instance stride / need extra texel buffers — CPU fallback keeps them
     * correct until a follow-up phase ports them).
     */
    private boolean canInstanceTiles() {
        var renderer = runtime().renderer;
        var setting = config.additionalGPUDataSetting;
        return renderer.isUseGPUInstance()
                && renderer.getRenderMode() != ParticleRendererSetting.Mode.None;
    }

    /** Extract the tiles as instanced draws (billboard quad or baked model mesh). */
    private boolean extractInstancedTiles(com.lowdragmc.photon.client.render.PhotonViewSettings settings,
                                          java.util.List<PhotonWorldRenderState.DrawJob> out,
                                          Camera camera, float partialTicks) {
        if (extractRenderer == null) {
            extractRenderer = new TileParticleRenderer(runtime().renderer);
        }
        var tileCount = 0;
        for (var queue : particles.values()) {
            if (queue.peek() instanceof TileParticle) {
                tileCount += queue.size();
            }
        }
        var model = runtime().renderer.getRenderMode() == ParticleRendererSetting.Mode.Model;
        com.mojang.blaze3d.buffers.GpuBuffer vertices;
        int indexCount;
        if (model) {
            vertices = extractRenderer.modelMeshBuffer();
            indexCount = extractRenderer.modelIndexCount();
            if (vertices == null || indexCount == 0) {
                return false; // no mesh — CPU path renders the fallback
            }
        } else {
            vertices = PhotonWorldRenderState.tileQuad();
            indexCount = 6;
        }
        var floats = model ? TileParticleRenderer.MODEL_INSTANCE_FLOATS : TileParticleRenderer.INSTANCE_FLOATS;
        return bakeInstancedGroup(settings, out, camera, runtime().renderer, config.additionalGPUDataSetting,
                model ? PhotonPipelines.InstancedVariant.MODEL : PhotonPipelines.InstancedVariant.TILE,
                BaseMesh.quads(vertices, indexCount), tileCount * floats, 0, new org.joml.Vector3f(),
                (instances, points, data, custom) -> {
                    var count = 0;
                    var setting = config.additionalGPUDataSetting;
                    for (var queue : particles.values()) {
                        if (queue.peek() instanceof TileParticle) {
                            count += model
                                    ? extractRenderer.fillInstancesModel(queue, camera, partialTicks, instances,
                                            setting, data, custom)
                                    : extractRenderer.fillInstances(queue, camera, partialTicks, instances,
                                            setting, data, custom);
                        }
                    }
                    return count;
                });
    }

    private boolean hasParticlesOf(Class<?> type) {
        for (var queue : particles.values()) {
            if (type.isInstance(queue.peek())) {
                return true;
            }
        }
        return false;
    }

    private void renderQueuesOf(Class<?> type, java.util.function.Consumer<Queue<IParticle>> render) {
        for (var queue : particles.values()) {
            if (type.isInstance(queue.peek())) {
                render.accept(queue);
            }
        }
    }

    @Override
    protected void bakeGeometry(VertexConsumer geometry, Camera camera, float partialTicks) {
        if (extractRenderer == null) {
            extractRenderer = new TileParticleRenderer(runtime().renderer);
        }
        for (var queue : this.particles.values()) {
            // tiles only — trail queues extract as their own groups (see extractBatches)
            if (queue.peek() instanceof TileParticle) {
                extractRenderer.renderQueue(geometry, queue, camera, partialTicks);
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

    @Override
    public void remove(boolean force) {
        super.remove(force);
        if (force) {
            particles.clear();
            pendingSubEmitterSpawns.clear();
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
    }

    public int nextParticleBatchIndex() {
        return particleBatchCursor++;
    }

    public int getParticleBatchCount() {
        return particleBatchCount;
    }
}
