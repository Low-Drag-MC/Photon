package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.emitter.data.number.Constant;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.client.particle.TrailParticle;
import com.lowdragmc.photon.core.mixins.accessor.BlendModeAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Vector4f;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;

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
    @Configurable(tips = "photon.emitter.config.trails.ratio")
    @NumberRange(range = {0f, 1f})
    protected float ratio = 1f;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.lifetime")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, max = 1, defaultValue = 1, curveConfig = @CurveConfig(xAxis = "lifetime", yAxis = "trail length"))
    protected NumberFunction lifetime = NumberFunction.constant(1);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.minimumVertexDistance")
    @NumberRange(range = {0f, Float.MAX_VALUE})
    protected float minimumVertexDistance = 0.02f;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.dieWithParticles")
    protected boolean dieWithParticles = false;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.uvMode")
    protected TrailParticle.UVMode uvMode = TrailParticle.UVMode.Stretch;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.sizeAffectsWidth")
    protected boolean sizeAffectsWidth = true;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.sizeAffectsLifetime")
    protected boolean sizeAffectsLifetime = false;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.inheritParticleColor")
    protected boolean inheritParticleColor = true;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.colorOverLifetime")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverLifetime = new Gradient();
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.widthOverTrail")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "trail position", yAxis = "width"))
    protected NumberFunction widthOverTrail = NumberFunction.constant(1f);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.trails.colorOverTrail")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverTrail = new Gradient();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "photon.emitter.config.material")
    protected final MaterialSetting material = new MaterialSetting();

    //runtime
    protected final PhotonParticleRenderType renderType;

    public TrailsSetting(ParticleConfig config) {
        renderType = new RenderType(config);
        material.setMaterial(new CustomShaderMaterial());
        material.cull = false;
    }

    public void setup(ParticleEmitter emitter, LParticle particle) {
        var random = emitter.getRandomSource();
        if (random.nextFloat() < ratio) { // has tail
            var pos = particle.getPos();
            var trail = new TrailParticle.Basic(emitter.getClientLevel(), pos.x, pos.y, pos.z, renderType);
            trail.setDelay(particle.getDelay());
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

            trail.setDynamicLight((p, partialTicks) -> {
                if (emitter.usingBloom()) {
                    return LightTexture.FULL_BRIGHT;
                }
                if (emitter.getConfig().getLights().isEnable()) {
                    return emitter.getConfig().getLights().getLight(p, partialTicks);
                }
                return p.getLight(partialTicks);
            });

            emitter.emitParticle(trail);
        }
    }

    private class RenderType extends PhotonParticleRenderType {
        protected final ParticleConfig config;
        private BlendMode lastBlend = null;

        public RenderType(ParticleConfig config) {
            this.config = config;
        }

        @Override
        public void prepareStatus() {
            if (config.getRenderer().isBloomEffect()) {
                beginBloom();
            }
            material.pre();
            material.getMaterial().begin(false);
            if (RenderSystem.getShader() instanceof ShaderInstanceAccessor shader) {
                lastBlend = BlendModeAccessor.getLastApplied();
                BlendModeAccessor.setLastApplied(shader.getBlend());
            }
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        }

        @Override
        public void begin(@Nonnull BufferBuilder bufferBuilder) {
            bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void releaseStatus() {
            material.getMaterial().end(false);
            material.post();
            if (lastBlend != null) {
                lastBlend.apply();
                lastBlend = null;
            }
            if (config.getRenderer().isBloomEffect()) {
                endBloom();
            }
        }

        @Override
        public boolean isParallel() {
            return config.isParallelRendering();
        }

        @Override
        public int hashCode() {
            return config.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof RenderType type) {
                return type.config.equals(config);
            }
            return super.equals(obj);
        }
    }
}
