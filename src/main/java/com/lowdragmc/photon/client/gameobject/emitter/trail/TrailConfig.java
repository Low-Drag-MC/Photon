package com.lowdragmc.photon.client.gameobject.emitter.trail;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote TrailConfig
 */
public class TrailConfig implements IConfigurable, IPersistedSerializable {
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
    @Setter
    @Getter
    @Configurable(name = "TrailConfig.time", tips = "photon.emitter.trail.config.time")
    @ConfigNumber(range = {0f, Integer.MAX_VALUE})
    protected int time = 20;
    @Setter
    @Getter
    @Configurable(name = "TrailConfig.minVertexDistance", tips = "photon.emitter.trail.config.minVertexDistance")
    @ConfigNumber(range = {0f, Float.MAX_VALUE})
    protected float minVertexDistance = 0.05f;
    @Getter
    @Configurable(name = "TrailConfig.smoothInterpolation", tips = {
            "photon.emitter.trail.config.smoothInterpolation.0",
            "photon.emitter.trail.config.smoothInterpolation.1",
    })
    protected boolean smoothInterpolation = false;
    @Getter
//    @Configurable(tips = {
//            "photon.emitter.trail.config.calculateSmoothByShader.0",
//            "photon.emitter.trail.config.calculateSmoothByShader.1",
//    })
    protected boolean calculateSmoothByShader = false;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.parallelRendering", tips = {
            "photon.emitter.config.parallelRendering.0",
            "photon.emitter.config.parallelRendering.1"})
    protected boolean parallelRendering = false;
    @Setter
    @Getter
    @Configurable(name = "TrailConfig.uvMode", tips = "photon.emitter.trail.config.uvMode")
    protected TrailParticle.UVMode uvMode = TrailParticle.UVMode.Stretch;
    @Setter
    @Getter
    @Configurable(name = "TrailConfig.widthOverTrail", tips = "photon.emitter.trail.config.widthOverTrail")
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 0.1f}, xAxis = "trail position", yAxis = "width"))
    protected NumberFunction widthOverTrail = NumberFunction.constant(0.2f);
    @Setter
    @Getter
    @Configurable(name = "TrailConfig.colorOverTrail", tips = "photon.emitter.trail.config.colorOverTrail")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverTrail = new Gradient();
    @Getter
    @Configurable(name = "ParticleConfig.renderer", subConfigurable = true, tips = "photon.emitter.config.renderer")
    public final RendererSetting renderer = new RendererSetting();
    @Getter
    @Configurable(name = "ParticleConfig.fixedLight", subConfigurable = true, tips = "photon.emitter.config.lights")
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Getter
    @Configurable(name = "ParticleConfig.uvAnimation", subConfigurable = true, tips = "photon.emitter.config.uvAnimation")
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();

    // runtime
    public final PhotonFXRenderPass particleRenderType = new RenderPass();

    public TrailConfig() {
        renderer.getMaterials().add(new MaterialSetting());
    }

    private class RenderPass extends PhotonFXRenderPass {

        public RenderPass() {
            super(renderer, VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.BLOCK);
        }

        @Override
        public boolean isParallel() {
            return isParallelRendering();
        }

        @Override
        public boolean equals(@Nonnull Object o) {
            return o instanceof RenderPass && super.equals(o);
        }
    }
}
