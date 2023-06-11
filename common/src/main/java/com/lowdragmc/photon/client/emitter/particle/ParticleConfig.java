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
    @Configurable(tips = "The length of time (tick) the Particle Emitter is emitting particles. If the system is looping, this indicates the length of one cycle.")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(tips = "If true, the emission cycle will repeat after the duration.")
    protected boolean looping = true;
    @Setter
    @Getter
    @Configurable(tips = "Delay in seconds that this Particle System will wait before emitting particles.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, min = 0, curveConfig = @CurveConfig(bound = {0, 100}, xAxis = "duration", yAxis = "delay"))
    protected NumberFunction startDelay = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "Start lifetime in ticks, particle will die when its lifetime reaches 0.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, isDecimals = false, min = 0, defaultValue = 100, curveConfig = @CurveConfig(bound = {0, 200}, xAxis = "duration", yAxis = "life time"))
    protected NumberFunction startLifetime = NumberFunction.constant(100);
    @Setter
    @Getter
    @Configurable(tips = "The start speed of particles, applied in the starting direction.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "duration", yAxis = "speed"))
    protected NumberFunction startSpeed = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "The start size of particles.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "size"))
    protected NumberFunction startSize = NumberFunction.constant(0.1f);
    @Setter
    @Getter
    @Configurable(tips = "The start rotation of particles in degrees. (x-roll, y-pitch, z-yaw)")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "duration", yAxis = "rotation")))
    protected NumberFunction3 startRotation = new NumberFunction3(0, 0, 0);
    @Setter
    @Getter
    @Configurable(tips = "The start color of particles.")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction startColor = NumberFunction.color(-1);
    @Setter
    @Getter
    @Configurable(tips = "The number of particles in the system will be limited by this number. Emission will be temporarily halted if this is reached")
    @NumberRange(range = {0, 10000})
    protected int maxParticles = 2000;
    @Getter
    @Configurable(name = "Emission", subConfigurable = true, tips = "Emission of the emitter. This controls the rate at which particles are emitted as well as burst emissions.")
    protected EmissionSetting emission = new EmissionSetting();
    @Getter
    @Configurable(name = "Shape", subConfigurable = true, tips = "Shape of the emitter volume, which controls where particles are emitted and their initial direction.")
    protected ShapeSetting shape = new ShapeSetting();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "Open Reference for Particle Material.")
    protected MaterialSetting material = new MaterialSetting();
    @Getter
    @Configurable(name = "Renderer", subConfigurable = true, tips = "Specifies how the particles are rendered.")
    protected RendererSetting renderer = new RendererSetting();
    @Getter
    @Configurable(name = "Physics", subConfigurable = true, tips = "The physics of particles.")
    protected PhysicsSetting physics = new PhysicsSetting();
    @Getter
    @Configurable(name = "Fixed Light", subConfigurable = true, tips = "Controls the light map of each particle during its lifetime.")
    protected LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Getter
    @Configurable(name = "Velocity over Lifetime", subConfigurable = true, tips = "Controls the velocity of each particle during its lifetime.")
    protected final VelocityOverLifetimeSetting velocityOverLifetime = new VelocityOverLifetimeSetting();
    @Getter
    @Configurable(name = "Inherit Velocity", subConfigurable = true, tips = "Controls the velocity inherited from the emitter, foreach particle.")
    protected final InheritVelocitySetting inheritVelocity = new InheritVelocitySetting();
    @Getter
    @Configurable(name = "Lifetime by Emitter Speed", subConfigurable = true, tips = "Controls the initial lifetime of each particle based on the speed of the emitter when the particle was spawned.")
    protected final LifetimeByEmitterSpeedSetting lifetimeByEmitterSpeed = new LifetimeByEmitterSpeedSetting();
    @Getter
    @Configurable(name = "Force over Lifetime", subConfigurable = true, tips = "Controls the force of each particle during its lifetime.")
    protected final ForceOverLifetimeSetting forceOverLifetime = new ForceOverLifetimeSetting();
    @Getter
    @Configurable(name = "Color over Lifetime", subConfigurable = true, tips = "Controls the color of each particle during its lifetime.")
    protected final ColorOverLifetimeSetting colorOverLifetime = new ColorOverLifetimeSetting();
    @Getter
    @Configurable(name = "Color by Speed", subConfigurable = true, tips = "Controls the color of each particle based on its speed.")
    protected final ColorBySpeedSetting colorBySpeed = new ColorBySpeedSetting();
    @Getter
    @Configurable(name = "Size over Lifetime", subConfigurable = true, tips = "Controls the size of each particle during its lifetime.")
    protected final SizeOverLifetimeSetting sizeOverLifetime = new SizeOverLifetimeSetting();
    @Getter
    @Configurable(name = "Size by Speed", subConfigurable = true, tips = "Controls the size of each particle based on its speed.")
    protected final SizeBySpeedSetting sizeBySpeed = new SizeBySpeedSetting();
    @Getter
    @Configurable(name = "Rotation over Lifetime", subConfigurable = true, tips = "Controls the rotation of each particle during its lifetime.")
    protected final RotationOverLifetimeSetting rotationOverLifetime = new RotationOverLifetimeSetting();
    @Getter
    @Configurable(name = "Rotation by Speed", subConfigurable = true, tips = "Controls the angular velocity of each particle based on its speed.")
    protected final RotationBySpeedSetting rotationBySpeed = new RotationBySpeedSetting();
    @Getter
    @Configurable(name = "Noise", subConfigurable = true, tips = "Add noise/turbulence to particle movement.")
    protected final NoiseSetting noise = new NoiseSetting();
    @Getter
    @Configurable(name = "UV Animation", subConfigurable = true, tips = "Particle UV animation. This allows you to specify a texture sheet (a texture with multiple tiles/subframes) and animate or randomize over it per particle.")
    protected final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Getter
    @Configurable(name = "Trails", subConfigurable = true, tips = "Attach trails to the particles.")
    protected final TrailsSetting trails = new TrailsSetting(this);

    public ParticleConfig() {
    }

}
