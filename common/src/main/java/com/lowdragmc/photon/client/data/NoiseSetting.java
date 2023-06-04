package com.lowdragmc.photon.client.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.lowdraglib.utils.noise.PerlinNoise;
import com.lowdragmc.photon.client.data.number.*;
import com.lowdragmc.photon.client.data.number.curve.Curve;
import com.lowdragmc.photon.client.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.particle.LParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Matrix4f;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;


/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote NoiseSetting
 */
@Environment(EnvType.CLIENT)
public class NoiseSetting extends ToggleGroup {
    public enum Quality {
        Noise1D,
        Noise2D,
        Noise3D
    }

    private final PerlinNoise noise = new PerlinNoise();

    @Setter
    @Getter
    @Configurable(tips = "Low values create soft, smooth noise, and high values create rapidly changing noise.")
    @NumberRange(range = {Float.MIN_VALUE, Float.MAX_VALUE})
    protected float frequency = 1;

    @Setter
    @Getter
    @Configurable(tips = "Generate 1D,2D or 3D noise.")
    protected Quality quality = Quality.Noise2D;

    @Getter
    @Configurable(subConfigurable = true, tips = "Remap the final noise values into a new range.")
    protected final Remap remap = new Remap();

    @Setter
    @Getter
    @Configurable(tips = "How strong the overall noise effect is. If you use a curve to set this value, the Particle System applies the curve over the lifetime of each particle.")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "strength")))
    protected NumberFunction3 position = new NumberFunction3(0.1, 0.1, 0.1);

    @Setter
    @Getter
    @Configurable(tips = "What proportion of the noise is applied to the particle rotations,in degrees per second. If you use a curve to set this value, the Particle System applies the curve over the lifetime of each particle.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 180}, xAxis = "rotation amount", yAxis = "lifetime"))
    protected NumberFunction rotation = NumberFunction.constant(0);

    @Setter
    @Getter
    @Configurable(tips = "Multiply the size of the particle by a proportion of the noise.If you use a curve to set this value, theParticle System applies the curve over the life time of each particle.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "size amount", yAxis = "lifetime"))
    protected NumberFunction size = NumberFunction.constant(0);


    public double getNoise(float t) {
        var input = t * frequency;
        float value = (float)switch (quality) {
            case Noise1D -> noise.noise(input);
            case Noise2D -> noise.noise(input, input);
            case Noise3D -> noise.noise(input, input, input);
        };
        if (remap.isEnable()) {
            value = remap.remapCurve.get((value + 1) / 2, () -> 0f).floatValue();
        }
        return value;
    }

    public Vector3 getRotation(LParticle particle, float partialTicks) {
        var t = particle.getT(partialTicks);
        var degree = rotation.get(t, () -> particle.getMemRandom("noise-rotation")).floatValue();
        if (degree != 0) {
            return new Vector3(degree, 0, 0).multiply(getNoise((t + 10 * particle.getMemRandom("noise-rotation-degree")) * 100) * Mth.TWO_PI / 360);
        }
        return Vector3.ZERO;
    }

    public Vector3 getSize(LParticle particle, float partialTicks) {
        var t = particle.getT(partialTicks);
        var scale = size.get(t, () -> particle.getMemRandom("noise-size")).floatValue();
        if (scale != 0) {
            return new Vector3(scale, scale, scale).multiply(getNoise((t + 10 * particle.getMemRandom("noise-size-scale")) * 100));
        }
        return Vector3.ZERO;
    }

    public Vector3 getPosition(LParticle particle, float partialTicks) {
        var t = particle.getT(partialTicks);
        var offset = position.get(t, () -> particle.getMemRandom("noise-position"));
        if (!offset.isZero()) {
            offset.multiply(
                    getNoise((t + 10 * particle.getMemRandom("noise-position-x")) * 100),
                    getNoise((t + 10 * particle.getMemRandom("noise-position-y")) * 100),
                    getNoise((t + 10 * particle.getMemRandom("noise-position-z")) * 100));
            return offset;
        }
        return Vector3.ZERO;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        father.addConfigurator(0, new WrapperConfigurator("Noise preview", new ImageWidget(0, 0, 100, 100, new NoisePreview())));
    }


    public static class Remap extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @NumberFunctionConfig(types = {Curve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "base noise", yAxis = "remap result"))
        protected NumberFunction remapCurve = new Curve(Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 1, 1f, "base noise", "remap result");
    }

    private class NoisePreview implements IGuiTexture {

        @Override
        @Environment(EnvType.CLIENT)
        public void draw(PoseStack stack, int mouseX, int mouseY, float x, float y, int width, int height) {
            // render color bar
            RenderSystem.disableTexture();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            Matrix4f mat = stack.last().pose();
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder buffer = tesselator.getBuilder();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            for (int i = 0; i < width; i++) {
                if (quality == Quality.Noise1D) {
                    var value = ((float) noise.noise(i * frequency) + 1) / 2;
                    if (remap.isEnable()) {
                        value = (remap.remapCurve.get(value, () -> 0f).floatValue() + 1) / 2;
                    }
                    buffer.vertex(mat,x + i + 1, y, 0).color(value, value, value, 1).endVertex();
                    buffer.vertex(mat, x + i, y, 0).color(value, value, value, 1).endVertex();
                    buffer.vertex(mat, x + i, y + height, 0).color(value, value, value, 1).endVertex();
                    buffer.vertex(mat, x + i + 1, y + height, 0).color(value, value, value, 1).endVertex();
                } else {
                    for (int j = 0; j < height; j++) {
                        float value;
                        if (quality == Quality.Noise2D) {
                            value = ((float) noise.noise(i * frequency, j * frequency) + 1) / 2;
                        } else {
                            value = ((float) noise.noise(i * frequency, j * frequency, 1) + 1) / 2;
                        }

                        if (remap.isEnable()) {
                            value = (remap.remapCurve.get(value, () -> 0f).floatValue() + 1) / 2;
                        }

                        buffer.vertex(mat,x + i + 1, y + j, 0).color(value, value, value, 1).endVertex();
                        buffer.vertex(mat, x + i, y + j, 0).color(value, value, value, 1).endVertex();
                        buffer.vertex(mat, x + i, y + j + 1, 0).color(value, value, value, 1).endVertex();
                        buffer.vertex(mat, x + i + 1, y + j + 1, 0).color(value, value, value, 1).endVertex();

                    }
                }

            }

            tesselator.end();
            RenderSystem.enableTexture();
        }
    }
}
