package com.lowdragmc.photon.client.gameobject.light;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRColorFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRRandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRRandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector4f;

import java.util.function.Supplier;

/** Colour, intensity and range are curves over the normalized lifetime; {@link Runtime} holds the timeline overrides. */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class LightConfig implements IConfigurable, IPersistedSerializable {

    public enum Type {
        Point,
        Spot
    }

    @Configurable(name = "LightConfig.type", tips = "photon.light.type")
    protected Type type = Type.Point;

    @Configurable(name = "LightConfig.lifetime", tips = "photon.light.lifetime")
    @ConfigNumber(range = {1, 1000000})
    protected int lifetime = 20;

    @Configurable(name = "LightConfig.looping", tips = "photon.light.looping")
    protected boolean looping = false;

    @Configurable(name = "LightConfig.color", tips = "photon.light.color")
    @NumberFunctionConfig(types = {HDRConstantColor.class, HDRRandomColor.class, HDRGradient.class, HDRRandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new HDRConstantColor();

    @Configurable(name = "LightConfig.intensity", tips = "photon.light.intensity")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 10,
            curveConfig = @CurveConfig(bound = {0, 64}, xAxis = "lifetime", yAxis = "intensity"))
    protected NumberFunction intensity = NumberFunction.constant(10);

    @Configurable(name = "LightConfig.range", tips = "photon.light.range")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 10,
            curveConfig = @CurveConfig(bound = {0, 64}, xAxis = "lifetime", yAxis = "range"))
    protected NumberFunction range = NumberFunction.constant(10);

    @Configurable(name = "LightConfig.innerAngle", tips = "photon.light.innerAngle")
    @ConfigNumber(range = {0, 89})
    protected float innerAngle = 20;

    @Configurable(name = "LightConfig.outerAngle", tips = "photon.light.outerAngle")
    @ConfigNumber(range = {0.1, 90})
    protected float outerAngle = 35;

    @Configurable(name = "LightConfig.sourceRadius", tips = "photon.light.sourceRadius")
    @ConfigNumber(range = {0, 8})
    protected float sourceRadius = 0.15f;

    @Configurable(name = "LightConfig.castShadows", tips = "photon.light.castShadows")
    protected boolean castShadows = true;

    @Configurable(name = "LightConfig.flicker", tips = "photon.light.flicker")
    @ConfigNumber(range = {0, 1})
    protected float flicker = 0;

    @Configurable(name = "LightConfig.flickerSpeed", tips = "photon.light.flickerSpeed")
    @ConfigNumber(range = {0, 50})
    protected float flickerSpeed = 8;

    public static class Runtime {
        public final RuntimeValue<NumberFunction> color;
        public final RuntimeValue<NumberFunction> intensity;
        public final RuntimeValue<NumberFunction> range;
        public final RuntimeValue<Float> innerAngle;
        public final RuntimeValue<Float> outerAngle;
        public final RuntimeValue<Float> sourceRadius;
        public final RuntimeValue<Float> flicker;
        public final RuntimeValue<Boolean> castShadows;

        public Runtime(LightConfig config) {
            this.color = new RuntimeValue<>(() -> config.color);
            this.intensity = new RuntimeValue<>(() -> config.intensity);
            this.range = new RuntimeValue<>(() -> config.range);
            this.innerAngle = new RuntimeValue<>(config::getInnerAngle);
            this.outerAngle = new RuntimeValue<>(config::getOuterAngle);
            this.sourceRadius = new RuntimeValue<>(config::getSourceRadius);
            this.flicker = new RuntimeValue<>(config::getFlicker);
            this.castShadows = new RuntimeValue<>(config::isCastShadows);
        }

        /** Linear rgb with the HDR intensity of the colour already multiplied in. */
        public void sampleColor(float t, Supplier<Float> random, Vector4f out) {
            HDRColorFunction.sample(color.get(), t, random, out);
        }

        public float getIntensity(float t, Supplier<Float> random) {
            return intensity.get().get(t, random).floatValue();
        }

        public float getRange(float t, Supplier<Float> random) {
            return range.get().get(t, random).floatValue();
        }

        public void clear() {
            color.clear();
            intensity.clear();
            range.clear();
            innerAngle.clear();
            outerAngle.clear();
            sourceRadius.clear();
            flicker.clear();
            castShadows.clear();
        }
    }
}
