package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote PhysicsSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class PhysicsSetting extends ToggleGroup {

    @Configurable(name = "PhysicsSetting.hasCollision", tips = "photon.emitter.config.physics.hasCollision")
    protected boolean hasCollision = true;
    @Configurable(name = "PhysicsSetting.removedWhenCollided", tips = "photon.emitter.config.physics.removedWhenCollided")
    protected boolean removedWhenCollided = false;
    @Configurable(name = "PhysicsSetting.friction", tips = "photon.emitter.config.physics.friction")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "duration", yAxis = "friction"))
    protected NumberFunction friction = NumberFunction.constant(1);
    @Configurable(name = "PhysicsSetting.gravity", tips = "photon.emitter.config.physics.gravity")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "gravity"))
    protected NumberFunction gravity = NumberFunction.constant(0);
    @Configurable(name = "PhysicsSetting.bounceChance", tips = "photon.emitter.config.physics.bounceChance")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "duration", yAxis = "bounce chance"))
    protected NumberFunction bounceChance = NumberFunction.constant(1);
    @Configurable(name = "PhysicsSetting.bounceRate", tips = "photon.emitter.config.physics.bounceRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "bounce rate"))
    protected NumberFunction bounceRate =NumberFunction.constant(1);
    @Configurable(name = "PhysicsSetting.bounceSpreadRate", tips = "photon.emitter.config.physics.bounceSpreadRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "spread"))
    protected NumberFunction bounceSpreadRate = NumberFunction.constant(0);

    public float getFriction(IParticle particle) {
        return friction.get(particle.getT(), () -> particle.getMemRandom("friction")).floatValue();
    }

    public float getGravity(IParticle particle) {
        return gravity.get(particle.getT(), () -> particle.getMemRandom("gravity")).floatValue();
    }

    public float getBounceChance(IParticle particle) {
        return bounceChance.get(particle.getT(), () -> particle.getMemRandom("bounceChance")).floatValue();
    }

    public float getBounceRate(IParticle particle) {
        return bounceRate.get(particle.getT(), () -> particle.getMemRandom("bounceRate")).floatValue();
    }

    public float getBounceSpreadRate(IParticle particle) {
        return bounceSpreadRate.get(particle.getT(), () -> particle.getMemRandom("bounceSpreadRate")).floatValue();
    }
}
