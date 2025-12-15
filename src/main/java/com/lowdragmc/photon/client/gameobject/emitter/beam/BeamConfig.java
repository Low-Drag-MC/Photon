package com.lowdragmc.photon.client.gameobject.emitter.beam;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.accessors.EnumAccessor;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.ClipContext;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Optional;

/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamConfig
 */
public class BeamConfig implements IConfigurable, IPersistedSerializable {
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.duration", tips = "photon.emitter.config.duration")
    @ConfigNumber(range = {1, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.looping", tips = "photon.emitter.config.looping")
    protected boolean looping = true;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.startDelay", tips = "photon.emitter.config.startDelay")
    @ConfigNumber(range = {0, Integer.MAX_VALUE})
    protected int startDelay = 0;
    @Getter
    @Configurable(name = "BeamConfig.end", tips = "photon.emitter.beam.config.end")
    @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    protected Vector3f end = new Vector3f(0, 0, -3);
    @Setter
    @Getter
    @Configurable(name = "BeamConfig.width", tips = "photon.emitter.beam.config.width")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction width = NumberFunction.constant(0.2);
    @Setter
    @Getter
    @Configurable(name = "BeamConfig.emitRate", tips = "photon.emitter.beam.config.emitRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction emitRate = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(name = "BeamConfig.raycast", tips = "photon.emitter.beam.config.raycast")
    @ConfigSelector(subConfiguratorBuilder = "raycastSubConfiguratorBuilder")
    protected RaycastMode raycast = RaycastMode.NONE;
    @Setter
    @Getter
    @Persisted
    protected ClipContext.Block raycastBlockMode = ClipContext.Block.VISUAL;
    @Setter
    @Getter
    @Persisted
    protected ClipContext.Fluid raycastFluidMode = ClipContext.Fluid.NONE;
    @Setter
    @Getter
    @Configurable(name = "BeamConfig.color", tips = "photon.emitter.beam.config.color")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Color();
    @Getter
    @Configurable(name = "ParticleConfig.renderer", subConfigurable = true, tips = "photon.emitter.config.renderer")
    public final RendererSetting renderer = new RendererSetting();
    @Configurable(name = "ParticleConfig.uvAnimation", subConfigurable = true, tips = "photon.emitter.config.uvAnimation")
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Getter
    @Configurable(name = "ParticleConfig.fixedLight", subConfigurable = true, tips = "photon.emitter.config.lights")
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();

    public enum RaycastMode {
        NONE,
        BLOCKS,
        ENTITIES,
        BLOCKS_AND_ENTITIES;
    }

    // runtime
    public final PhotonFXRenderPass particleRenderType = new RenderPass();

    public BeamConfig() {
        renderer.getMaterials().add(new MaterialSetting(Optional.ofNullable(MaterialResource.INSTANCE.getResourceInstance()
                .getResource(new BuiltinPath("laser"))).orElseGet(TextureMaterial::new)));
    }

    private void raycastSubConfiguratorBuilder(RaycastMode mode, ConfiguratorGroup group) {
        if (mode == RaycastMode.BLOCKS || mode == RaycastMode.BLOCKS_AND_ENTITIES) {
            group.addConfigurators(
                    EnumAccessor.create("BeamConfig.raycast.block",
                        Arrays.stream(ClipContext.Block.values()).toList(),
                        this::getRaycastBlockMode, this::setRaycastBlockMode, ClipContext.Block.VISUAL, true)
                            .setTips("photon.emitter.beam.config.raycast.block"),
                    EnumAccessor.create("BeamConfig.raycast.fluid",
                            Arrays.stream(ClipContext.Fluid.values()).toList(),
                            this::getRaycastFluidMode, this::setRaycastFluidMode, ClipContext.Fluid.NONE, true)
                            .setTips( "photon.emitter.beam.config.raycast.fluid")
            );
        }
    }

    private class RenderPass extends PhotonFXRenderPass {

        public RenderPass() {
            super(renderer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        }


        @Override
        public boolean equals(@Nonnull Object o) {
            return o instanceof RenderPass && super.equals(o);
        }

    }
}
