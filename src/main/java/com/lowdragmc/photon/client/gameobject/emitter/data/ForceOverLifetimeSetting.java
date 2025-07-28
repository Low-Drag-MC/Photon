package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote LifetimeByEmitterSpeed
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class ForceOverLifetimeSetting extends ToggleGroup {

    @Configurable(name = "ForceOverLifetimeSetting.force", tips = "photon.emitter.config.forceOverLifetime.force")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "force")))
    protected NumberFunction3 force = new NumberFunction3(0, 0, 0);

    @Setter
    @Getter
    @Configurable(name = "ForceOverLifetimeSetting.simulationSpace", tips = "photon.emitter.config.simulationSpace")
    protected ParticleConfig.Space simulationSpace = ParticleConfig.Space.Local;

    public Vector3f getForce(IParticle particle) {
        return force.get(particle.getT(), () -> particle.getMemRandom(this)).mul(0.05f);
    }

}
