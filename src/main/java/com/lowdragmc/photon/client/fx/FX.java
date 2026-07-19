package com.lowdragmc.photon.client.fx;

import lombok.Getter;
import lombok.Setter;
import com.mojang.logging.annotations.MethodsReturnNonnullByDefault;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

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
/*
 * Serialization note (26.1): NeoForge removed INBTSerializable. The .fx file format is Photon-owned
 * tag composition, so these classes keep their Tag-based serializeNBT/deserializeNBT methods as plain
 * methods to stay byte-compatible with existing assets; ValueIOSerializable is only adopted where
 * LDLib2's PersistedParser requires it (see CurveTexture/GradientTexture/MeshData).
 */
public class FX {
    public static final String SUFFIX = ".fx";
    @Nullable
    @Setter
    private Identifier fxLocation;
    private final FXData fxData;

    public FX() {
        fxData = new FXData();
    }

    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        tag.put("fxData", fxData.serializeNBT(provider));
        return tag;
    }

    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        fxData.deserializeNBT(provider, tag.getCompoundOrEmpty("fxData"));
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
