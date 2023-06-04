package com.lowdragmc.photon.client.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.photon.client.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.data.number.Constant;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.data.number.RandomConstant;
import com.lowdragmc.photon.client.data.number.color.Color;
import com.lowdragmc.photon.client.data.number.color.Gradient;
import com.lowdragmc.photon.client.data.number.color.RandomColor;
import com.lowdragmc.photon.client.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.data.number.curve.Curve;
import com.lowdragmc.photon.client.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.emitter.ParticleEmitter;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.client.particle.TrailParticle;
import com.lowdragmc.photon.core.mixins.accessor.BlendModeAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Vector4f;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;

import javax.annotation.Nonnull;

/**
 * @author KilaBash
 * @date 2023/6/1
 * @implNote TrailsSetting
 */
@Environment(EnvType.CLIENT)
public class TrailsSetting extends ToggleGroup {
    @Setter
    @Getter
    @Configurable(tips = "Choose what proportion of particles will receive a trail.")
    @NumberRange(range = {0f, 1f})
    protected float ratio = 1f;
    @Setter
    @Getter
    @Configurable(tips = "How long each trail will last, relative to the life of the particle.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "trail length"))
    protected NumberFunction lifetime = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "The minimum distance each trail can travel before adding a new vertex.")
    @NumberRange(range = {0f, Float.MAX_VALUE})
    protected float minimumVertexDistance = 0.02f;
    @Setter
    @Getter
    @Configurable(tips = "The trails will disappear when their owning particles die.")
    protected boolean dieWithParticles = false;
    @Setter
    @Getter
    @Configurable(tips = "Should the U coordinate be stretched or tiled?")
    protected TrailParticle.UVMode uvMode = TrailParticle.UVMode.Stretch;
    @Setter
    @Getter
    @Configurable(tips = "The trails will use the particle size to control their width.")
    protected boolean sizeAffectsWidth = true;
    @Setter
    @Getter
    @Configurable(tips = "The trails will use the particle size to control their lifetime.")
    protected boolean sizeAffectsLifetime = false;
    @Setter
    @Getter
    @Configurable(tips = "The trails will use the particle color as their base color.")
    protected boolean inheritParticleColor = true;
    @Setter
    @Getter
    @Configurable(tips = "The color of the trails during the lifetime of the particle they are attached to.")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverLifetime = new Gradient();
    @Setter
    @Getter
    @Configurable(tips = "Select a width for the trail from its start to end vertex.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "trail position", yAxis = "width"))
    protected NumberFunction widthOverTrail = NumberFunction.constant(1f);
    @Setter
    @Getter
    @Configurable(tips = "Select a color for the trail from its start to end vertex.")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverTrail = new Gradient();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "Open Reference for Tail Material.")
    protected final MaterialSetting material = new MaterialSetting();

    //runtime
    protected final ParticleRenderType renderType = new RenderType();

    public TrailsSetting() {
        material.setMaterial(new CustomShaderMaterial());
        material.cull = false;
    }

    public void setup(ParticleEmitter emitter, LParticle particle) {
        var random = emitter.getRandomSource();
        if (random.nextFloat() < ratio) { // has tail
            var pos = particle.getPos();
            var trail = new TrailParticle.Basic(emitter.getClientLevel(), pos.x, pos.y, pos.z, renderType);
            trail.setLevel(emitter.getLevel());
            trail.setLifetime(particle.getLifetime());
            trail.setWidth((float) particle.getQuadSize(0).min());
            trail.setUvMode(uvMode);
            trail.setMinimumVertexDistance(minimumVertexDistance);
            trail.setOnUpdate(p -> {
                var positionO = particle.getPos(0);
                var position = particle.getPos(1);
                p.setPos(positionO.x, positionO.y, positionO.z, true);
                p.setPos(position.x, position.y, position.z, false);
            });
            trail.setOnRemoveTails(t -> {
                var maxTails = lifetime.get(t.getT(), () -> t.getMemRandom("trails-lifetime")).floatValue() * particle.getLifetime();
                if (sizeAffectsLifetime) {
                    maxTails = (float) (maxTails * (particle.getQuadSize(0).min() / t.getWidth()));
                }
                var tails = t.getTails();
                while (tails.size() > maxTails) {
                    tails.removeFirst();
                }
                return true;
            });
            trail.setDieWhenRemoved(dieWithParticles);
            trail.setDynamicColor((t, partialTicks) -> {
                float a = 1f;
                float r = 1f;
                float g = 1f;
                float b = 1f;

                if (inheritParticleColor) {
                    var color = particle.getColor(partialTicks);
                    a *= color.w();
                    r *= color.x();
                    g *= color.y();
                    b *= color.z();
                }

                int color = colorOverLifetime.get(t.getT(partialTicks), () -> t.getMemRandom("trails-colorOverLifetime")).intValue();
                a *= ColorUtils.alpha(color);
                r *= ColorUtils.red(color);
                g *= ColorUtils.green(color);
                b *= ColorUtils.blue(color);

                return new Vector4f(r, g, b, a);
            });
            trail.setDynamicTailColor((t, tail, partialTicks) -> {
                int color = colorOverTrail.get(tail / (t.getTails().size() - 1f), () -> t.getMemRandom("trails-colorOverTrail")).intValue();
                return new Vector4f(ColorUtils.red(color), ColorUtils.green(color), ColorUtils.blue(color), ColorUtils.alpha(color));
            });
            trail.setDynamicTailWidth((t, tail, partialTicks) -> {
                var width = t.getWidth();
                if (sizeAffectsWidth) {
                    width = (float) (particle.getQuadSize(partialTicks).min());
                }
                width = width * widthOverTrail.get(tail / (t.getTails().size() - 1f), () -> t.getMemRandom("trails-widthOverTrail")).floatValue();
                return width;
            });

            emitter.emitParticle(trail);
        }
    }

    private class RenderType implements ParticleRenderType {
        @Override
        public void begin(@Nonnull BufferBuilder bufferBuilder, @Nonnull TextureManager textureManager) {
            material.pre();
            material.getMaterial().begin(bufferBuilder, textureManager, false);
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(@Nonnull Tesselator tesselator) {
            BlendMode lastBlend = null;
            if (RenderSystem.getShader() instanceof ShaderInstanceAccessor shader) {
                lastBlend = BlendModeAccessor.getLastApplied();
                BlendModeAccessor.setLastApplied(shader.getBlend());
            }
            tesselator.end();
            material.getMaterial().end(tesselator, false);
            material.post();
            if (lastBlend != null) {
                lastBlend.apply();
            }
        }
    }
}
