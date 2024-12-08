package com.lowdragmc.photon.client.gameobject.emitter.trail;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.lowdragmc.photon.core.mixins.accessor.BlendModeAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nonnull;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote TrailConfig
 */
public class TrailConfig implements IPersistedSerializable {
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.trail.config.time")
    @NumberRange(range = {0f, Integer.MAX_VALUE})
    protected int time = 20;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.trail.config.minVertexDistance")
    @NumberRange(range = {0f, Float.MAX_VALUE})
    protected float minVertexDistance = 0.05f;
    @Getter
    @Configurable(tips = {
            "photon.emitter.trail.config.smoothInterpolation.0",
            "photon.emitter.trail.config.smoothInterpolation.1",
    })
    protected boolean smoothInterpolation = false;
    @Getter
//    @Configurable(tips = {
//            "photon.emitter.trail.config.calculateSmoothByShader.0",
//            "photon.emitter.trail.config.calculateSmoothByShader.1",
//    })
    protected boolean calculateSmoothByShader = false;
    @Setter
    @Getter
    @Configurable(tips = {"photon.emitter.config.parallelRendering.0",
            "photon.emitter.config.parallelRendering.1"})
    protected boolean parallelRendering = false;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.trail.config.uvMode")
    protected TrailParticle.UVMode uvMode = TrailParticle.UVMode.Stretch;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.trail.config.widthOverTrail")
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 0.1f}, xAxis = "trail position", yAxis = "width"))
    protected NumberFunction widthOverTrail = NumberFunction.constant(0.2f);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.trail.config.colorOverTrail")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverTrail = new Gradient();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "photon.emitter.config.material")
    public final MaterialSetting material = new MaterialSetting();
    @Getter
    @Configurable(name = "Renderer", subConfigurable = true, tips = "photon.emitter.config.renderer")
    public final RendererSetting renderer = new RendererSetting();
    @Getter
    @Configurable(name = "Fixed Light", subConfigurable = true, tips = "photon.emitter.config.lights")
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Getter
    @Configurable(name = "UV Animation", subConfigurable = true, tips = "photon.emitter.config.uvAnimation")
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();

    // runtime
    public final PhotonParticleRenderType particleRenderType = new RenderType();

    public TrailConfig() {
        material.setMaterial(new CustomShaderMaterial());
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        IPersistedSerializable.super.deserializeNBT(tag);
        // compatible with old version
        if (!tag.contains("smoothInterpolation")) {
            if (widthOverTrail instanceof Constant constant) {
                constant.setNumber(constant.getNumber().floatValue() / 10);
            } else if (widthOverTrail instanceof Curve curve) {
                curve.setMax(curve.getMax() / 10);
                curve.setMin(curve.getMin() / 10);
                curve.setDefaultValue(curve.getDefaultValue() / 10);
            }
        }
    }

    private class RenderType extends PhotonParticleRenderType {
        private BlendMode lastBlend = null;

        @Override
        public void prepareStatus() {
            if (renderer.isBloomEffect()) {
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
            if (renderer.isBloomEffect()) {
                endBloom();
            }
        }

        @Override
        public boolean isParallel() {
            return isParallelRendering();
        }

    }
}
