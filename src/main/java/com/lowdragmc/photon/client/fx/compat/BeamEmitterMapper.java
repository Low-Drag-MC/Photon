package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.nbt.CompoundTag;

public class BeamEmitterMapper implements Mapper{

    public static CompoundTag mapBeamEmitter(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", emitterTag.getString("_type") + "_emitter");
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getString("name"));
        dataTag.putInt("version",  emitterTag.getInt("_version"));
        dataTag.put("transform", MapperUtils.mapTransformTag(emitterTag.getCompound("transform")));
        dataTag.put("config", mapBeamConfig(emitterTag.getCompound("config")));

        newEmitterTag.put("data", dataTag);


        return newEmitterTag;
    }

    public static CompoundTag mapBeamConfig(CompoundTag configTag){
        CompoundTag newBeamConfigTag = new CompoundTag();
        newBeamConfigTag.putInt("duration",  configTag.getInt("duration"));
        newBeamConfigTag.putByte("looping", configTag.getByte("looping"));

        newBeamConfigTag.put("color", MapperUtils.mapTypedValue(configTag.getCompound("color"), false));
        newBeamConfigTag.put("end", MapperUtils.mapCoords(configTag.getCompound("end")));
        newBeamConfigTag.put("renderer", MapperUtils.mapRendererTag(configTag.getCompound("renderer"), configTag));
        newBeamConfigTag.put("uvAnimation", MapperUtils.mapUVTag(configTag.getCompound("uvAnimation")));
        newBeamConfigTag.put("width", MapperUtils.mapTypedValue(configTag.getCompound("width"), false));
        newBeamConfigTag.put("lights", MapperUtils.mapLightTag(configTag.getCompound("lights")));
        newBeamConfigTag.put("emitRate", MapperUtils.mapTypedValue(configTag.getCompound("emitRate"), false));

        newBeamConfigTag.putString("raycastBlockMode", "VISUAL");

        return newBeamConfigTag;
    }
}
