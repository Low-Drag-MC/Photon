package com.lowdragmc.photon.client.gameobject.emitter.trail;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataBindings;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
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
public class TrailEmitter extends Emitter {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "trail");
    @LDLRegisterClient(name = "trail_emitter", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new TrailEmitter();
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
            if (!(target instanceof TrailEmitter emitter)) {
                return animatableProperties();
            }
            return CustomDataBindings.appendAnimatable(animatableProperties(),
                    emitter.config.additionalGPUDataSetting.customDataStreams(),
                    o -> ((TrailEmitter) o).runtime().customData);
        }

        @Override
        public RuntimeBinding resolveRuntimeBinding(FXObject target, String path) {
            var b = super.resolveRuntimeBinding(target, path);
            return b != null ? b : CustomDataBindings.resolve(path, o -> ((TrailEmitter) o).runtime().customData);
        }
    };

    /** Timeline-animatable config values backed by named {@link TrailRuntime} slots (no map/reflection). */
    public static final List<RuntimeBinding> RUNTIME_BINDINGS = List.of(
            new RuntimeBinding("duration", "ParticleConfig.duration", ConfigValueType.INT,
                    o -> ((TrailEmitter) o).runtime().duration),
            new RuntimeBinding("looping", "ParticleConfig.looping", ConfigValueType.BOOL,
                    o -> ((TrailEmitter) o).runtime().looping),
            new RuntimeBinding("startDelay", "ParticleConfig.startDelay", ConfigValueType.INT,
                    o -> ((TrailEmitter) o).runtime().startDelay),
            new RuntimeBinding("time", "TrailConfig.time", ConfigValueType.INT,
                    o -> ((TrailEmitter) o).runtime().time),
            new RuntimeBinding("minVertexDistance", "TrailConfig.minVertexDistance", ConfigValueType.FLOAT,
                    o -> ((TrailEmitter) o).runtime().minVertexDistance),
            new RuntimeBinding("widthOverTrail", "TrailConfig.widthOverTrail", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((TrailEmitter) o).runtime().widthOverTrail),
            new RuntimeBinding("colorOverTrail", "TrailConfig.colorOverTrail", ConfigValueType.COLOR,
                    o -> ((TrailEmitter) o).runtime().colorOverTrail),
            new RuntimeBinding("lights.enable", "enable", ConfigValueType.BOOL,
                    o -> ((TrailEmitter) o).runtime().lights.enable),
            new RuntimeBinding("lights.skyLight", "LightOverLifetimeSetting.skyLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((TrailEmitter) o).runtime().lights.skyLight),
            new RuntimeBinding("lights.blockLight", "LightOverLifetimeSetting.blockLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((TrailEmitter) o).runtime().lights.blockLight),
            new RuntimeBinding("uvAnimation.enable", "enable", ConfigValueType.BOOL,
                    o -> ((TrailEmitter) o).runtime().uvAnimation.enable),
            new RuntimeBinding("uvAnimation.frameOverTime", "UVAnimationSetting.frameOverTime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((TrailEmitter) o).runtime().uvAnimation.frameOverTime),
            new RuntimeBinding("uvAnimation.startFrame", "UVAnimationSetting.startFrame", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((TrailEmitter) o).runtime().uvAnimation.startFrame),
            new RuntimeBinding("uvAnimation.cycle", "UVAnimationSetting.cycle", ConfigValueType.FLOAT,
                    o -> ((TrailEmitter) o).runtime().uvAnimation.cycle),
            new RuntimeBinding("renderer.writeCustomMask", "photon.emitter.config.renderer.writeCustomMask", ConfigValueType.BOOL,
                    o -> ((TrailEmitter) o).runtime().renderer.writeCustomMask),
            new RuntimeBinding("renderer.maskAlphaCutoff", "photon.emitter.config.renderer.maskAlphaCutoff", ConfigValueType.FLOAT,
                    o -> ((TrailEmitter) o).runtime().renderer.maskAlphaCutoff));

    @Persisted(subPersisted = true)
    public final TrailConfig config;

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private TrailRuntime runtime;

    // runtime
    protected TrailParticle trailParticle;

    public TrailEmitter() {
        this(new TrailConfig());
        config.smoothInterpolation = true;
        config.minVertexDistance = 0.1f;
    }

    public TrailEmitter(TrailConfig config) {
        this.config = config;
    }

    /** This emitter's per-instance runtime layer (lazily created; timeline overrides live here). */
    public TrailRuntime runtime() {
        if (runtime == null) {
            runtime = new TrailRuntime(config);
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
    public TrailEmitter shallowCopy() {
        return new TrailEmitter(config);
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
    public int getStartDelay() {
        return runtime().startDelay.get();
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
    protected void onTickBegin() {
        super.onTickBegin();
        if (trailParticle != null) trailParticle.syncOrigin();
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
        trailParticle = new TrailParticle(this, config);
    }

    /** The render pass this emitter draws through (per-instance override pass, or the shared singleton). */
    public PhotonFXRenderPass effectiveRenderPass() {
        return runtime().effectiveRenderPass();
    }

    /** Lazily built CPU ribbon renderer. Transient: render-only state, never persisted or copied. */
    @Nullable
    private transient com.lowdragmc.photon.client.gameobject.particle.renderer.TrailParticleRenderer extractRenderer;

    @Override
    public com.mojang.blaze3d.vertex.VertexFormat.Mode geometryMode() {
        return com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLE_STRIP; // 1.21 TrailConfig.RenderPass
    }

    @Override
    public com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting.Runtime rendererRuntime() {
        return runtime().renderer;
    }

    @Override
    public void extractBatches(com.lowdragmc.photon.client.render.PhotonFXRenderState state,
                               net.minecraft.client.Camera camera, float partialTicks) {
        var setting = config.additionalGPUDataSetting;
        if (runtime().renderer.isUseGPUInstance()) {
            if (extractRenderer == null) {
                extractRenderer = new com.lowdragmc.photon.client.gameobject.particle.renderer.TrailParticleRenderer();
            }
            var tails = trailParticle.getTails().size();
            if (extractInstancedGroup(camera, rendererRuntime(), setting,
                    com.lowdragmc.photon.client.render.PhotonPipelines.InstancedVariant.TRAIL,
                    BaseMesh.quads(com.lowdragmc.photon.client.render.PhotonWorldRenderState.segmentQuad(), 6),
                    (tails + 1) * 4, (tails + 4) * 12,
                    new org.joml.Vector3f(com.lowdragmc.photon.client.render.PhotonCameraUtils.facingEye(camera)).sub(com.lowdragmc.photon.client.render.PhotonCameraUtils.renderOrigin(camera)),
                    (instances, points, data, custom) -> extractRenderer.fillInstances(
                            Collections.singleton(trailParticle), camera, partialTicks, instances, points,
                            setting, data, custom))) {
                return;
            }
        }
        super.extractBatches(state, camera, partialTicks);
    }

    @Override
    protected void bakeGeometry(com.mojang.blaze3d.vertex.VertexConsumer geometry,
                                net.minecraft.client.Camera camera, float partialTicks) {
        if (extractRenderer == null) {
            extractRenderer = new com.lowdragmc.photon.client.gameobject.particle.renderer.TrailParticleRenderer();
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
            trailParticle.getTails().clear();
            if (runtime != null) {
                runtime.clearRenderOverride(); // free the per-instance override pass's GL on force removal
            }
        }
    }
}
