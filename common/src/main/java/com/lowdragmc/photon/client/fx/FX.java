package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.*;

/**
 * FX is a definition of a FX.
 * <br>
 * In general, use {@link #createRuntime()} to create a runtime of this FX.
 */
@Getter
public class FX implements ITagSerializable<CompoundTag> {
    @Nullable
    @Setter
    private ResourceLocation fxLocation;
    private final FXData mainFX;
    private final Map<String, FXData> subFXs = new LinkedHashMap<>();

    public FX() {
        mainFX = new FXData();
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.put("mainFX", mainFX.serializeNBT());
        var subFXs = new CompoundTag();
        for (var entry : this.subFXs.entrySet()) {
            subFXs.put(entry.getKey(), entry.getValue().serializeNBT());
        }
        tag.put("subFXs", subFXs);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        mainFX.deserializeNBT(tag.getCompound("mainFX"));
        subFXs.clear();
        var subFXs = tag.getCompound("subFXs");
        for (var key : subFXs.getAllKeys()) {
            var subFX = new FXData();
            subFX.deserializeNBT(subFXs.getCompound(key));
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
