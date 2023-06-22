package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.emitter.data.number.*;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote LifetimeByEmitterSpeed
 */
@Environment(EnvType.CLIENT)
public class ForceOverLifetimeSetting extends ToggleGroup {

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.forceOverLifetime.force")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "force")))
    protected NumberFunction3 force = new NumberFunction3(0, 0, 0);

    public Vector3f getForce(LParticle particle) {
        return force.get(particle.getT(), () -> particle.getMemRandom(this)).mul(0.05f);
    }

}
