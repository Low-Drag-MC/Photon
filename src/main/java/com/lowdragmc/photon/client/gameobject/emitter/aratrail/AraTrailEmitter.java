package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
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
                    o -> ((AraTrailEmitter) o).runtime().physics.damping));

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

    @Override
    public boolean useTranslucentPipeline() {
        return config.renderer.getLayer() == RendererSetting.Layer.Translucent;
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {
        if (isVisible()) {
            buffer.pipeQueue(trailParticle.getRenderType(), Collections.singleton(trailParticle));
        }
    }

    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    @Override
    @Nullable
    public AABB getCullBox(float partialTicks) {
        return config.renderer.getCull().isEnable() ? config.renderer.getCull().getCullAABB(this, partialTicks) : null;
    }

    @Override
    public void remove(boolean force) {
        trailParticle.setRemoved(true);
        super.remove(force);
        if (force) {
            trailParticle.clear();
        }
    }
}
