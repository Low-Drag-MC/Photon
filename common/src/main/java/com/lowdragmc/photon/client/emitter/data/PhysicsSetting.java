package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.photon.client.emitter.data.number.Constant;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote PhysicsSetting
 */
@Environment(EnvType.CLIENT)
public class PhysicsSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "Check for collisions of particles.")
    protected boolean hasCollision = true;
    @Setter
    @Getter
    @Configurable(tips = "The friction of particles over lifetime. (0-infinite friction, 1-frictionless)")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 0.98f, curveConfig = @CurveConfig(xAxis = "duration", yAxis = "friction"))
    protected NumberFunction friction = NumberFunction.constant(0.98);
    @Setter
    @Getter
    @Configurable(tips = "The gravity of particles over lifetime.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "gravity"))
    protected NumberFunction gravity = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "The possibility of bounce when it has physics.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "duration", yAxis = "bounce chance"))
    protected NumberFunction bounceChance = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "The bounce rate of speed when collision happens.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "bounce rate"))
    protected NumberFunction bounceRate =NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "Addition velocity for other two axis gaussian noise when collision happens.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "spread"))
    protected NumberFunction bounceSpreadRate = NumberFunction.constant(0);

    public PhysicsSetting() {
    }

    public void setupParticlePhysics(LParticle particle) {
        particle.setFriction(friction.get(particle.getT(), () -> particle.getMemRandom("friction")).floatValue());
        particle.setGravity(gravity.get(particle.getT(), () -> particle.getMemRandom("gravity")).floatValue());
        particle.setBounceChance(bounceChance.get(particle.getT(), () -> particle.getMemRandom("bounce_chance")).floatValue());
        particle.setBounceRate(bounceRate.get(particle.getT(), () -> particle.getMemRandom("bounce_rate")).floatValue());
        particle.setBounceSpreadRate(bounceSpreadRate.get(particle.getT(), () -> particle.getMemRandom("bounce_spread_rate")).floatValue());
    }

}
