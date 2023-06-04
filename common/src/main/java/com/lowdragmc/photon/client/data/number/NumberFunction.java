package com.lowdragmc.photon.client.data.number;

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.photon.client.data.number.color.Color;
import com.lowdragmc.photon.client.data.number.color.Gradient;
import com.lowdragmc.photon.client.data.number.color.RandomColor;
import com.lowdragmc.photon.client.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.data.number.curve.Curve;
import com.lowdragmc.photon.client.data.number.curve.RandomCurve;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/25
 * @implNote NumberFunction
 */
public interface NumberFunction extends ITagSerializable<CompoundTag> {
    Map<String, Class< ? extends NumberFunction>> REGISTRY = new HashMap<>(Map.ofEntries(
            Map.entry(Color.class.getSimpleName(), Color.class),
            Map.entry(RandomColor.class.getSimpleName(), RandomColor.class),
            Map.entry(Constant.class.getSimpleName(), Constant.class),
            Map.entry(RandomConstant.class.getSimpleName(), RandomConstant.class),
            Map.entry(Curve.class.getSimpleName(), Curve.class),
            Map.entry(RandomCurve.class.getSimpleName(), RandomCurve.class),
            Map.entry(Gradient.class.getSimpleName(), Gradient.class),
            Map.entry(RandomGradient.class.getSimpleName(), RandomGradient.class)
    ));

    static NumberFunction constant(Number constant) {
        return new Constant(constant);
    }

    static NumberFunction color(Number color) {
        return new Color(color);
    }

    static Tag serializeWrapper(NumberFunction value) {
        var tag = value.serializeNBT();
        tag.putString("_type", value.getClass().getSimpleName());
        return tag;
    }

    static NumberFunction deserializeWrapper(CompoundTag tag) {
        var type = REGISTRY.get(tag.getString("_type"));
        if (type != null) {
            try {
                var function = type.getConstructor().newInstance();
                function.deserializeNBT(tag);
                return function;
            } catch (Throwable ignored) {}
        }
        return constant(0);
    }

    void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator);

    default Number get(RandomSource randomSource, float t) {
        return get(t, randomSource::nextFloat);
    }

    Number get(float t, Supplier<Float> lerp);

}
