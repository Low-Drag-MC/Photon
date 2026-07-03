package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The single registry singleton for {@code "config"} animatable properties. It exists only to
 * {@link #deserialize} a persisted config property back into a concrete {@link ConfigPropertyType}
 * (reconstructed from the stored {@code path}/{@code valueType}/{@code label}); the live editor builds
 * its own full-metadata instances via {@link ConfigPropertyType#discover}.
 */
@OnlyIn(Dist.CLIENT)
public class ConfigPropertyTypeDispatcher implements AnimatedPropertyType {
    @LDLRegisterClient(name = "config", registry = "photon:animated_property")
    public static final ConfigPropertyTypeDispatcher INSTANCE = new ConfigPropertyTypeDispatcher();

    @Override
    public String name() {
        return "config";
    }

    @Override
    public int channelCount() {
        return 0;
    }

    @Override
    public String channelKey(int channel) {
        return "";
    }

    @Override
    public float[] capture(FXObject target) {
        return new float[0];
    }

    @Override
    public void apply(FXObject target, float[] values) {
    }

    @Override
    public AnimatedProperty deserialize(HolderLookup.Provider provider, CompoundTag tag) {
        var path = tag.getString("path");
        var valueType = ConfigValueType.valueOf(tag.getString("valueType"));
        var label = tag.contains("label") ? tag.getString("label") : path;
        var type = new ConfigPropertyType(path, valueType, label);
        return type.deserialize(provider, tag);
    }
}
