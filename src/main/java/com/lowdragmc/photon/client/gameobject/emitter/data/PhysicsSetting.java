package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
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
 * Pure-data physics config. The value-use behaviour (getFriction/getGravity/… and the collision flags)
 * lives on the co-located {@link Runtime} — a per-emitter runtime layer whose named {@link RuntimeValue}
 * slots are written by the timeline and fall back to these authored fields.
 *
 * @author KilaBash
 * @date 2023/5/31
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
    @Configurable(name = "PhysicsSetting.collidedFriction", tips = "photon.emitter.config.physics.collidedFriction")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "duration", yAxis = "friction"))
    protected NumberFunction collidedFriction = NumberFunction.constant(0.7f);
    @Configurable(name = "PhysicsSetting.gravity", tips = "photon.emitter.config.physics.gravity")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "gravity"))
    protected NumberFunction gravity = NumberFunction.constant(0);
    @Configurable(name = "PhysicsSetting.bounceChance", tips = "photon.emitter.config.physics.bounceChance")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "duration", yAxis = "bounce chance"))
    protected NumberFunction bounceChance = NumberFunction.constant(1);
    @Configurable(name = "PhysicsSetting.bounceRate", tips = "photon.emitter.config.physics.bounceRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "bounce rate"))
    protected NumberFunction bounceRate = NumberFunction.constant(1);
    @Configurable(name = "PhysicsSetting.bounceSpreadRate", tips = "photon.emitter.config.physics.bounceSpreadRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "spread"))
    protected NumberFunction bounceSpreadRate = NumberFunction.constant(0);

    /** Create this setting's per-emitter runtime layer. */
    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /**
     * Per-emitter runtime layer for physics: named override slots (timeline-written, fall back to the
     * authored config) plus the value-use behaviour. All reads are direct field access — no map, no
     * reflection.
     */
    public static class Runtime {
        private final PhysicsSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<Boolean> hasCollision;
        public final RuntimeValue<Boolean> removedWhenCollided;
        public final RuntimeValue<NumberFunction> friction;
        public final RuntimeValue<NumberFunction> collidedFriction;
        public final RuntimeValue<NumberFunction> gravity;
        public final RuntimeValue<NumberFunction> bounceChance;
        public final RuntimeValue<NumberFunction> bounceRate;
        public final RuntimeValue<NumberFunction> bounceSpreadRate;

        public Runtime(PhysicsSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.hasCollision = new RuntimeValue<>(() -> config.hasCollision);
            this.removedWhenCollided = new RuntimeValue<>(() -> config.removedWhenCollided);
            this.friction = new RuntimeValue<>(() -> config.friction);
            this.collidedFriction = new RuntimeValue<>(() -> config.collidedFriction);
            this.gravity = new RuntimeValue<>(() -> config.gravity);
            this.bounceChance = new RuntimeValue<>(() -> config.bounceChance);
            this.bounceRate = new RuntimeValue<>(() -> config.bounceRate);
            this.bounceSpreadRate = new RuntimeValue<>(() -> config.bounceSpreadRate);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public boolean hasCollision() {
            return hasCollision.get();
        }

        public boolean isRemovedWhenCollided() {
            return removedWhenCollided.get();
        }

        public float getFriction(IParticle particle) {
            return friction.get().get(particle.getT(), () -> particle.getMemRandom("friction")).floatValue();
        }

        public float getCollidedFriction(IParticle particle) {
            return collidedFriction.get().get(particle.getT(), () -> particle.getMemRandom("collidedFriction")).floatValue();
        }

        public float getGravity(IParticle particle) {
            return gravity.get().get(particle.getT(), () -> particle.getMemRandom("gravity")).floatValue();
        }

        public float getBounceChance(IParticle particle) {
            return bounceChance.get().get(particle.getT(), () -> particle.getMemRandom("bounceChance")).floatValue();
        }

        public float getBounceRate(IParticle particle) {
            return bounceRate.get().get(particle.getT(), () -> particle.getMemRandom("bounceRate")).floatValue();
        }

        public float getBounceSpreadRate(IParticle particle) {
            return bounceSpreadRate.get().get(particle.getT(), () -> particle.getMemRandom("bounceSpreadRate")).floatValue();
        }

        public void clear() {
            enable.clear();
            hasCollision.clear();
            removedWhenCollided.clear();
            friction.clear();
            collidedFriction.clear();
            gravity.clear();
            bounceChance.clear();
            bounceRate.clear();
            bounceSpreadRate.clear();
        }
    }
}
