package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Pure-data force-over-lifetime config; value-use behaviour lives on the co-located {@link Runtime}.
 *
 * @author KilaBash
 * @date 2023/5/30
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class ForceOverLifetimeSetting extends ToggleGroup {

    /**
     * The space the force vector is expressed in. Unlike the particle simulation space this is
     * restricted to Local/World (Unity parity); constant names must stay stable (persisted by name).
     */
    public enum ForceSpace {
        Local,
        World
    }

    @Configurable(name = "ForceOverLifetimeSetting.force", tips = "photon.emitter.config.forceOverLifetime.force")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "force")))
    protected NumberFunction3 force = new NumberFunction3(0, 0, 0);

    @Setter
    @Getter
    @Configurable(name = "ForceOverLifetimeSetting.simulationSpace", tips = "photon.emitter.config.simulationSpace")
    protected ForceSpace simulationSpace = ForceSpace.Local;

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final ForceOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction3> force;

        public Runtime(ForceOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.force = new RuntimeValue<>(() -> config.force);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public ForceSpace getSimulationSpace() {
            return config.simulationSpace;
        }

        public Vector3f getForce(IParticle particle) {
            return force.get().get(particle.getT(), () -> particle.getMemRandom(this)).mul(0.05f);
        }

        public void clear() {
            enable.clear();
            force.clear();
        }
    }
}
