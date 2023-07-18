package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import org.joml.Vector3f;
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
 * @date 2023/5/30
 * @implNote InheritVelocitySetting
 */
@Environment(EnvType.CLIENT)
public class InheritVelocitySetting extends ToggleGroup {
    public enum Mode {
        Position,
        Velocity
    }

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.inheritVelocity.mode")
    protected Mode mode = Mode.Position;

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.inheritVelocity.multiply")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction multiply = NumberFunction.constant(1);


    public Vector3f getVelocityAddition(LParticle particle, LParticle emitter) {
        var mul = multiply.get(particle.getT(), () -> particle.getMemRandom(this)).floatValue();
        if (mode == Mode.Velocity) {
            return emitter.getVelocity().mul(mul);
        }
        return new Vector3f(0 ,0, 0);
    }

    public Vector3f getPosition(LParticle emitter, Vector3f initialPos, float partialTicks) {
        if (mode == Mode.Position) {
            return emitter.getPos(partialTicks).sub(initialPos);
        }
        return new Vector3f(0 ,0, 0);
    }

}
