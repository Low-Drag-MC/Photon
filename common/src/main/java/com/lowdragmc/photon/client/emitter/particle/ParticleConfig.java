package com.lowdragmc.photon.client.emitter.particle;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.photon.client.emitter.data.*;
import com.lowdragmc.photon.client.emitter.data.number.*;
import com.lowdragmc.photon.client.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote ParticleConfig
 */
public class ParticleConfig {
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.duration")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.looping")
    protected boolean looping = true;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.startDelay")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, min = 0, curveConfig = @CurveConfig(bound = {0, 100}, xAxis = "duration", yAxis = "delay"))
    protected NumberFunction startDelay = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.startLifetime")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, min = 0, defaultValue = 100, curveConfig = @CurveConfig(bound = {0, 200}, xAxis = "duration", yAxis = "life time"))
    protected NumberFunction startLifetime = NumberFunction.constant(100);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.startSpeed")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "duration", yAxis = "speed"))
    protected NumberFunction startSpeed = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.startSize")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "size"))
    protected NumberFunction startSize = NumberFunction.constant(0.1f);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.startRotation")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "duration", yAxis = "rotation")))
    protected NumberFunction3 startRotation = new NumberFunction3(0, 0, 0);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.startColor")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction startColor = NumberFunction.color(-1);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.maxParticles")
    @NumberRange(range = {0, 100000}, wheel = 100)
    protected int maxParticles = 2000;
    @Setter
    @Getter
    @Configurable(tips = {"photon.emitter.config.parallelUpdate.0",
            "photon.emitter.config.parallelUpdate.1"})
    protected boolean parallelUpdate = false;
    @Setter
    @Getter
    @Configurable(tips = {"photon.emitter.config.parallelRendering.0",
            "photon.emitter.config.parallelRendering.1"})
    protected boolean parallelRendering = false;
    @Getter
    @Configurable(name = "Emission", subConfigurable = true, tips = "photon.emitter.config.emission")
    protected EmissionSetting emission = new EmissionSetting();
    @Getter
    @Configurable(name = "Shape", subConfigurable = true, tips = "photon.emitter.config.shape")
    protected ShapeSetting shape = new ShapeSetting();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "photon.emitter.config.material")
    protected MaterialSetting material = new MaterialSetting();
    @Getter
    @Configurable(name = "Renderer", subConfigurable = true, tips = "photon.emitter.config.renderer")
    protected RendererSetting renderer = new RendererSetting();
    @Getter
    @Configurable(name = "Physics", subConfigurable = true, tips = "photon.emitter.config.physics")
    protected PhysicsSetting physics = new PhysicsSetting();
    @Getter
    @Configurable(name = "Fixed Light", subConfigurable = true, tips = "photon.emitter.config.lights")
    protected LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Getter
    @Configurable(name = "Velocity over Lifetime", subConfigurable = true, tips = "photon.emitter.config.velocityOverLifetime")
    protected final VelocityOverLifetimeSetting velocityOverLifetime = new VelocityOverLifetimeSetting();
    @Getter
    @Configurable(name = "Inherit Velocity", subConfigurable = true, tips = "photon.emitter.config.inheritVelocity")
    protected final InheritVelocitySetting inheritVelocity = new InheritVelocitySetting();
    @Getter
    @Configurable(name = "Lifetime by Emitter Speed", subConfigurable = true, tips = "photon.emitter.config.lifetimeByEmitterSpeed")
    protected final LifetimeByEmitterSpeedSetting lifetimeByEmitterSpeed = new LifetimeByEmitterSpeedSetting();
    @Getter
    @Configurable(name = "Force over Lifetime", subConfigurable = true, tips = "photon.emitter.config.forceOverLifetime")
    protected final ForceOverLifetimeSetting forceOverLifetime = new ForceOverLifetimeSetting();
    @Getter
    @Configurable(name = "Color over Lifetime", subConfigurable = true, tips = "photon.emitter.config.colorOverLifetime")
    protected final ColorOverLifetimeSetting colorOverLifetime = new ColorOverLifetimeSetting();
    @Getter
    @Configurable(name = "Color by Speed", subConfigurable = true, tips = "photon.emitter.config.colorBySpeed")
    protected final ColorBySpeedSetting colorBySpeed = new ColorBySpeedSetting();
    @Getter
    @Configurable(name = "Size over Lifetime", subConfigurable = true, tips = "photon.emitter.config.sizeOverLifetime")
    protected final SizeOverLifetimeSetting sizeOverLifetime = new SizeOverLifetimeSetting();
    @Getter
    @Configurable(name = "Size by Speed", subConfigurable = true, tips = "photon.emitter.config.sizeBySpeed")
    protected final SizeBySpeedSetting sizeBySpeed = new SizeBySpeedSetting();
    @Getter
    @Configurable(name = "Rotation over Lifetime", subConfigurable = true, tips = "photon.emitter.config.rotationOverLifetime")
    protected final RotationOverLifetimeSetting rotationOverLifetime = new RotationOverLifetimeSetting();
    @Getter
    @Configurable(name = "Rotation by Speed", subConfigurable = true, tips = "photon.emitter.config.rotationBySpeed")
    protected final RotationBySpeedSetting rotationBySpeed = new RotationBySpeedSetting();
    @Getter
    @Configurable(name = "Noise", subConfigurable = true, tips = "photon.emitter.config.noise")
    protected final NoiseSetting noise = new NoiseSetting();
    @Getter
    @Configurable(name = "UV Animation", subConfigurable = true, tips = "photon.emitter.config.uvAnimation")
    protected final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Getter
    @Configurable(name = "Trails", subConfigurable = true, tips = "photon.emitter.config.trails")
    protected final TrailsSetting trails = new TrailsSetting(this);
    @Getter
    @Configurable(name = "Sub Emitters", subConfigurable = true, tips = "photon.emitter.config.sub_emitters")
    protected final SubEmittersSetting subEmitters = new SubEmittersSetting();

    public ParticleConfig() {
    }

}
