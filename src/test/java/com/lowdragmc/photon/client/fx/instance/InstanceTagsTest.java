package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstanceTagsTest {

    /** 26.1 lists hold mixed elements as they are; 1.21's typed read gave nothing for a list of non-compounds. */
    @Test
    void anElementThatIsNoCompoundIsNoObject() {
        var object = new CompoundTag();
        object.putString(InstanceTags.TYPE, "particle");
        var objects = new ListTag();
        objects.add(StringTag.valueOf("_NULL_"));
        objects.add(object);
        var fxData = new CompoundTag();
        fxData.put(InstanceTags.OBJECTS, objects);
        assertEquals(List.of(object), InstanceTags.objects(fxData));
    }
}
