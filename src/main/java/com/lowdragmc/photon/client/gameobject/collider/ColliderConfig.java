package com.lowdragmc.photon.client.gameobject.collider;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/** Pure-data collider config; timeline slots live on the co-located {@link Runtime}. */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class ColliderConfig implements IConfigurable, IPersistedSerializable {

    public enum Shape {
        /** an infinite plane through the object, facing its local +Y; solid from the front only */
        Plane,
        Box,
        Sphere
    }

    @Configurable(name = "ColliderConfig.shape", tips = "photon.collider.shape")
    protected Shape shape = Shape.Plane;
    @Configurable(name = "ColliderConfig.size", tips = "photon.collider.size")
    protected Vector3f size = new Vector3f(1, 1, 1);
    @Configurable(name = "ColliderConfig.radius", tips = "photon.collider.radius")
    @ConfigNumber(range = {0, 1000}, wheel = 0.1)
    protected float radius = 0.5f;
    @Configurable(name = "ColliderConfig.trigger", tips = "photon.collider.trigger")
    protected boolean trigger = false;

    public Shape getShape() {
        return shape == null ? Shape.Plane : shape;
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        public final RuntimeValue<Float> radius;
        public final RuntimeValue<Boolean> trigger;

        public Runtime(ColliderConfig config) {
            this.radius = new RuntimeValue<>(config::getRadius);
            this.trigger = new RuntimeValue<>(config::isTrigger);
        }

        public void clear() {
            radius.clear();
            trigger.clear();
        }
    }
}
