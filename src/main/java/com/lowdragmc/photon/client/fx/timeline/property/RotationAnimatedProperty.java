package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Rotation-specific {@link AnimatedProperty}: carries the angular {@link InterpMode} (the one piece of
 * per-type state that used to live on the shared base class). Built by {@link RotationPropertyType}.
 */
@OnlyIn(Dist.CLIENT)
public class RotationAnimatedProperty extends AnimatedProperty {
    /** How angular channels interpolate between keyframes. */
    public enum InterpMode {
        /** Follow the curve literally (e.g. 360°→5° spins the long way). */
        DEFAULT,
        /** Take the shortest angular path between keyframes (e.g. 355°→5° rotates only 10°). */
        SHORTEST;

        public String langKey() {
            return "photon.gui.editor.timeline.interp_" + name().toLowerCase();
        }

        public static InterpMode byOrdinal(int ordinal) {
            var values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : DEFAULT;
        }
    }

    private InterpMode interpMode = InterpMode.DEFAULT;

    public RotationAnimatedProperty(AnimatedPropertyType type, float[] base, ECBCurves[] channels,
                                    float rangeMin, float rangeMax) {
        super(type, base, channels, rangeMin, rangeMax);
    }

    public InterpMode interpMode() {
        return interpMode;
    }

    public void interpMode(InterpMode interpMode) {
        this.interpMode = interpMode;
    }

    @Override
    protected void restoreExtraFrom(AnimatedProperty other) {
        if (other instanceof RotationAnimatedProperty rotation) {
            this.interpMode = rotation.interpMode;
        }
    }

    @Override
    public AnimatedProperty copy() {
        var copy = new RotationAnimatedProperty(type, base(), snapshotChannels(), rangeMin(), rangeMax());
        copy.interpMode = this.interpMode;
        return copy;
    }
}
