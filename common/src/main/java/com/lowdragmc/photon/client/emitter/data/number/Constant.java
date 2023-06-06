package com.lowdragmc.photon.client.emitter.data.number;

import com.lowdragmc.lowdraglib.gui.editor.configurator.NumberConfigurator;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Constant
 */
public class Constant implements NumberFunction {

    @Setter
    @Getter
    private Number number;

    public Constant() {
        number = 0;
    }

    public Constant(Number number) {
        this.number = number;
    }

    public Constant(NumberFunctionConfig config) {
        this(config.isDecimals() ? config.defaultValue() : ((int) config.defaultValue()));
    }

    @Override
    public Number get(RandomSource randomSource, float t) {
        return number;
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        return number;
    }

    @Override
    public void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator) {
        var widget = new NumberConfigurator("", () -> configurator.getConfig().isDecimals() ? number.floatValue() : number.intValue(), number -> {
            setNumber(number);
            configurator.updateValue();;
        }, number, true);
        group.addWidget(widget);
        widget.setRange(configurator.getConfig().min(), configurator.getConfig().max());
        widget.setWheel(configurator.getConfig().isDecimals() ? configurator.getConfig().wheelDur() : Math.max(1, (int) configurator.getConfig().wheelDur()));
        widget.setConfigPanel(configurator.getConfigPanel(), configurator.getTab());
        widget.init(group.getSize().width);
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        if (number instanceof Float || number instanceof Double) {
            tag.putFloat("number", number.floatValue());
        } else {
            tag.putInt("number", number.intValue());
        }
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        if (nbt.contains("number", Tag.TAG_INT)) {
            number = nbt.getInt("number");
        } else {
            number = nbt.getFloat("number");
        }
    }
}
