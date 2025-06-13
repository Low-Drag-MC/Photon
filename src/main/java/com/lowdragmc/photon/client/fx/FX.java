package com.lowdragmc.photon.client.fx;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/**
 * FX is a definition of a FX.
 * <br>
 * In general, use {@link #createRuntime()} to create a runtime of this FX.
 */
@Getter
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class FX implements INBTSerializable<CompoundTag> {
    @Nullable
    @Setter
    private ResourceLocation fxLocation;
    private final FXData mainFX;
    private final Map<String, FXData> subFXs = new LinkedHashMap<>();

    public FX() {
        mainFX = new FXData();
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        tag.put("mainFX", mainFX.serializeNBT(provider));
        var subFXs = new CompoundTag();
        for (var entry : this.subFXs.entrySet()) {
            subFXs.put(entry.getKey(), entry.getValue().serializeNBT(provider));
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        mainFX.deserializeNBT(provider, tag.getCompound("mainFX"));
        var subFXs = tag.getCompound("subFXs");
        for (var key : subFXs.getAllKeys()) {
            var subFX = new FXData();
            subFX.deserializeNBT(provider, subFXs.getCompound(key));
            this.subFXs.put(key, subFX);
        }
    }

    /**
     * Create a runtime of this FX.
     * @return a runtime of this FX
     */
    public FXRuntime createRuntime() {
        return new FXRuntime(this, mainFX, true, false);
    }

    /**
     * Create a runtime of this FX.
     * @param deepCopy if true, deep copy the data
     * @return a runtime of this FX
     */
    public FXRuntime createRuntime(boolean deepCopy) {
        return new FXRuntime(this, mainFX, true, deepCopy);
    }

    /**
     * Create a runtime of this FX which use the raw data.
     */
    public FXRuntime createInternalRuntime() {
        return new FXRuntime(this, mainFX, false, false);
    }

    @Nullable
    public FXRuntime createSubFXRuntime(String name) {
        if (!subFXs.containsKey(name)) {
            return null;
        }
        return new FXRuntime(this, subFXs.get(name), true, false);
    }

}
