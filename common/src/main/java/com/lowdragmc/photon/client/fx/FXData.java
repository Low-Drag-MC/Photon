package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.List;

public record FXData(List<IFXObject> objects) implements ITagSerializable<CompoundTag> {

    public FXData() {
        this(new ArrayList<>());
    }

    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        var fxObjects = new ListTag();
        for (var fxObject : objects) {
            fxObjects.add(fxObject.serializeNBT());
        }
        tag.put("fxObjects", fxObjects);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
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
