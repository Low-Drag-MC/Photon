package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.google.common.base.Suppliers;
import org.joml.Vector3f;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote NumberFunction3
 */
public class NumberFunction3 {
    public NumberFunction x, y, z;

    public NumberFunction3(NumberFunction x, NumberFunction y, NumberFunction z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public NumberFunction3(Number x, Number y, Number z) {
        this.x = NumberFunction.constant(x);
        this.y = NumberFunction.constant(y);
        this.z = NumberFunction.constant(z);
    }

    public Vector3f get(RandomSource randomSource, float t) {
        var lerp = Suppliers.memoize(randomSource::nextFloat);
        return new Vector3f(x.get(t, lerp).floatValue(), y.get(t, lerp).floatValue(), z.get(t, lerp).floatValue());
    }

    public Vector3f get(float t, Supplier<Float> lerp) {
        return new Vector3f(x.get(t, lerp).floatValue(), y.get(t, lerp).floatValue(), z.get(t, lerp).floatValue());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        return obj instanceof NumberFunction3 numberFunction3 &&
                x.equals(numberFunction3.x) &&
                y.equals(numberFunction3.y) &&
                z.equals(numberFunction3.z);
    }
}
