package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.configurator.NumberFunctionConfigurator;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/25
 * @implNote NumberFunction
 */
public interface NumberFunction extends IPersistedSerializable, ILDLRegisterClient<NumberFunction, Supplier<NumberFunction>> {
    NumberFunction ZERO = NumberFunction.constant(0);
    Codec<NumberFunction> CODEC = PhotonRegistries.NUMBER_FUNCTIONS.optionalCodec().dispatch(ILDLRegisterClient::getRegistryHolderOptional,
            optional -> optional.map(holder ->
                            PersistedParser.createCodec(holder.value()).fieldOf("data"))
                    .orElseGet(() -> MapCodec.unit(ZERO)));

    static NumberFunction constant(Number constant) {
        return new Constant(constant);
    }

    static NumberFunction color(int color) {
        return new Color(color);
    }

    default CompoundTag serializeWrapper() {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).result().orElse(new CompoundTag());
    }

    static NumberFunction deserializeWrapper(Tag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(ZERO);
    }

    static NumberFunction copy(NumberFunction function) {
        return function.copy();
    }

    static boolean isEqual(NumberFunction a, NumberFunction b) {
        return a.equals(b);
    }

    void loadConfig(NumberFunctionConfig config);

    /**
     * Copy a new instance of this number function
     */
    NumberFunction copy();

    void createConfigurator(NumberFunctionConfigurator configurator);

    default Number get(RandomSource randomSource, float t) {
        return get(t, randomSource::nextFloat);
    }

    Number get(float t, Supplier<Float> lerp);

}
