package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.client.shader.LDLibRenderTypes;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.noise.CurlNoise;
import com.lowdragmc.photon.client.gameobject.emitter.data.noise.NoiseParticleState;
import com.lowdragmc.photon.client.gameobject.emitter.data.noise.NoisePreviewSampler;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

import java.util.function.Supplier;

/** Unity-style spatial turbulence, independent of the legacy lifetime-based {@link NoiseSetting}. */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class Noise2Setting extends ToggleGroup {
    @Configurable(name = "Noise2Setting.strength", tips = "photon.emitter.config.noise2.strength")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "strength")))
    protected NumberFunction3 strengthAxes = new NumberFunction3(1, 1, 1);

    @Configurable(name = "Noise2Setting.frequency", tips = "photon.emitter.config.noise2.frequency")
    @ConfigNumber(range = {0, Float.MAX_VALUE})
    protected float frequency = 0.5f;

    @Configurable(name = "Noise2Setting.scrollSpeed", tips = "photon.emitter.config.noise2.scrollSpeed")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "duration", yAxis = "scroll speed"))
    protected NumberFunction scrollSpeed = NumberFunction.constant(0);

    @Configurable(name = "Noise2Setting.damping", tips = "photon.emitter.config.noise2.damping")
    protected boolean damping = true;

    @Configurable(name = "Noise2Setting.octaveCount", tips = "photon.emitter.config.noise2.octaveCount")
    @ConfigNumber(range = {1, 4})
    protected int octaveCount = 1;

    @Configurable(name = "Noise2Setting.octaveMultiplier", tips = "photon.emitter.config.noise2.octaveMultiplier")
    @ConfigNumber(range = {0, 1})
    protected float octaveMultiplier = 0.5f;

    @Configurable(name = "Noise2Setting.octaveScale", tips = "photon.emitter.config.noise2.octaveScale")
    @ConfigNumber(range = {1, 16})
    protected float octaveScale = 2;

    @Configurable(name = "Noise2Setting.quality", tips = "photon.emitter.config.noise2.quality")
    protected CurlNoise.Quality quality = CurlNoise.Quality.High;

    @Configurable(name = "Noise2Setting.remap", subConfigurable = true, tips = "photon.emitter.config.noise2.remap")
    protected final Remap remap = new Remap();

    @Configurable(name = "Noise2Setting.positionAmount", tips = "photon.emitter.config.noise2.positionAmount")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "position amount"))
    protected NumberFunction positionAmount = NumberFunction.constant(1);

    @Configurable(name = "Noise2Setting.rotationAmount", tips = "photon.emitter.config.noise2.rotationAmount")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 180}, xAxis = "lifetime", yAxis = "degrees/second"))
    protected NumberFunction rotationAmount = NumberFunction.constant(0);

    @Configurable(name = "Noise2Setting.sizeAmount", tips = "photon.emitter.config.noise2.sizeAmount")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "size amount"))
    protected NumberFunction sizeAmount = NumberFunction.constant(0);

    @Getter
    @Setter
    public static class Remap extends ToggleGroup {
        @Configurable(name = "Noise2Setting.remapCurve", tips = "photon.emitter.config.noise2.remapCurve")
        @NumberFunction3Config(common = @NumberFunctionConfig(types = {Curve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "base noise", yAxis = "remap result")))
        protected NumberFunction3 axes = new NumberFunction3(identityRemap(), identityRemap(), identityRemap());
    }

    private static Curve identityRemap() {
        return new Curve(-Float.MAX_VALUE, Float.MAX_VALUE, -1, 1, "base noise", "remap result",
                new ECBCurves(0, 0, 1f / 3, 1f / 3, 2f / 3, 2f / 3, 1, 1));
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        super.deserializeNBT(provider, migrateAxes(tag));
    }

    static CompoundTag migrateAxes(CompoundTag tag) {
        // Earlier Noise 2 files selected the common values with a separate checkbox. Fold the
        // active values into XYZ so opening an existing effect does not silently change it.
        var data = tag.copy();
        if (data.contains("separateAxes") && !data.getBoolean("separateAxes")) {
            if (data.contains("strength", Tag.TAG_COMPOUND)) {
                data.put("strengthAxes", repeat(data.get("strength")));
            }
            var remapData = data.getCompound("remap");
            if (remapData.contains("curve", Tag.TAG_COMPOUND)) {
                remapData.put("axes", repeat(remapData.get("curve")));
            }
        }
        return data;
    }

    private static ListTag repeat(Tag value) {
        var result = new ListTag();
        for (int i = 0; i < 3; i++) result.add(value.copy());
        return result;
    }

    /** Per-emitter scroll/seed and overrides. All particle workers read the same field snapshot. */
    public static class Runtime {
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction3> strengthAxes;
        public final RuntimeValue<Float> frequency;
        public final RuntimeValue<NumberFunction> scrollSpeed;
        public final RuntimeValue<Boolean> damping;
        public final RuntimeValue<Integer> octaveCount;
        public final RuntimeValue<Float> octaveMultiplier;
        public final RuntimeValue<Float> octaveScale;
        public final RuntimeValue<CurlNoise.Quality> quality;
        public final RuntimeValue<Boolean> remapEnabled;
        public final RuntimeValue<NumberFunction3> remapAxes;
        public final RuntimeValue<NumberFunction> positionAmount;
        public final RuntimeValue<NumberFunction> rotationAmount;
        public final RuntimeValue<NumberFunction> sizeAmount;
        private double scroll;
        private double previousScroll;
        private int seed;
        private final Vector3f seedOffset = CurlNoise.seedOffset(0, new Vector3f());

        public Runtime(Noise2Setting config) {
            enable = new RuntimeValue<>(config::isEnable);
            strengthAxes = new RuntimeValue<>(() -> config.strengthAxes);
            frequency = new RuntimeValue<>(() -> config.frequency);
            scrollSpeed = new RuntimeValue<>(() -> config.scrollSpeed);
            damping = new RuntimeValue<>(() -> config.damping);
            octaveCount = new RuntimeValue<>(() -> config.octaveCount);
            octaveMultiplier = new RuntimeValue<>(() -> config.octaveMultiplier);
            octaveScale = new RuntimeValue<>(() -> config.octaveScale);
            quality = new RuntimeValue<>(() -> config.quality);
            remapEnabled = new RuntimeValue<>(config.remap::isEnable);
            remapAxes = new RuntimeValue<>(() -> config.remap.axes);
            positionAmount = new RuntimeValue<>(() -> config.positionAmount);
            rotationAmount = new RuntimeValue<>(() -> config.rotationAmount);
            sizeAmount = new RuntimeValue<>(() -> config.sizeAmount);
        }

        public boolean isEnable() {
            return enable.get();
        }

        /** Called once before emission/parallel updates, including prewarm and seek replay. */
        public void advance(ParticleEmitter emitter, float dt) {
            advance((int) emitter.getRandomSeed(), emitter.getT(),
                    () -> emitter.getMemRandom("noise2-scroll"), dt);
        }

        public void advance(int emitterSeed, float emitterTime, Supplier<Float> random, float dt) {
            if (!isEnable()) return;
            if (seed != emitterSeed) {
                seed = emitterSeed;
                CurlNoise.seedOffset(seed, seedOffset);
            }
            previousScroll = scroll;
            scroll += scrollSpeed.get().get(emitterTime, random).doubleValue() * dt / 20;
        }

        public Vector3f sample(Vector3f position, float lifetime, Supplier<Float> random, Vector3f result) {
            return sample(position, lifetime, random, 1, result);
        }

        public Vector3f sample(Vector3f position, float lifetime, Supplier<Float> random, float stepFraction, Vector3f result) {
            double sampleScroll = previousScroll + (scroll - previousScroll) * stepFraction;
            sampleField(position, random, sampleScroll, result);
            return result.mul(strengthAxes.get().get(lifetime, random));
        }

        private Vector3f sampleField(Vector3f position, Supplier<Float> random, double sampleScroll, Vector3f result) {
            if (!Float.isFinite(frequency.get()) || frequency.get() < 0) return result.zero();
            CurlNoise.sample(position.x, position.y, position.z, sampleScroll, seedOffset, frequency.get(), false,
                    octaveCount.get(), octaveMultiplier.get(), octaveScale.get(), quality.get(), result);
            if (remapEnabled.get()) {
                var axes = remapAxes.get();
                // Remap the signed [-2, 2] curl before the base-frequency derivative factor.
                // Octave derivative scales remain in the signal; strength/damping follow remap.
                float baseFrequency = Math.max(frequency.get(), 0.0001f);
                float inputScale = 0.5f / baseFrequency;
                result.set(2 * baseFrequency * remap(axes.x, result.x * inputScale, random),
                        2 * baseFrequency * remap(axes.y, result.y * inputScale, random),
                        2 * baseFrequency * remap(axes.z, result.z * inputScale, random));
            }
            return result.mul(CurlNoise.dampingScale(frequency.get(), damping.get()));
        }

        private static float remap(NumberFunction curve, float value, Supplier<Float> random) {
            return curve.get(Mth.clamp((value + 1) * 0.5f, 0, 1), random).floatValue();
        }

        public void updateParticle(TileParticle particle, NoiseParticleState state, float dt, float stepFraction) {
            // Unity samples lifetime curves at the beginning of the simulation substep.
            float t = particle.getLifetime() > 0 ? Math.max(0, particle.getT() - dt / particle.getLifetime()) : particle.getT();
            var value = sample(particle.getSimPosWithoutNoise(1), t,
                    () -> particle.getMemRandom("noise2-strength"),
                    stepFraction, new Vector3f());
            state.update(value,
                    positionAmount.get().get(t, () -> particle.getMemRandom("noise2-position")).floatValue(),
                    rotationAmount.get().get(t, () -> particle.getMemRandom("noise2-rotation")).floatValue(),
                    sizeAmount.get().get(t, () -> particle.getMemRandom("noise2-size")).floatValue(),
                    particle.getRuntime().renderer.getRenderMode() == ParticleRendererSetting.Mode.Model, dt);
        }

        public void clear() {
            enable.clear();
            strengthAxes.clear();
            frequency.clear();
            scrollSpeed.clear();
            damping.clear();
            octaveCount.clear();
            octaveMultiplier.clear();
            octaveScale.clear();
            quality.clear();
            remapEnabled.clear();
            remapAxes.clear();
            positionAmount.clear();
            rotationAmount.clear();
            sizeAmount.clear();
            scroll = 0;
            previousScroll = 0;
            seed = 0;
            CurlNoise.seedOffset(0, seedOffset);
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        new NoisePreview().createPreview(father);
        super.buildConfigurator(father);
    }

    /** Unity's inspector previews the scalar derivative, including strength, damping and remap. */
    private class NoisePreview implements IGuiTexture {
        private static final int RESOLUTION = 96;
        private final float[] pixels = new float[RESOLUTION * RESOLUTION];
        private double scroll;
        private PreviewKey previousKey;
        private long previousTime;
        private long previousSampleTime;

        private record PreviewKey(float frequency, CurlNoise.Quality quality, int octaves,
                                  float multiplier, float scale, boolean damping, NumberFunction3 strength,
                                  boolean remapEnabled, NumberFunction3 remap) {
        }

        private void refresh() {
            long now = System.nanoTime();
            if (previousTime != 0) {
                // The preview has its own clock; opening the inspector never advances simulation.
                scroll += scrollSpeed.get(0, () -> 0.5f).doubleValue()
                        * Math.min((now - previousTime) / 1_000_000_000d, 0.1);
            }
            previousTime = now;
            var key = new PreviewKey(frequency, quality, octaveCount, octaveMultiplier,
                    octaveScale, damping, copyAxes(strengthAxes), remap.isEnable(), copyAxes(remap.axes));
            boolean scrolling = scrollSpeed.get(0, () -> 0.5f).floatValue() != 0;
            if (key.equals(previousKey) && (!scrolling || now - previousSampleTime < 16_666_667)) return;
            previousKey = key;
            previousSampleTime = now;
            var value = new Vector3f();
            var strength = strengthAxes.get(0, () -> 0.5f);
            for (int j = 0; j < RESOLUTION; j++) {
                for (int i = 0; i < RESOLUTION; i++) {
                    // Unity shows X/Y/Z settings in consecutive vertical thirds. Equal axis
                    // settings naturally form one continuous image without another toggle.
                    int axis = Math.min(i * 3 / RESOLUTION, 2);
                    float noise = NoisePreviewSampler.sample(i / (double) RESOLUTION,
                            (RESOLUTION - 1 - j) / (double) RESOLUTION, scroll, frequency, damping,
                            octaveCount, octaveMultiplier, octaveScale, quality, value) * strength.get(axis);
                    if (remap.isEnable()) {
                        NumberFunction curve = axis == 0 ? remap.axes.x : axis == 1 ? remap.axes.y : remap.axes.z;
                        noise = Runtime.remap(curve, noise, () -> 0.5f);
                    }
                    pixels[j * RESOLUTION + i] = Math.round(Mth.clamp(noise * 0.5f + 0.5f, 0, 1) * 255) / 255f;
                }
            }
        }

        private NumberFunction3 copyAxes(NumberFunction3 axes) {
            return new NumberFunction3(axes.x.copy(), axes.y.copy(), axes.z.copy());
        }

        @Override
        public void draw(GuiGraphics graphics, float mouseX, float mouseY, float x, float y,
                         float width, float height, float partialTicks) {
            refresh();
            var mat = graphics.pose().last().pose();
            var buffer = graphics.bufferSource().getBuffer(LDLibRenderTypes.guiOverlay());
            for (int j = 0; j <= RESOLUTION; j++) {
                for (int i = 0; i <= RESOLUTION; i++) {
                    int x0 = Math.max(0, i - 1), x1 = Math.min(RESOLUTION - 1, i);
                    int y0 = Math.max(0, j - 1), y1 = Math.min(RESOLUTION - 1, j);
                    float a = pixels[y0 * RESOLUTION + x0], b = pixels[y0 * RESOLUTION + x1];
                    float c = pixels[y1 * RESOLUTION + x0], d = pixels[y1 * RESOLUTION + x1];
                    float left = x + Math.max(0, i - 0.5f) * width / RESOLUTION;
                    float right = x + Math.min(RESOLUTION, i + 0.5f) * width / RESOLUTION;
                    float top = y + Math.max(0, j - 0.5f) * height / RESOLUTION;
                    float bottom = y + Math.min(RESOLUTION, j + 0.5f) * height / RESOLUTION;
                    // Interpolate between texel centers and clamp the half-texel border.
                    buffer.addVertex(mat, right, top, 0).setColor(b, b, b, 1);
                    buffer.addVertex(mat, left, top, 0).setColor(a, a, a, 1);
                    buffer.addVertex(mat, left, bottom, 0).setColor(c, c, c, 1);
                    buffer.addVertex(mat, right, bottom, 0).setColor(d, d, d, 1);
                }
            }
        }
    }
}
