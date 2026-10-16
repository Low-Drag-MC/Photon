package com.lowdragmc.photon.client.gameobject.light;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** The light shows in the air it crosses, as a halo or a beam; the strength is a curve over the lifetime. */
@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class VolumetricSetting extends ToggleGroup {

    @Configurable(name = "VolumetricSetting.strength", tips = "photon.light.volumetric.strength")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 1,
            curveConfig = @CurveConfig(bound = {0, 4}, xAxis = "lifetime", yAxis = "strength"))
    protected NumberFunction strength = NumberFunction.constant(1);
}
