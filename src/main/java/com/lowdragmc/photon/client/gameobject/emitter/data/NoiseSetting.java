package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.math.noise.PerlinNoise;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;


/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote NoiseSetting
 */
@OnlyIn(Dist.CLIENT)
@Setter
@Getter
public class NoiseSetting extends ToggleGroup {
    public enum Quality {
        Noise1D,
        Noise2D,
        Noise3D
    }

    private final ThreadLocal<PerlinNoise> noise = ThreadLocal.withInitial(PerlinNoise::new);

    @Configurable(name = "NoiseSetting.frequency", tips = "photon.emitter.config.noise.frequency")
    @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    protected float frequency = 1;

    @Configurable(name = "NoiseSetting.quality", tips = "photon.emitter.config.noise.quality")
    protected Quality quality = Quality.Noise2D;

    @Configurable(name = "NoiseSetting.remap", subConfigurable = true, tips = "photon.emitter.config.noise.remap")
    protected final Remap remap = new Remap();

    @Configurable(name = "NoiseSetting.position", tips = "photon.emitter.config.noise.position")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "strength")))
    protected NumberFunction3 position = new NumberFunction3(0.1, 0.1, 0.1);

    @Configurable(name = "NoiseSetting.rotation", tips = "photon.emitter.config.noise.rotation")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 180}, xAxis = "rotation amount", yAxis = "lifetime"))
    protected NumberFunction rotation = NumberFunction.constant(0);

    @Configurable(name = "NoiseSetting.size", tips = "photon.emitter.config.noise.size")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "size amount", yAxis = "lifetime"))
    protected NumberFunction size = NumberFunction.constant(0);


    public float getNoise(float t) {
        var input = t * frequency;
        float value = (float)switch (quality) {
            case Noise1D -> noise.get().noise(input);
            case Noise2D -> noise.get().noise(input, input);
            case Noise3D -> noise.get().noise(input, input, input);
        };
        if (remap.isEnable()) {
            value = remap.remapCurve.get((value + 1) / 2, () -> 0f).floatValue();
        }
        return value;
    }

    public void setupSeed(IParticle particle) {
        noise.get().setSeed(particle.getMemRandom("noise-seed", randomSource -> (float) randomSource.nextGaussian()) * 255);
    }

    public Vector3f getRotation(IParticle particle, float partialTicks) {
        setupSeed(particle);
        var t = particle.getT(partialTicks);
        var degree = rotation.get(t, () -> particle.getMemRandom("noise-rotation")).floatValue();
        if (degree != 0) {
            return new Vector3f(degree, 0, 0).mul(getNoise((t + 10 * particle.getMemRandom("noise-rotation-degree")) * 100) * Mth.TWO_PI / 360);
        }
        return new Vector3f(0 ,0, 0);
    }

    public Vector3f getSize(IParticle particle, float partialTicks) {
        setupSeed(particle);
        var t = particle.getT(partialTicks);
        var scale = size.get(t, () -> particle.getMemRandom("noise-size")).floatValue();
        if (scale != 0) {
            return new Vector3f(scale, scale, scale).mul(getNoise((t + 10 * particle.getMemRandom("noise-size-scale")) * 100));
        }
        return new Vector3f(0 ,0, 0);
    }

    public Vector3f getPosition(IParticle particle, float partialTicks) {
        setupSeed(particle);
        var t = particle.getT(partialTicks);
        var offset = position.get(t, () -> particle.getMemRandom("noise-position"));
        if (!(offset.x == 0 && offset.y == 0 && offset.z == 0)) {
            offset.mul(
                    getNoise((t + 10 * particle.getMemRandom("noise-position-x")) * 100),
                    getNoise((t + 10 * particle.getMemRandom("noise-position-y")) * 100),
                    getNoise((t + 10 * particle.getMemRandom("noise-position-z")) * 100));
            return offset;
        }
        return new Vector3f(0 ,0, 0);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        new NoisePreview(LDLib2.RANDOM.nextGaussian() * 255).createPreview(father);
        super.buildConfigurator(father);
    }


    public static class Remap extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @NumberFunctionConfig(types = {Curve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "base noise", yAxis = "remap result"))
        protected NumberFunction remapCurve = new Curve(Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 1, 1f, "base noise", "remap result");
    }

    private class NoisePreview implements IGuiTexture {

        private final double seed;

        public NoisePreview(double seed) {
            this.seed = seed;
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, float width, float height, float partialTicks) {
            noise.get().setSeed(seed);
            // render color bar
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            Matrix4f mat = graphics.pose().last().pose();
            Tesselator tesselator = Tesselator.getInstance();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            var buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            for (int i = 0; i < width; i++) {
                if (quality == Quality.Noise1D) {
                    var value = ((float) noise.get().noise(i * frequency) + 1) / 2;
                    if (remap.isEnable()) {
                        value = (remap.remapCurve.get(value, () -> 0f).floatValue() + 1) / 2;
                    }
                    buffer.addVertex(mat,x + i + 1, y, 0).setColor(value, value, value, 1);
                    buffer.addVertex(mat, x + i, y, 0).setColor(value, value, value, 1);
                    buffer.addVertex(mat, x + i, y + height, 0).setColor(value, value, value, 1);
                    buffer.addVertex(mat, x + i + 1, y + height, 0).setColor(value, value, value, 1);
                } else {
                    for (int j = 0; j < height; j++) {
                        float value;
                        if (quality == Quality.Noise2D) {
                            value = ((float) noise.get().noise(i * frequency, j * frequency) + 1) / 2;
                        } else {
                            value = ((float) noise.get().noise(i * frequency, j * frequency, 1) + 1) / 2;
                        }

                        if (remap.isEnable()) {
                            value = (remap.remapCurve.get(value, () -> 0f).floatValue() + 1) / 2;
                        }

                        buffer.addVertex(mat,x + i + 1, y + j, 0).setColor(value, value, value, 1);
                        buffer.addVertex(mat, x + i, y + j, 0).setColor(value, value, value, 1);
                        buffer.addVertex(mat, x + i, y + j + 1, 0).setColor(value, value, value, 1);
                        buffer.addVertex(mat, x + i + 1, y + j + 1, 0).setColor(value, value, value, 1);

                    }
                }

            }

            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
    }
}
