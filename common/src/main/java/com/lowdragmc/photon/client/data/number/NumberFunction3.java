package com.lowdragmc.photon.client.data.number;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.Vector3;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote NumberFunction3
 */
public class NumberFunction3 {

    @Persisted
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

    public Vector3 get(RandomSource randomSource, float t) {
        return new Vector3(x.get(randomSource, t).doubleValue(), y.get(randomSource, t).doubleValue(), z.get(randomSource, t).doubleValue());
    }

    public Vector3 get(float t, Supplier<Float> lerp) {
        return new Vector3(x.get(t, lerp).doubleValue(), y.get(t, lerp).doubleValue(), z.get(t, lerp).doubleValue());
    }

}
