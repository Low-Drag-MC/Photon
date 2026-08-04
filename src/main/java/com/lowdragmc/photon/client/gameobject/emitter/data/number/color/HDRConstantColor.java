package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.configurator.ui.HDRColorConfigurator;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import lombok.Getter;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The HDR counterpart of {@link Color}: one constant {@link HDRColor}.
 *
 * <p>Deliberately not derived from {@code Constant} — its {@code @Persisted Number} can't hold an HDR
 * colour, and inheriting it would also make this droppable onto every scalar field (the drag predicate
 * keys off the configurator's default value type, which is a {@code Constant}).
 */
@LDLRegisterClient(name = "hdr_color", registry = "photon:number_function")
public class HDRConstantColor implements HDRColorFunction {

    @Getter
    @Persisted
    private HDRColor color;

    public HDRConstantColor() {
        this(HDRColor.white());
    }

    public HDRConstantColor(HDRColor color) {
        this.color = color;
    }

    public void setColor(HDRColor color) {
        this.color = color == null ? HDRColor.white() : color;
    }

    @Override
    public void loadConfig(NumberFunctionConfig config) {
        this.color = HDRColor.fromARGB((int) config.defaultValue());
    }

    @Override
    public void sampleHDR(float t, Supplier<Float> lerp, Vector4f out) {
        out.set(color.getR() * color.getIntensity(), color.getG() * color.getIntensity(),
                color.getB() * color.getIntensity(), color.getA());
    }

    @Override
    public NumberFunction copy() {
        return new HDRConstantColor(color.copy());
    }

    @Override
    public void createConfigurator(NumberFunctionConfigurator configurator) {
        configurator.inlineContainer.addChildren(new HDRColorConfigurator("", () -> color, hdr -> {
            setColor(hdr);
            configurator.updateValue(this);
        }, color.copy(), true));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        return obj instanceof HDRConstantColor other && Objects.equals(color, other.color);
    }

    @Override
    public int hashCode() {
        return Objects.hash(color);
    }
}
