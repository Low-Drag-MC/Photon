package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A single-channel scalar animatable: the object's playback speed ({@code selfTimeScale}). Used only by
 * the {@code speed} track (it is intentionally not part of any object's {@code animatableProperties()},
 * so it never appears in the animation track's add-property menu). Default value 1, range [0, 2].
 */
@OnlyIn(Dist.CLIENT)
public class SpeedPropertyType implements AnimatedPropertyType {
    @LDLRegisterClient(name = "speed", registry = "photon:animated_property")
    public static final SpeedPropertyType INSTANCE = new SpeedPropertyType();

    @Override
    public int channelCount() {
        return 1;
    }

    @Override
    public String channelKey(int channel) {
        return "speed";
    }

    @Override
    public float[] capture(FXObject target) {
        return new float[]{target.getSelfTimeScale()};
    }

    @Override
    public void apply(FXObject target, float[] values) {
        target.setSelfTimeScale(Math.max(0, values[0]));
    }

    @Override
    public float[] defaultRange(float[] base) {
        return new float[]{0, Math.max(2, base.length > 0 ? base[0] : 1)};
    }

    @Override
    public float clampValue(float value) {
        return Math.max(0, value); // speed can't go negative
    }

    @Override
    public Float fixedRangeMin() {
        return 0f; // bottom of the range is pinned at 0
    }
}
