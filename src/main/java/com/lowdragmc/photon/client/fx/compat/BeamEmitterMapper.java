package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.nbt.CompoundTag;

public class BeamEmitterMapper implements Mapper{

    public static CompoundTag mapBeamEmitter(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", emitterTag.getStringOr("_type", "") + "_emitter");
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getStringOr("name", ""));
        dataTag.putInt("version",  emitterTag.getIntOr("_version", 0));
        dataTag.put("transform", MapperUtils.mapTransformTag(emitterTag.getCompoundOrEmpty("transform")));
        dataTag.put("config", mapBeamConfig(emitterTag.getCompoundOrEmpty("config")));

        newEmitterTag.put("data", dataTag);


        return newEmitterTag;
    }

    public static CompoundTag mapBeamConfig(CompoundTag configTag){
        CompoundTag newBeamConfigTag = new CompoundTag();
        newBeamConfigTag.putInt("duration",  configTag.getIntOr("duration", 0));
        newBeamConfigTag.putByte("looping", configTag.getByteOr("looping", (byte) 0));

        newBeamConfigTag.put("color", MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("color"), false));
        newBeamConfigTag.put("end", MapperUtils.mapCoords(configTag.getCompoundOrEmpty("end")));
        newBeamConfigTag.put("renderer", MapperUtils.mapRendererTag(configTag.getCompoundOrEmpty("renderer"), configTag));
        newBeamConfigTag.put("uvAnimation", MapperUtils.mapUVTag(configTag.getCompoundOrEmpty("uvAnimation")));
        newBeamConfigTag.put("width", MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("width"), false));
        newBeamConfigTag.put("lights", MapperUtils.mapLightTag(configTag.getCompoundOrEmpty("lights")));
        newBeamConfigTag.put("emitRate", MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("emitRate"), false));

        newBeamConfigTag.putString("raycastBlockMode", "VISUAL");

        return newBeamConfigTag;
    }
}
