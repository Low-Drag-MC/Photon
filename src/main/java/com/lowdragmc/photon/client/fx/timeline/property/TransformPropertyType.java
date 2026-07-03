package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;

/**
 * Base for the three local-transform animatable properties (position / rotation / scale). All have
 * three channels x/y/z.
 */
public abstract class TransformPropertyType implements AnimatedPropertyType {
    private static final String[] KEYS = {"x", "y", "z"};

    /** Group the three local-transform properties under a {@code transform} menu branch. */
    @Override
    public String path() {
        return "transform." + name();
    }

    @Override
    public int channelCount() {
        return 3;
    }

    @Override
    public String channelKey(int channel) {
        return KEYS[channel];
    }
}
