package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
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

    public Vector3f getVelocityAddition(TileParticle particle) {
        var lifetime = particle.getT();
        var addition = linear.get(lifetime, () -> particle.getMemRandom("vol0")).mul(0.05f);
        var orbitalVec = orbital.get(lifetime, () -> particle.getMemRandom("vol1"));
        var center = offset.get(lifetime, () -> particle.getMemRandom("vol2"));
        if (!Vector3fHelper.isZero(orbitalVec)) {
            if (orbitalMode == OrbitalMode.AngularVelocity) {
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
            } else if (orbitalMode == OrbitalMode.LinearVelocity) {
                var toPoint = particle.getLocalPos().sub(center);
                if (orbitalVec.x != 0) {
                    var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(1, 0, 0)));
                    var r = radiusVec.length();
                    addition.add(new Vector3f(radiusVec).rotateX(orbitalVec.x * 0.05f / r).sub(radiusVec));
                }
                if (orbitalVec.y != 0) {
                    var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(0, 1, 0)));
                    var r = radiusVec.length();
                    addition.add(new Vector3f(radiusVec).rotateY(orbitalVec.y * 0.05f / r).sub(radiusVec));
                }
                if (orbitalVec.z != 0) {
                    var radiusVec = new Vector3f(toPoint).sub(Vector3fHelper.project(new Vector3f(toPoint), new Vector3f(0, 0, 1)));
                    var r = radiusVec.length();
                    addition.add(new Vector3f(radiusVec).rotateZ(orbitalVec.z * 0.05f / r).sub(radiusVec));
                }
            } else if (orbitalMode == OrbitalMode.FixedVelocity) {
                var toCenter = center.sub(particle.getLocalPos());
                if (orbitalVec.x != 0) {
                    addition.add(new Vector3f(toCenter).cross(new Vector3f(1, 0, 0)).normalize().mul(orbitalVec.x * 0.05f));
                }
                if (orbitalVec.y != 0) {
                    addition.add(new Vector3f(toCenter).cross(new Vector3f(0, 1, 0)).normalize().mul(orbitalVec.y * 0.05f));
                }
                if (orbitalVec.z != 0) {
                    addition.add(new Vector3f(toCenter).cross(new Vector3f(0, 0, 1)).normalize().mul(orbitalVec.z * 0.05f));
                }
            }
        }
        var radialVec = radial.get(lifetime, () -> particle.getMemRandom("vol3")).floatValue();
        if (radialVec != 0) {
            addition.add(particle.getLocalPos().normalize().mul(radialVec * 0.01f));
        }
        return addition;
    }

    public float getVelocityMultiplier(IParticle particle) {
        var lifetime = particle.getT();
        return speedModifier.get(lifetime, () -> particle.getMemRandom(this)).floatValue();
    }

}
