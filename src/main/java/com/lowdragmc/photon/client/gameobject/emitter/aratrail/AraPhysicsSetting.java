package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote PhysicsSetting
 */
@Setter
@Getter
public class AraPhysicsSetting extends ToggleGroup {
    @Configurable(name = "AraTrails.warmup", tips = "AraTrails.warmup.tips")
    public float warmup = 0;               /**< simulation warmup seconds.*/
    @Configurable(name = "AraTrails.gravity", tips = "AraTrails.gravity.tips")
    public Vector3f gravity = new Vector3f();  /**< gravity applied to the trail, in world space. */
    @Configurable(name = "AraTrails.inertia", tips = "AraTrails.inertia.tips")
    @ConfigNumber(range = {0, 1})
    public float inertia = 0;               /**< amount of GameObject velocity transferred to the trail.*/
    @Configurable(name = "AraTrails.velocitySmoothing", tips = "AraTrails.velocitySmoothing.tips")
    @ConfigNumber(range = {0, 1})
    public float velocitySmoothing = 0.75f;     /**< velocity smoothing amount.*/
    @Configurable(name = "AraTrails.damping", tips = "AraTrails.damping.tips")
    @ConfigNumber(range = {0, 1})
    public float damping = 0.75f;               /**< velocity damping amount.*/

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime layer holding timeline-overridable slots over the immutable physics config. */
    public static class Runtime {
        private final AraPhysicsSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<Float> warmup;
        public final RuntimeValue<Vector3f> gravity;   // slot only (Vector3f → no timeline binding)
        public final RuntimeValue<Float> inertia;
        public final RuntimeValue<Float> velocitySmoothing;
        public final RuntimeValue<Float> damping;

        public Runtime(AraPhysicsSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.warmup = new RuntimeValue<>(() -> config.warmup);
            this.gravity = new RuntimeValue<>(() -> config.gravity);
            this.inertia = new RuntimeValue<>(() -> config.inertia);
            this.velocitySmoothing = new RuntimeValue<>(() -> config.velocitySmoothing);
            this.damping = new RuntimeValue<>(() -> config.damping);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public void clear() {
            enable.clear();
            warmup.clear();
            gravity.clear();
            inertia.clear();
            velocitySmoothing.clear();
            damping.clear();
        }
    }
}
