package com.lowdragmc.photon.client.emitter.trail;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.photon.client.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.emitter.data.number.Constant;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.particle.TrailParticle;
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote TrailConfig
 */
public class TrailConfig {
    @Setter
    @Getter
    @Configurable(tips = "How long the tail should be (ticks)[O, infinity].")
    @NumberRange(range = {0f, Integer.MAX_VALUE})
    protected int time = 20;
    @Getter
    @Configurable(tips = "The minimum distance each trail can travel before adding a new vertex.")
    @NumberRange(range = {0f, Float.MAX_VALUE})
    protected float minVertexDistance = 0.05f;
    @Setter
    @Getter
    @Configurable(tips = {"Should particles be rendered in parallel threads. It will improve performance when you have massive particles. e.g., use it can improve trail particles",
            "§cThis does not always improve performance and sometimes degrade performance§r"})
    protected boolean parallelRendering = false;
    @Setter
    @Getter
    @Configurable(tips = "Should the U coordinate be stretched or tiled?")
    protected TrailParticle.UVMode uvMode = TrailParticle.UVMode.Stretch;
    @Setter
    @Getter
    @Configurable(tips = "Select a width for the trail from its start to end vertex.")
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "trail position", yAxis = "width"))
    protected NumberFunction widthOverTrail = NumberFunction.constant(2f);
    @Setter
    @Getter
    @Configurable(tips = "Select a color for the trail from its start to end vertex.")
    @NumberFunctionConfig(types = {Color.class, Gradient.class}, defaultValue = -1)
    protected NumberFunction colorOverTrail = new Gradient();
    @Getter
    @Configurable(name = "Material", subConfigurable = true, tips = "Open Reference for Tail Material.")
    protected final MaterialSetting material = new MaterialSetting();
    @Getter
    @Configurable(name = "Renderer", subConfigurable = true, tips = "Specifies how the particles are rendered.")
    protected final RendererSetting renderer = new RendererSetting();
    @Getter
    @Configurable(name = "Fixed Light", subConfigurable = true, tips = "Controls the light map of each particle during its lifetime.")
    protected final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
}
