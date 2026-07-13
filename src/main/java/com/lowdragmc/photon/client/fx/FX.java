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

/**
 * An FX definition: the authored object tree + timeline, as saved by the editor / a {@code .fx} file.
 * <p>
 * Loaded via {@link FXHelper#getFX} (which also sets {@link #getFxLocation()}). A definition is not
 * playable by itself — call {@link #createRuntime()} for a playable instance; each runtime works on
 * its own copy of the data, so one definition can play many times concurrently.
 */
@Getter
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class FX implements INBTSerializable<CompoundTag> {
    public static final String SUFFIX = ".fx";
    @Nullable
    @Setter
    private ResourceLocation fxLocation;
    private final FXData fxData;

    public FX() {
        fxData = new FXData();
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        tag.put("fxData", fxData.serializeNBT(provider));
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        fxData.deserializeNBT(provider, tag.getCompound("fxData"));
    }

    /**
     * Create a runtime of this FX.
     * @return a runtime of this FX
     */
    public FXRuntime createRuntime() {
        return createRuntime(false);
    }

    /**
     * Create a runtime of this FX.
     * @param deepCopy if true, deep copy the data
     * @return a runtime of this FX
     */
    public FXRuntime createRuntime(boolean deepCopy) {
        return new FXRuntime(fxData.copy(deepCopy));
    }

    /**
     * Create a runtime of this FX which use the raw data.
     */
    public FXRuntime createInternalRuntime() {
        return new FXRuntime(fxData);
    }

}
