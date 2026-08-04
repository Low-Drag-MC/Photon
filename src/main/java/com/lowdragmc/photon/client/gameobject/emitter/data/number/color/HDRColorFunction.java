package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * A colour {@link NumberFunction} whose samples are unclamped floats rather than a packed ARGB int, so
 * components may exceed 1 (emission / bloom).
 *
 * <p>It is still a plain {@code NumberFunction}: {@link #get} returns the clamped ARGB int, so every
 * existing LDR consumer keeps working against an HDR function — it just loses the range. That matters
 * because HDR and LDR functions do mix in practice (the timeline writes LDR functions into runtime
 * slots, and pre-HDR projects hold LDR functions in slots that are now HDR). Consumers that want the
 * range must check {@code instanceof HDRColorFunction} and call {@link #sampleHDR}; they must never
 * cast.
 *
 * <p>The sampled rgb has the intensity already multiplied in — the same convention as
 * {@link com.lowdragmc.lowdraglib2.math.HDRColor#toVector4f()}.
 */
public interface HDRColorFunction extends NumberFunction {

    /** Sample the unclamped HDR rgba into {@code out}. */
    void sampleHDR(float t, Supplier<Float> lerp, Vector4f out);

    @Override
    default Number get(float t, Supplier<Float> lerp) {
        var sample = new Vector4f();
        sampleHDR(t, lerp, sample);
        return ColorUtils.color(clamp01(sample.w), clamp01(sample.x), clamp01(sample.y), clamp01(sample.z));
    }

    private static float clamp01(float value) {
        return Math.clamp(value, 0f, 1f);
    }
}
