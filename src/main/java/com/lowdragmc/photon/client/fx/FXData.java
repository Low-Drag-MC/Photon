package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public record FXData(List<IFXObject> objects) implements INBTSerializable<CompoundTag> {

    public FXData() {
        this(new ArrayList<>());
    }

    public FXData copy(boolean deepCopy) {
        return new FXData(objects.stream().map(obj -> obj.copy(deepCopy)).collect(Collectors.toList()));
    }

    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        var fxObjects = new ListTag();
        for (var fxObject : objects) {
            fxObjects.add(fxObject.serializeWrapper());
        }
        tag.put("fxObjects", fxObjects);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        objects.clear();
        var list = tag.getList("fxObjects", ListTag.TAG_COMPOUND);
        for (var nbt : list) {
            if (nbt instanceof CompoundTag data) {
                var fxObject = IFXObject.deserializeWrapper(data);
                if (fxObject != null) {
                    objects.add(fxObject);
                }
            }
        }
    }
}
