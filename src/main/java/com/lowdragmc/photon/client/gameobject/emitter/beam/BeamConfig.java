package com.lowdragmc.photon.client.gameobject.emitter.beam;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;

import javax.annotation.Nonnull;

/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamConfig
 */
public class BeamConfig {
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.duration")
    @ConfigNumber(range = {1, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.looping")
    protected boolean looping = true;
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.startDelay")
    @ConfigNumber(range = {0, Integer.MAX_VALUE})
    protected int startDelay = 0;
    @Getter
    @Configurable(tips = "photon.emitter.beam.config.end")
    @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    protected Vector3f end = new Vector3f(3, 0, 0);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.beam.config.width")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction width = NumberFunction.constant(0.2);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.beam.config.emitRate")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction emitRate = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.beam.config.color")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Color();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "photon.emitter.config.material")
    public final MaterialSetting material = new MaterialSetting();
    @Getter
    @Configurable(name = "Renderer", subConfigurable = true, tips = "photon.emitter.config.renderer")
    public final RendererSetting renderer = new RendererSetting();
    @Configurable(name = "UV Animation", subConfigurable = true, tips = "photon.emitter.config.uvAnimation")
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Getter
    @Configurable(name = "Fixed Light", subConfigurable = true, tips = "photon.emitter.config.lights")
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();

    // runtime
    public final PhotonFXRenderPass particleRenderType = new RenderPass();

    private class RenderPass extends PhotonFXRenderPass {

        @Override
        public void prepareStatus(@Nonnull RenderPassPipeline pipeline) {
            material.pre();
            material.getMaterial().begin(MaterialContext.NORMAL);
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        }

        @Override
        public BufferBuilder begin(@Nonnull Tesselator tesselator) {
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void releaseStatus(@Nonnull RenderPassPipeline pipeline) {
            material.getMaterial().end(MaterialContext.NORMAL);
            material.post();
        }

        @Override
        public int layerOrder() {
            return renderer.getOrderInLayer();
        }
    }
}
