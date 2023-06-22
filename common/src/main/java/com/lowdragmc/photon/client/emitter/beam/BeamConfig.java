package com.lowdragmc.photon.client.emitter.beam;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.emitter.data.RendererSetting;
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
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamConfig
 */
public class BeamConfig {
    @Setter
    @Getter
    @Configurable(tips = "The length of time (tick) the Particle Emitter is emitting particles. If the system is looping, this indicates the length of one cycle.")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(tips = "If true, the emission cycle will repeat after the duration.")
    protected boolean looping = true;
    @Setter
    @Getter
    @Configurable(tips = "Delay in seconds that this Particle System will wait before emitting particles.")
    @NumberRange(range = {0, Integer.MAX_VALUE})
    protected int startDelay = 0;
    @Getter
    @Configurable(tips = "Beam end offset.")
    @NumberRange(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    protected Vector3 end = new Vector3(3, 0, 0);
    @Setter
    @Getter
    @Configurable(tips = "Controls the width during its lifetime.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction width = NumberFunction.constant(0.2);
    @Setter
    @Getter
    @Configurable(tips = "Controls the emit rate during its lifetime.")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction emitRate = NumberFunction.constant(0);
    @Setter
    @Getter
    @Configurable(tips = "Controls the color during its lifetime.")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Color();
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
