package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.configurator.ui.ColorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.util.RandomSource;
import dev.vfyjxf.taffy.style.FlexDirection;

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

    @Override
    public NumberFunction copy() {
        return new RandomColor(getA().intValue(), getB().intValue());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj == this) return true;
        if (obj instanceof RandomColor randomColor) {
            return super.equals(randomColor);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public Integer get(float t, Supplier<Float> lerp) {
        int colorA = getA().intValue();
        int colorB = getB().intValue();
        return ColorUtils.blendRGBColor(colorA, colorB, lerp.get());
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
        ColorConfigurator a, b;
        configurator.inlineContainer.addChild(new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.gapAll(2);
            layout.marginLeft(2);
            layout.flexDirection(FlexDirection.ROW);
            layout.wrap(FlexWrap.WRAP);
        }).addChildren(
                a = new ColorConfigurator("", () -> getA().intValue(), color -> {
                    setA(color);
                    configurator.updateValue(this);
                }, getA().intValue(), true),
                b = new ColorConfigurator("", () -> getB().intValue(), color -> {
                    setB(color);
                    configurator.updateValue(this);
                }, getB().intValue(), true)
        ));
        a.layout(layout -> {
            layout.flex(1);
            layout.minWidth(40);
            layout.height(14);
        });
        b.layout(layout -> {
            layout.flex(1);
            layout.minWidth(40);
            layout.height(14);
        });
    }

}
