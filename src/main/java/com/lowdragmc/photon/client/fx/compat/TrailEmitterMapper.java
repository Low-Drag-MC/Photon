package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.nbt.CompoundTag;

public class TrailEmitterMapper implements Mapper {

    public static CompoundTag mapTrailEmitter(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", emitterTag.getStringOr("_type", "") + "_emitter");
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getStringOr("name", ""));
        dataTag.putInt("version",  emitterTag.getIntOr("_version", 0));
        dataTag.put("transform", MapperUtils.mapTransformTag(emitterTag.getCompoundOrEmpty("transform")));
        dataTag.put("config", mapTrailConfig(emitterTag.getCompoundOrEmpty("config")));

        newEmitterTag.put("data", dataTag);

        return newEmitterTag;
    }

    public static CompoundTag mapTrailConfig(CompoundTag trailConfigTag){
        CompoundTag newTrailConfigTag = new CompoundTag();
        newTrailConfigTag.putInt("startDelay",  trailConfigTag.getByteOr("startDelay", (byte) 0));
        newTrailConfigTag.put("lights", MapperUtils.mapLightTag(trailConfigTag.getCompoundOrEmpty("lights")));
        newTrailConfigTag.put("uvAnimation", MapperUtils.mapUVTag(trailConfigTag.getCompoundOrEmpty("uvAnimation")));
        newTrailConfigTag.put("colorOverTrail", MapperUtils.mapTypedValue(trailConfigTag.getCompoundOrEmpty("colorOverTrail"), false));
        newTrailConfigTag.put("widthOverTrail",  MapperUtils.mapTypedValue(trailConfigTag.getCompoundOrEmpty("widthOverTrail"), false));
        newTrailConfigTag.putFloat("minVertexDistance",  trailConfigTag.getFloatOr("minVertexDistance", 0.0F));
        newTrailConfigTag.putInt("time",  trailConfigTag.getIntOr("time", 0));
        newTrailConfigTag.putString("uvMode",  trailConfigTag.getStringOr("uvMode", ""));
        newTrailConfigTag.putInt("duration",   trailConfigTag.getIntOr("duration", 0));
        newTrailConfigTag.put("renderer", MapperUtils.mapRendererTag(trailConfigTag.getCompoundOrEmpty("renderer"),trailConfigTag));
        newTrailConfigTag.putByte("smoothInterpolation",  trailConfigTag.getByteOr("smoothInterpolation", (byte) 0));
        newTrailConfigTag.putByte("looping", trailConfigTag.getByteOr("looping", (byte) 0));

        return  newTrailConfigTag;
    }
}
