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

    /**
     * Which axes the values on this module are authored in — independent of the emitter's simulation
     * space, exactly like Unity's Velocity over Lifetime "Space".
     *
     * <p>{@link #Local}: the emitter's own axes, so {@code linear = (0,1,0)} means "the emitter's up"
     * and rotating the effect re-aims it. {@link #World}: absolute world axes, unaffected by how the
     * emitter is oriented.
     *
     * <p>Under a non-Local simulation space the emitter's axes are taken from the particle's spawn
     * frame (see {@code TileParticle#emitterToSim}) — frozen, because those particles were
     * deliberately left behind in the world and should not be re-aimed by the emitter afterwards.
     *
     * <p><b>Beware:</b> {@link ForceOverLifetimeSetting.ForceSpace} spells the same two constants but
     * resolves {@code Local} against the <i>live</i> emitter matrix, so on a moving or rotating emitter
     * the two modules disagree about what "Local" means. The two should be reconciled onto one shared
     * enum and one sampling rule; until then this divergence is deliberate rather than overlooked.
     */
    public enum Space {
        Local,
        World
    }

    @Configurable(name = "VelocityOverLifetimeSetting.space", tips = "photon.emitter.config.velocityOverLifetime.space")
    protected Space space = Space.Local;

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

    /**
     * Per-emitter runtime layer: slots (timeline/programmatic override, else config) + moved behaviour.
     */
    public static class Runtime {
        private final VelocityOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<Space> space; // slot only (enum → no timeline binding)
        public final RuntimeValue<NumberFunction3> linear;
        public final RuntimeValue<OrbitalMode> orbitalMode; // slot only (enum → no timeline binding)
        public final RuntimeValue<NumberFunction3> orbital;
        public final RuntimeValue<NumberFunction3> offset;
        public final RuntimeValue<NumberFunction> radial;
        public final RuntimeValue<NumberFunction> speedModifier;

        public Runtime(VelocityOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.space = new RuntimeValue<>(config::getSpace);
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
            var frame = space.get();
            var radialVec = radial.get().get(lifetime, () -> particle.getMemRandom("vol3")).floatValue();
            var hasOrbital = !Vector3fHelper.isZero(orbitalVec);
            // Only orbital and radial need the particle's position, and reading it is not cheap — with
            // the Noise module on, getSimPos() samples the whole noise field. A linear-only config (the
            // common one) must not pay for it, so both live under this guard.
            //
            // Two corrections are folded into how `pos` is taken:
            //  - the pivot is the EMITTER, never the simulation space's origin — outside Local space
            //    getSimPos() is an absolute coordinate, which would drop it on the world origin
            //    (hundreds of blocks away, far below the effect) and inflate every radius;
            //  - the axes are `frame`'s, not the simulation space's — so "the emitter's up" keeps
            //    meaning that even when the particles simulate in world space.
            if (hasOrbital || radialVec != 0) {
                var pos = toFrame(particle, frame, particle.getEmitterRelativePos());
                if (hasOrbital) {
                    if (mode == OrbitalMode.AngularVelocity) {
                        var toPoint = new Vector3f(pos).sub(center);
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
                        var toPoint = new Vector3f(pos).sub(center);
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
                        var toCenter = new Vector3f(center).sub(pos);
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
                if (radialVec != 0) {
                    var radialDir = new Vector3f(pos);
                    if (radialDir.lengthSquared() > 1e-8f) { // undefined radial direction at the emitter
                        addition.add(radialDir.normalize().mul(radialVec * 0.01f));
                    }
                }
            }
            // internal velocity is measured in simulation space — hand the result back in it
            return fromFrame(particle, frame, addition);
        }

        /**
         * A simulation-space direction, re-expressed in {@code frame}'s axes (in place).
         */
        private static Vector3f toFrame(TileParticle particle, Space frame, Vector3f simDirection) {
            return frame == Space.World ? particle.simDirToWorld(simDirection)
                    : particle.simDirToEmitter(simDirection);
        }

        /**
         * A direction in {@code frame}'s axes, re-expressed in simulation space (in place).
         */
        private static Vector3f fromFrame(TileParticle particle, Space frame, Vector3f frameDirection) {
            return frame == Space.World ? particle.worldDirToSim(frameDirection)
                    : particle.emitterDirToSim(frameDirection);
        }

        public float getVelocityMultiplier(IParticle particle) {
            var lifetime = particle.getT();
            return speedModifier.get().get(lifetime, () -> particle.getMemRandom(this)).floatValue();
        }

        public void clear() {
            enable.clear();
            space.clear();
            linear.clear();
            orbitalMode.clear();
            orbital.clear();
            offset.clear();
            radial.clear();
            speedModifier.clear();
        }
    }

}
