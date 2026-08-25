package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.configurator.ui.HDRColorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import lombok.Getter;
import net.minecraft.util.Mth;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The HDR counterpart of {@link RandomColor}: a random blend between two {@link HDRColor}s. The blend
 * happens in float space (no ARGB round-trip), so intensities above 1 survive it.
 */
@LDLRegisterClient(name = "hdr_random_color", registry = "photon:number_function")
public class HDRRandomColor implements HDRColorFunction {

    @Getter
    @Persisted
    private HDRColor colorA;
    @Getter
    @Persisted
    private HDRColor colorB;

    public HDRRandomColor() {
        this(HDRColor.black(), HDRColor.white());
    }

    public HDRRandomColor(HDRColor colorA, HDRColor colorB) {
        this.colorA = colorA;
        this.colorB = colorB;
    }

    public void setColorA(HDRColor color) {
        this.colorA = color == null ? HDRColor.black() : color;
    }

    public void setColorB(HDRColor color) {
        this.colorB = color == null ? HDRColor.white() : color;
    }

    @Override
    public void loadConfig(NumberFunctionConfig config) {
        var color = (int) config.defaultValue();
        this.colorA = HDRColor.fromARGB(color);
        this.colorB = HDRColor.fromARGB(color);
    }

    @Override
    public void sampleHDR(float t, Supplier<Float> lerp, Vector4f out) {
        var a = colorA.toVector4f();
        var b = colorB.toVector4f();
        var blend = lerp.get();
        out.set(Mth.lerp(blend, a.x, b.x), Mth.lerp(blend, a.y, b.y),
                Mth.lerp(blend, a.z, b.z), Mth.lerp(blend, a.w, b.w));
    }

    @Override
    public NumberFunction copy() {
        return new HDRRandomColor(colorA.copy(), colorB.copy());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        HDRColorConfigurator a, b;
        configurator.inlineContainer.addChild(new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.gapAll(2);
            layout.marginLeft(2);
            layout.flexDirection(FlexDirection.ROW);
            layout.wrap(FlexWrap.WRAP);
        }).addChildren(
                a = new HDRColorConfigurator("", () -> colorA, color -> {
                    setColorA(color);
                    configurator.updateValue(this);
                }, colorA.copy(), true),
                b = new HDRColorConfigurator("", () -> colorB, color -> {
                    setColorB(color);
                    configurator.updateValue(this);
                }, colorB.copy(), true)
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

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        return obj instanceof HDRRandomColor other
                && Objects.equals(colorA, other.colorA)
                && Objects.equals(colorB, other.colorB);
    }

    @Override
    public int hashCode() {
        return Objects.hash(colorA, colorB);
    }
}
