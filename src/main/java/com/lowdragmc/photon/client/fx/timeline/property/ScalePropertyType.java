package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.FXObject;
import org.joml.Vector3f;

public class ScalePropertyType extends TransformPropertyType {
    @LDLRegisterClient(name = "scale", registry = "photon:animated_property")
    public static final ScalePropertyType INSTANCE = new ScalePropertyType();

    @Override
    public float[] capture(FXObject target) {
        var s = target.transform().localScale();
        return new float[]{s.x, s.y, s.z};
    }

    @Override
    public void apply(FXObject target, float[] values) {
        target.transform().localScale(new Vector3f(values[0], values[1], values[2]));
    }
}
