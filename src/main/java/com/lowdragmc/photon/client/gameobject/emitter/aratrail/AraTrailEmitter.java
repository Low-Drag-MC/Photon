package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataBindings;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collections;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/6
 * @implNote TrailEmitter
 */
@ParametersAreNonnullByDefault
public class AraTrailEmitter extends Emitter {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "trail");
    @LDLRegisterClient(name = "ara_trail_emitter", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new AraTrailEmitter();
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
            if (!(target instanceof AraTrailEmitter emitter)) {
                return animatableProperties();
            }
            return CustomDataBindings.appendAnimatable(animatableProperties(),
                    emitter.config.additionalGPUDataSetting.customDataStreams(),
                    o -> ((AraTrailEmitter) o).runtime().customData);
        }

        @Override
        public RuntimeBinding resolveRuntimeBinding(FXObject target, String path) {
            var b = super.resolveRuntimeBinding(target, path);
            return b != null ? b : CustomDataBindings.resolve(path, o -> ((AraTrailEmitter) o).runtime().customData);
        }
    };

    /** Timeline-animatable config values backed by named {@link AraTrailRuntime} slots (no map/reflection). */
    public static final List<RuntimeBinding> RUNTIME_BINDINGS = List.of(
            new RuntimeBinding("duration", "ParticleConfig.duration", ConfigValueType.INT,
                    o -> ((AraTrailEmitter) o).runtime().duration),
            new RuntimeBinding("looping", "ParticleConfig.looping", ConfigValueType.BOOL,
                    o -> ((AraTrailEmitter) o).runtime().looping),
            new RuntimeBinding("thickness", "AraTrails.thickness", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().thickness),
            new RuntimeBinding("thicknessOverLength", "AraTrails.thicknessOverLength", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((AraTrailEmitter) o).runtime().thicknessOverLength),
            new RuntimeBinding("colorOverLength", "AraTrails.colorOverLength", ConfigValueType.COLOR,
                    o -> ((AraTrailEmitter) o).runtime().colorOverLength),
            new RuntimeBinding("thicknessOverTime", "AraTrails.thicknessOverTime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((AraTrailEmitter) o).runtime().thicknessOverTime),
            new RuntimeBinding("thicknessOverSegmentTime", "AraTrails.thicknessOverSegmentTime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((AraTrailEmitter) o).runtime().thicknessOverSegmentTime),
            new RuntimeBinding("colorOverTime", "AraTrails.colorOverTime", ConfigValueType.COLOR,
                    o -> ((AraTrailEmitter) o).runtime().colorOverTime),
            new RuntimeBinding("colorOverSegmentTime", "AraTrails.colorOverSegmentTime", ConfigValueType.COLOR,
                    o -> ((AraTrailEmitter) o).runtime().colorOverSegmentTime),
            new RuntimeBinding("initialThickness", "AraTrails.initialThickness", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().initialThickness),
            new RuntimeBinding("time", "AraTrails.time", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().time),
            new RuntimeBinding("minDistance", "AraTrails.minDistance", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().minDistance),
            new RuntimeBinding("timeInterval", "AraTrails.minSpawnTime", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().timeInterval),
            new RuntimeBinding("physicsSetting.enable", "enable", ConfigValueType.BOOL,
                    o -> ((AraTrailEmitter) o).runtime().physics.enable),
            new RuntimeBinding("physicsSetting.warmup", "AraTrails.warmup", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().physics.warmup),
            new RuntimeBinding("physicsSetting.inertia", "AraTrails.inertia", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().physics.inertia),
            new RuntimeBinding("physicsSetting.velocitySmoothing", "AraTrails.velocitySmoothing", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().physics.velocitySmoothing),
            new RuntimeBinding("physicsSetting.damping", "AraTrails.damping", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().physics.damping),
            new RuntimeBinding("renderer.writeCustomMask", "photon.emitter.config.renderer.writeCustomMask", ConfigValueType.BOOL,
                    o -> ((AraTrailEmitter) o).runtime().renderer.writeCustomMask),
            new RuntimeBinding("renderer.maskAlphaCutoff", "photon.emitter.config.renderer.maskAlphaCutoff", ConfigValueType.FLOAT,
                    o -> ((AraTrailEmitter) o).runtime().renderer.maskAlphaCutoff));

    @Persisted(subPersisted = true)
    public final AraTrailConfig config;

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private AraTrailRuntime runtime;

    // runtime
    protected AraTrailParticle trailParticle;

    public AraTrailEmitter() {
        this(new AraTrailConfig());
    }

    public AraTrailEmitter(AraTrailConfig config) {
        this.config = config;
    }

    /** This emitter's per-instance runtime layer (lazily created; timeline overrides live here). */
    public AraTrailRuntime runtime() {
        if (runtime == null) {
            runtime = new AraTrailRuntime(config);
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
    public AraTrailEmitter shallowCopy() {
        return new AraTrailEmitter(config);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        config.buildConfigurator(father);
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////

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
    public boolean isLooping() {
        return runtime().looping.get();
    }

    @Override
    public int getParticleAmount() {
        return trailParticle.isAlive() ? 1 : 0;
    }

    @Override
    protected void update(float dt) {
        if (trailParticle.isAlive()) {
            trailParticle.updateTick(dt);
        } else {
            remove();
        }

        super.update(dt);
    }

    @Override
    public void reset() {
        super.reset();
        if (runtime != null) {
            runtime.clear(); // drop timeline overrides; fall back to authored config
        }
        trailParticle = new AraTrailParticle(this, config);
    }

    /** The render pass this emitter draws through (per-instance override pass, or the shared singleton). */
    public PhotonFXRenderPass effectiveRenderPass() {
        return runtime().effectiveRenderPass();
    }

    /** Lazily built CPU ribbon renderer. Transient: render-only state, never persisted or copied. */
    @Nullable
    private transient com.lowdragmc.photon.client.gameobject.particle.renderer.AraTrailParticleRenderer extractRenderer;

    @Override
    public com.mojang.blaze3d.vertex.VertexFormat.Mode geometryMode() {
        return com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES; // 1.21 AraTrailConfig.RenderPass
    }

    @Override
    public com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting.Runtime rendererRuntime() {
        return runtime().renderer;
    }

    @Override
    public void extractBatches(com.lowdragmc.photon.client.render.PhotonFXRenderState state,
                               net.minecraft.client.Camera camera, float partialTicks) {
        var setting = config.additionalGPUDataSetting;
        // 1.21's gate: the tube instances too; only high-quality corners on a non-Local FLAT ribbon
        // stay on the CPU (they emit a data-dependent fan topology no fixed base mesh can express).
        var tube = config.section.isEnable();
        if (runtime().renderer.isUseGPUInstance()
                && (tube || !(config.highQualityCorners && config.alignment != AraTrailConfig.TrailAlignment.Local))) {
            if (extractRenderer == null) {
                extractRenderer = new com.lowdragmc.photon.client.gameobject.particle.renderer.AraTrailParticleRenderer(config);
            }
            var pointCapacity = trailParticle.getPoints().size() * Math.max(1, config.smoothness) + 2;
            var mesh = tube ? extractRenderer.tubeMesh()
                    : BaseMesh.quads(com.lowdragmc.photon.client.render.PhotonWorldRenderState.araQuad(), 6);
            if (mesh != null && extractInstancedGroup(camera, rendererRuntime(), setting,
                    tube ? com.lowdragmc.photon.client.render.PhotonPipelines.InstancedVariant.ARA_TUBE
                            : com.lowdragmc.photon.client.render.PhotonPipelines.InstancedVariant.ARA,
                    mesh,
                    pointCapacity * (tube ? 1 : 3), pointCapacity * 16,
                    new org.joml.Vector3f(),
                    (instances, points, data, custom) -> extractRenderer.fillInstances(
                            Collections.singleton(trailParticle), camera, partialTicks, tube, instances, points,
                            data, custom))) {
                return;
            }
        }
        super.extractBatches(state, camera, partialTicks);
    }

    @Override
    protected void bakeGeometry(com.mojang.blaze3d.vertex.VertexConsumer geometry,
                                net.minecraft.client.Camera camera, float partialTicks) {
        if (extractRenderer == null) {
            extractRenderer = new com.lowdragmc.photon.client.gameobject.particle.renderer.AraTrailParticleRenderer(config);
        }
        extractRenderer.renderQueue(geometry, Collections.singleton(trailParticle), camera, partialTicks);
    }

    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    @Override
    @Nullable
    public AABB getCullBox(float partialTicks) {
        var cull = runtime().renderer.getCull();
        return cull.isEnable() ? cull.getCullAABB(this, partialTicks) : null;
    }

    @Override
    public void remove(boolean force) {
        trailParticle.setRemoved(true);
        super.remove(force);
        if (force) {
            trailParticle.clear();
            if (runtime != null) {
                runtime.clearRenderOverride(); // free the per-instance override pass's GL on force removal
            }
        }
    }
}
