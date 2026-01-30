package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.nbt.CompoundTag;

public class EmptyMapper implements Mapper{


    public static CompoundTag mapEmpty(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", "empty");
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getString("name"));
        dataTag.put("transform", MapperUtils.mapTransformTag(emitterTag.getCompound("transform")));

        newEmitterTag.put("data", dataTag);


        return newEmitterTag;
    }
}
