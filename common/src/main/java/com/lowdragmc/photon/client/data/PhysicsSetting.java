package com.lowdragmc.photon.client.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.photon.client.data.number.Constant;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.data.number.RandomConstant;
import com.lowdragmc.photon.client.data.number.curve.Curve;
import com.lowdragmc.photon.client.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.data.number.curve.RandomCurve;
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


    public PhysicsSetting() {
    }

    public float getFrictionModifier(LParticle particle) {
        return friction.get(particle.getT(), () -> particle.getMemRandom("friction")).floatValue();
    }

    public float getGravityModifier(LParticle particle) {
        return gravity.get(particle.getT(), () -> particle.getMemRandom("gravity")).floatValue();
    }
}
