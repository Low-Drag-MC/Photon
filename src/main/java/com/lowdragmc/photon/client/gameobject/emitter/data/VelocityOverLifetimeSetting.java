package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote VelocityOverLifetimeSetting
 */
@Getter
@Setter
@OnlyIn(Dist.CLIENT)
public class VelocityOverLifetimeSetting extends ToggleGroup {

    public enum OrbitalMode {
        AngularVelocity,
        LinearVelocity,
        FixedVelocity
    }

    @Configurable(name = "VelocityOverLifetimeSetting.linear", tips = "photon.emitter.config.velocityOverLifetime.linear")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 1, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "lifetime", yAxis = "additional velocity")))
    protected NumberFunction3 linear = new NumberFunction3(0, 0, 0);

    @Configurable(name = "VelocityOverLifetimeSetting.orbitalMode", tips = "photon.emitter.config.velocityOverLifetime.orbitalMode")
    protected OrbitalMode orbitalMode = OrbitalMode.AngularVelocity;

    @Configurable(name = "VelocityOverLifetimeSetting.orbital", tips = "photon.emitter.config.velocityOverLifetime.orbital")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 1, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "lifetime", yAxis = "orbital velocity")))
    protected NumberFunction3 orbital = new NumberFunction3(0, 0, 0);

    @Configurable(name = "VelocityOverLifetimeSetting.offset", tips = "photon.emitter.config.velocityOverLifetime.offset")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 1, curveConfig = @CurveConfig(bound = {-3, 3}, xAxis = "lifetime", yAxis = "orbital offset")))
    protected NumberFunction3 offset = new NumberFunction3(0, 0, 0);

    @Configurable(name = "VelocityOverLifetimeSetting.radial", tips = "photon.emitter.config.velocityOverLifetime.radial")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction radial = NumberFunction.constant(0);

    @Configurable(name = "VelocityOverLifetimeSetting.speedModifier", tips = "photon.emitter.config.velocityOverLifetime.speedModifier")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "speed modifier"))
    protected NumberFunction speedModifier = NumberFunction.constant(1);

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime layer: slots (timeline/programmatic override, else config) + moved behaviour. */
    public static class Runtime {
        private final VelocityOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction3> linear;
        public final RuntimeValue<OrbitalMode> orbitalMode; // slot only (enum → no timeline binding)
        public final RuntimeValue<NumberFunction3> orbital;
        public final RuntimeValue<NumberFunction3> offset;
        public final RuntimeValue<NumberFunction> radial;
        public final RuntimeValue<NumberFunction> speedModifier;

        public Runtime(VelocityOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.linear = new RuntimeValue<>(config::getLinear);
            this.orbitalMode = new RuntimeValue<>(config::getOrbitalMode);
            this.orbital = new RuntimeValue<>(config::getOrbital);
            this.offset = new RuntimeValue<>(config::getOffset);
            this.radial = new RuntimeValue<>(config::getRadial);
            this.speedModifier = new RuntimeValue<>(config::getSpeedModifier);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Vector3f getVelocityAddition(TileParticle particle) {
            var lifetime = particle.getT();
            var addition = linear.get().get(lifetime, () -> particle.getMemRandom("vol0")).mul(0.05f);
            var orbitalVec = orbital.get().get(lifetime, () -> particle.getMemRandom("vol1"));
            var center = offset.get().get(lifetime, () -> particle.getMemRandom("vol2"));
            var mode = orbitalMode.get();
            if (!Vector3fHelper.isZero(orbitalVec)) {
                if (mode == OrbitalMode.AngularVelocity) {
                    var toPoint = new Vector3f(particle.getLocalPos()).sub(center);
                    if (orbitalVec.x != 0) {
                        var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(1, 0, 0)));
                        addition.add(new Vector3f(radiusVec).rotateX(orbitalVec.x * 0.05f).sub(radiusVec));
                    }
                    if (orbitalVec.y != 0) {
                        var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(0, 1, 0)));
                        addition.add(new Vector3f(radiusVec).rotateY(orbitalVec.y * 0.05f).sub(radiusVec));
                    }
                    if (orbitalVec.z != 0) {
                        var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(0, 0, 1)));
                        addition.add(new Vector3f(radiusVec).rotateZ(orbitalVec.z * 0.05f).sub(radiusVec));
                    }
                } else if (mode == OrbitalMode.LinearVelocity) {
                    var toPoint = particle.getLocalPos().sub(center);
                    // r guards: a particle on the rotation axis has a zero radius vector — dividing by
                    // its length would produce NaN velocity, so the axis term contributes nothing there
                    if (orbitalVec.x != 0) {
                        var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(1, 0, 0)));
                        var r = radiusVec.length();
                        if (r > 1e-5f) {
                            addition.add(new Vector3f(radiusVec).rotateX(orbitalVec.x * 0.05f / r).sub(radiusVec));
                        }
                    }
                    if (orbitalVec.y != 0) {
                        var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(0, 1, 0)));
                        var r = radiusVec.length();
                        if (r > 1e-5f) {
                            addition.add(new Vector3f(radiusVec).rotateY(orbitalVec.y * 0.05f / r).sub(radiusVec));
                        }
                    }
                    if (orbitalVec.z != 0) {
                        var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(0, 0, 1)));
                        var r = radiusVec.length();
                        if (r > 1e-5f) {
                            addition.add(new Vector3f(radiusVec).rotateZ(orbitalVec.z * 0.05f / r).sub(radiusVec));
                        }
                    }
                } else if (mode == OrbitalMode.FixedVelocity) {
                    var toCenter = center.sub(particle.getLocalPos());
                    // cross guards: toCenter parallel to the axis gives a zero cross product — normalize would be NaN
                    if (orbitalVec.x != 0) {
                        var tangential = new Vector3f(toCenter).cross(new Vector3f(1, 0, 0));
                        if (tangential.lengthSquared() > 1e-8f) {
                            addition.add(tangential.normalize().mul(orbitalVec.x * 0.05f));
                        }
                    }
                    if (orbitalVec.y != 0) {
                        var tangential = new Vector3f(toCenter).cross(new Vector3f(0, 1, 0));
                        if (tangential.lengthSquared() > 1e-8f) {
                            addition.add(tangential.normalize().mul(orbitalVec.y * 0.05f));
                        }
                    }
                    if (orbitalVec.z != 0) {
                        var tangential = new Vector3f(toCenter).cross(new Vector3f(0, 0, 1));
                        if (tangential.lengthSquared() > 1e-8f) {
                            addition.add(tangential.normalize().mul(orbitalVec.z * 0.05f));
                        }
                    }
                }
            }
            var radialVec = radial.get().get(lifetime, () -> particle.getMemRandom("vol3")).floatValue();
            if (radialVec != 0) {
                var localPos = particle.getLocalPos();
                if (localPos.lengthSquared() > 1e-8f) { // undefined radial direction at the origin
                    addition.add(localPos.normalize().mul(radialVec * 0.01f));
                }
            }
            return addition;
        }

        public float getVelocityMultiplier(IParticle particle) {
            var lifetime = particle.getT();
            return speedModifier.get().get(lifetime, () -> particle.getMemRandom(this)).floatValue();
        }

        public void clear() {
            enable.clear();
            linear.clear();
            orbitalMode.clear();
            orbital.clear();
            offset.clear();
            radial.clear();
            speedModifier.clear();
        }
    }

}
