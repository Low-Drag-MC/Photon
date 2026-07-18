package com.lowdragmc.photon.client.gameobject.emitter.beam;

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
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataBindings;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import lombok.Getter;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamEmitter
 */
@ParametersAreNonnullByDefault
public class BeamEmitter extends Emitter {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "beam");
    @LDLRegisterClient(name = "beam_emitter", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new BeamEmitter();
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
            if (!(target instanceof BeamEmitter emitter)) {
                return animatableProperties();
            }
            return CustomDataBindings.appendAnimatable(animatableProperties(),
                    emitter.config.additionalGPUDataSetting.customDataStreams(),
                    o -> ((BeamEmitter) o).runtime().customData);
        }

        @Override
        public RuntimeBinding resolveRuntimeBinding(FXObject target, String path) {
            var b = super.resolveRuntimeBinding(target, path);
            return b != null ? b : CustomDataBindings.resolve(path, o -> ((BeamEmitter) o).runtime().customData);
        }
    };

    /** Timeline-animatable config values backed by named {@link BeamRuntime} slots (no map/reflection). */
    public static final List<RuntimeBinding> RUNTIME_BINDINGS = List.of(
            new RuntimeBinding("duration", "ParticleConfig.duration", ConfigValueType.INT,
                    o -> ((BeamEmitter) o).runtime().duration),
            new RuntimeBinding("looping", "ParticleConfig.looping", ConfigValueType.BOOL,
                    o -> ((BeamEmitter) o).runtime().looping),
            new RuntimeBinding("startDelay", "ParticleConfig.startDelay", ConfigValueType.INT,
                    o -> ((BeamEmitter) o).runtime().startDelay),
            new RuntimeBinding("width", "BeamConfig.width", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().width),
            new RuntimeBinding("emitRate", "BeamConfig.emitRate", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().emitRate),
            new RuntimeBinding("color", "BeamConfig.color", ConfigValueType.COLOR,
                    o -> ((BeamEmitter) o).runtime().color),
            new RuntimeBinding("lights.enable", "enable", ConfigValueType.BOOL,
                    o -> ((BeamEmitter) o).runtime().lights.enable),
            new RuntimeBinding("lights.skyLight", "LightOverLifetimeSetting.skyLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().lights.skyLight),
            new RuntimeBinding("lights.blockLight", "LightOverLifetimeSetting.blockLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().lights.blockLight),
            new RuntimeBinding("uvAnimation.enable", "enable", ConfigValueType.BOOL,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.enable),
            new RuntimeBinding("uvAnimation.frameOverTime", "UVAnimationSetting.frameOverTime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.frameOverTime),
            new RuntimeBinding("uvAnimation.startFrame", "UVAnimationSetting.startFrame", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.startFrame),
            new RuntimeBinding("uvAnimation.cycle", "UVAnimationSetting.cycle", ConfigValueType.FLOAT,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.cycle),
            new RuntimeBinding("renderer.writeCustomMask", "photon.emitter.config.renderer.writeCustomMask", ConfigValueType.BOOL,
                    o -> ((BeamEmitter) o).runtime().renderer.writeCustomMask),
            new RuntimeBinding("renderer.maskAlphaCutoff", "photon.emitter.config.renderer.maskAlphaCutoff", ConfigValueType.FLOAT,
                    o -> ((BeamEmitter) o).runtime().renderer.maskAlphaCutoff));

    @Getter
    @Persisted(subPersisted = true)
    protected final BeamConfig config;

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private BeamRuntime runtime;

    // runtime
    protected BeamParticle beamParticle;

    public BeamEmitter() {
        this(new BeamConfig());
    }

    public BeamEmitter(BeamConfig config) {
        this.config = config;
    }

    /** This emitter's per-instance runtime layer (lazily created; timeline overrides live here). */
    public BeamRuntime runtime() {
        if (runtime == null) {
            runtime = new BeamRuntime(config);
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
    public BeamEmitter shallowCopy() {
        return new BeamEmitter(config);
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
    public int getParticleAmount() {
        return beamParticle.isAlive() ? 1 : 0;
    }

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

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////

    @Override
    protected void onTickBegin() {
        super.onTickBegin();
        if (beamParticle != null) beamParticle.syncOrigin();
    }

    @Override
    protected void update(float dt) {
        if (beamParticle.isAlive()) {
            beamParticle.updateTick(dt);
            if(beamParticle.getDelay() > 0) { age = 0; ageF = 0; }
        } else {
            remove();
        }

        super.update(dt);
    }

    @Override
    public float getT() {
        if(beamParticle != null && beamParticle.getDelay() > 0) return 0;
        return super.getT();
    }

    @Override
    public float getT(float partialTicks) {
        if(beamParticle != null && beamParticle.getDelay() > 0) return 0;
        return super.getT(partialTicks);
    }

    @Override
    public void reset() {
        super.reset();
        if (runtime != null) {
            runtime.clear(); // drop timeline overrides; fall back to authored config
        }
        beamParticle = new BeamParticle(this, config);
    }

    /** The render pass this emitter draws through (per-instance override pass, or the shared singleton). */
    public PhotonFXRenderPass effectiveRenderPass() {
        return runtime().effectiveRenderPass();
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {
        if (isVisible()) {
            buffer.pipeQueue(effectiveRenderPass(), Collections.singleton(beamParticle));
        }
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
        super.remove(force);
        beamParticle.setRemoved(true);
        if (force && runtime != null) {
            runtime.clearRenderOverride(); // free the per-instance override pass's GL on force removal
        }
    }
}
