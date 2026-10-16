package com.lowdragmc.photon.client.gameobject.light;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
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
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.function.Supplier;

/** {@link Runtime} holds the timeline overrides. */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class FogVolumeConfig implements IConfigurable, IPersistedSerializable {

    public enum Shape {
        Box,
        Sphere
    }

    @Configurable(name = "FogVolumeConfig.shape", tips = "photon.fog_volume.shape")
    protected Shape shape = Shape.Box;

    @Configurable(name = "FogVolumeConfig.lifetime", tips = "photon.fog_volume.lifetime")
    @ConfigNumber(range = {1, 1000000})
    protected int lifetime = 100;

    @Configurable(name = "FogVolumeConfig.looping", tips = "photon.fog_volume.looping")
    protected boolean looping = true;

    @Configurable(name = "FogVolumeConfig.density", tips = "photon.fog_volume.density")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1,
            curveConfig = @CurveConfig(bound = {0, 8}, xAxis = "lifetime", yAxis = "density"))
    protected NumberFunction density = NumberFunction.constant(1);

    @Configurable(name = "FogVolumeConfig.edgeFalloff", tips = "photon.fog_volume.edgeFalloff")
    @ConfigNumber(range = {0, 1})
    protected float edgeFalloff = 0.3f;

    @Configurable(name = "FogVolumeConfig.color", tips = "photon.fog_volume.color")
    @NumberFunctionConfig(types = {HDRConstantColor.class, HDRRandomColor.class, HDRGradient.class, HDRRandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new HDRConstantColor();

    @Configurable(name = "FogVolumeConfig.absorption", tips = "photon.fog_volume.absorption")
    @ConfigNumber(range = {0, 1})
    protected float absorption = 0.5f;

    @Configurable(name = "FogVolumeConfig.emission", tips = "photon.fog_volume.emission")
    @NumberFunctionConfig(types = {HDRConstantColor.class, HDRRandomColor.class, HDRGradient.class, HDRRandomGradient.class}, defaultValue = 0xFF000000)
    protected NumberFunction emission = new HDRConstantColor(new HDRColor(0f, 0f, 0f, 1f, 1f));

    @Configurable(name = "FogVolumeConfig.noise", subConfigurable = true, tips = "photon.fog_volume.noise")
    protected final Noise noise = new Noise();

    /** Moving noise that breaks the fog up into drifting wisps. */
    @Getter
    @Setter
    public static class Noise extends ToggleGroup {
        @Configurable(name = "FogVolumeConfig.noise.strength", tips = "photon.fog_volume.noise.strength")
        @ConfigNumber(range = {0, 1})
        protected float strength = 0.6f;

        @Configurable(name = "FogVolumeConfig.noise.scale", tips = "photon.fog_volume.noise.scale")
        @ConfigNumber(range = {0.1, 32})
        protected float scale = 2f;

        @Configurable(name = "FogVolumeConfig.noise.wind", tips = "photon.fog_volume.noise.wind")
        protected Vector3f wind = new Vector3f(0.5f, 0f, 0.2f);
    }

    public static class Runtime {
        public final RuntimeValue<NumberFunction> density;
        public final RuntimeValue<NumberFunction> color;
        public final RuntimeValue<NumberFunction> emission;
        public final RuntimeValue<Float> absorption;
        public final RuntimeValue<Float> edgeFalloff;

        public Runtime(FogVolumeConfig config) {
            this.density = new RuntimeValue<>(() -> config.density);
            this.color = new RuntimeValue<>(() -> config.color);
            this.emission = new RuntimeValue<>(() -> config.emission);
            this.absorption = new RuntimeValue<>(config::getAbsorption);
            this.edgeFalloff = new RuntimeValue<>(config::getEdgeFalloff);
        }

        public float getDensity(float t, Supplier<Float> random) {
            return density.get().get(t, random).floatValue();
        }

        /** Linear rgb with the HDR intensity already multiplied in. */
        public void sampleColor(float t, Supplier<Float> random, Vector4f out) {
            HDRColorFunction.sample(color.get(), t, random, out);
        }

        public void sampleEmission(float t, Supplier<Float> random, Vector4f out) {
            HDRColorFunction.sample(emission.get(), t, random, out);
        }

        public void clear() {
            density.clear();
            color.clear();
            emission.clear();
            absorption.clear();
            edgeFalloff.clear();
        }
    }
}
