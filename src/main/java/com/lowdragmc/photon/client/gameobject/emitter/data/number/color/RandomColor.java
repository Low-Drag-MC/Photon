package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.gui.editor.configurator.NumberFunctionConfigurator;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

import static com.lowdragmc.lowdraglib2.utils.ColorUtils.*;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote RandomColor
 */
@LDLRegisterClient(name = "random_color", registry = "photon:number_function")
public class RandomColor extends RandomConstant {
    public RandomColor() {
        this(0xff000000, 0xffffffff);
    }

    public RandomColor(int a, int b) {
        super(a, b);
    }

    public RandomColor(NumberFunctionConfig config) {
        super(config);
    }

    @Override
    public NumberFunction copy() {
        return new RandomColor((int) getA(), (int) getB());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RandomColor randomColor) {
            return super.equals(randomColor);
        }
        return false;
    }

    @Override
    public Float get(float t, Supplier<Float> lerp) {
        int colorA = (int) getA();
        int colorB = (int) getB();
        return (float) ColorUtils.blendColor(colorA, colorB, lerp.get());
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
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        // TODO configurator
//        var size = group.getSize();
//        var aGroup = new WidgetGroup(0, 0, size.width / 2, size.height);
//        var bGroup = new WidgetGroup(size.width / 2, 0, size.width / 2, size.height);
//        group.addWidget(aGroup);
//        group.addWidget(bGroup);
//
//        setupNumberConfigurator(size, aGroup, new ColorConfigurator("", () -> getA().intValue(), number -> {
//            setA(number);
//            configurator.updateValue(this);
//        }, getA().intValue(), true), configurator);
//        setupNumberConfigurator(size, bGroup, new ColorConfigurator("", () -> getB().intValue(), number -> {
//            setB(number);
//            configurator.updateValue(this);
//        }, getB().intValue(), true), configurator);
    }

//    private void setupNumberConfigurator(Size size, WidgetGroup group, ColorConfigurator widget, NumberFunctionConfigurator configurator) {
//        group.addWidget(widget);
//        widget.setConfiguratorContainer(configurator.getConfiguratorContainer());
//        widget.init(size.width / 2);
//    }
}
