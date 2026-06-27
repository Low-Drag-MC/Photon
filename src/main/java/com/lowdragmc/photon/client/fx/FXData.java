package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.fx.timeline.Timeline;
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
public final class FXData implements INBTSerializable<CompoundTag> {
    private final List<IFXObject> objects;
    private final Timeline timeline;

    public FXData() {
        this(new ArrayList<>(), new Timeline());
    }

    public FXData(List<IFXObject> objects) {
        this(objects, new Timeline());
    }

    public FXData(List<IFXObject> objects, Timeline timeline) {
        this.objects = objects;
        this.timeline = timeline;
    }

    public List<IFXObject> objects() {
        return objects;
    }

    /**
     * The timeline that choreographs the child objects. Empty by default (and for legacy FX with no
     * timeline data), in which case the runtime behaves exactly as before.
     */
    public Timeline timeline() {
        return timeline;
    }

    public FXData copy(boolean deepCopy) {
        return new FXData(
                objects.stream().map(obj -> obj.copy(deepCopy)).collect(Collectors.toList()),
                timeline.copy());
    }

    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        var fxObjects = new ListTag();
        for (var fxObject : objects) {
            fxObjects.add(fxObject.serializeWrapper());
        }
        tag.put("fxObjects", fxObjects);
        // only write timeline data when present, keeping legacy files byte-identical
        if (!timeline.isEmpty()) {
            tag.put("timeline", timeline.serializeNBT(provider));
        }
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
        // tolerate a missing "timeline" tag: legacy FX deserialize to an empty timeline
        timeline.deserializeNBT(provider, tag.getCompound("timeline"));
    }
}
