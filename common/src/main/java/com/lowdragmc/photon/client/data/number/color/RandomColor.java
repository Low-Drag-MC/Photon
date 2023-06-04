package com.lowdragmc.photon.client.data.number.color;

import com.lowdragmc.lowdraglib.gui.editor.configurator.ColorConfigurator;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.client.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.data.number.RandomConstant;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

import static com.lowdragmc.lowdraglib.utils.ColorUtils.*;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote RandomColor
 */
public class RandomColor extends RandomConstant {
    public RandomColor() {
        this(0xff000000, 0xffffffff);
    }

    public RandomColor(Number a, Number b) {
        super(a, b, false);
    }

    public RandomColor(NumberFunctionConfig config) {
        super(config);
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        int colorA = getA().intValue();
        int colorB = getB().intValue();
        return ColorUtils.blendColor(colorA, colorB, lerp.get());
    }

    private int randomColor(RandomSource randomSource, int minA, int maxA, int minR, int maxR, int minG, int maxG, int minB, int maxB) {
        return  ((minR + randomSource.nextInt(maxA + 1 - minA)) << 24) |
                ((minR + randomSource.nextInt(maxR + 1 - minR)) << 16) |
                ((minG + randomSource.nextInt(maxG + 1 - minG)) << 8) |
                ((minB + randomSource.nextInt(maxB + 1 - minB))) ;
    }

    private int randomColor(RandomSource randomSource, int colorA, int colorB) {
        return randomColor(randomSource, Math.min(alphaI(colorA), alphaI(colorB)), Math.max(alphaI(colorA), alphaI(colorB)),
                Math.min(redI(colorA), redI(colorB)), Math.max(redI(colorA), redI(colorB)),
                Math.min(greenI(colorA), greenI(colorB)), Math.max(greenI(colorA), greenI(colorB)),
                Math.min(blueI(colorA), blueI(colorB)), Math.max(blueI(colorA), blueI(colorB)));
    }

    @Override
    public void createConfigurator(WidgetGroup group, NumberFunctionConfigurator configurator) {
        var size = group.getSize();
        var aGroup = new WidgetGroup(0, 0, size.width / 2, size.height);
        var bGroup = new WidgetGroup(size.width / 2, 0, size.width / 2, size.height);
        group.addWidget(aGroup);
        group.addWidget(bGroup);

        setupNumberConfigurator(size, aGroup, new ColorConfigurator("", () -> getA().intValue(), number -> {
            setA(number);
            configurator.updateValue();
        }, getA().intValue(), true), configurator);
        setupNumberConfigurator(size, bGroup, new ColorConfigurator("", () -> getB().intValue(), number -> {
            setB(number);
            configurator.updateValue();
        }, getB().intValue(), true), configurator);
    }

    private void setupNumberConfigurator(Size size, WidgetGroup group, ColorConfigurator widget, NumberFunctionConfigurator configurator) {
        group.addWidget(widget);
        widget.setConfigPanel(configurator.getConfigPanel(), configurator.getTab());
        widget.init(size.width / 2);
    }
}
