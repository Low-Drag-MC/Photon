package com.lowdragmc.photon.client.emitter.data.number;

import com.lowdragmc.lowdraglib.gui.editor.configurator.NumberConfigurator;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote RandomConstant
 */
public class RandomConstant implements NumberFunction {

    @Setter
    @Getter
    private Number a, b;
    @Setter
    @Getter
    private boolean isDecimals;

    public RandomConstant() {
        a = 0;
        b = 0;
    }

    public RandomConstant(Number a, Number b, boolean isDecimals) {
        this.a = a;
        this.b = b;
        this.isDecimals = isDecimals;
    }

    public RandomConstant(NumberFunctionConfig config) {
        this(config.defaultValue(), config.defaultValue(), config.isDecimals());
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        float min = Math.min(a.floatValue(), b.floatValue());
        float max = Math.max(a.floatValue(), b.floatValue());
        if (min == max) return max;
        if (isDecimals) return (min + lerp.get() * (max - min));
        return (int)(min + lerp.get() * (max + 1 - min));
    }

    @Override
    public void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator) {
        var size = group.getSize();
        int width;
        WidgetGroup aGroup, bGroup;
        if (size.width > 60) {
            width = size.width / 2;
            aGroup = new WidgetGroup(0, 0, width, size.height);
            bGroup = new WidgetGroup(width, 0, width, size.height);
        } else {
            width = size.width;
            aGroup = new WidgetGroup(0, 0, width, size.height);
            bGroup = new WidgetGroup(0, 15, width, size.height);
            group.setSize(new Size(size.width, size.height + 15));
        }

        group.addWidget(aGroup);
        group.addWidget(bGroup);
        setupNumberConfigurator(configurator, width, aGroup, new NumberConfigurator("", () -> isDecimals ? a.floatValue() : a.intValue(), number -> {
            setA(number);
            configurator.updateValue();
        }, a, true));
        setupNumberConfigurator(configurator, width, bGroup, new NumberConfigurator("", () -> isDecimals ? b.floatValue() : b.intValue(), number -> {
            setB(number);
            configurator.updateValue();
        }, b, true));

    }

    private void setupNumberConfigurator(NumberFunctionConfigurator configurator, int width, WidgetGroup group, NumberConfigurator widget) {
        group.addWidget(widget
                .setRange(configurator.getConfig().min(), configurator.getConfig().max())
                .setWheel(configurator.getConfig().isDecimals() ? configurator.getConfig().wheelDur() : Math.max(1, (int) configurator.getConfig().wheelDur())));
        widget.setConfigPanel(configurator.getConfigPanel(), configurator.getTab());
        widget.init(width);
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.putBoolean("isDecimals", isDecimals);
        if (a instanceof Float || a instanceof Double) {
            tag.putFloat("a", a.floatValue());
        } else {
            tag.putInt("a", a.intValue());
        }
        if (b instanceof Float || b instanceof Double) {
            tag.putFloat("b", b.floatValue());
        } else {
            tag.putInt("b", b.intValue());
        }
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        isDecimals = nbt.getBoolean("isDecimals");
        if (nbt.contains("a", Tag.TAG_INT)) {
            a = nbt.getInt("a");
        } else {
            a = nbt.getFloat("a");
        }
        if (nbt.contains("b", Tag.TAG_INT)) {
            b = nbt.getInt("a");
        } else {
            b = nbt.getFloat("b");
        }
    }
}
