package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.nbt.CompoundTag;

public class TrailEmitterMapper implements Mapper {

    public static CompoundTag mapTrailEmitter(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", emitterTag.getString("_type") + "_emitter");
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getString("name"));
        dataTag.putInt("version",  emitterTag.getInt("_version"));
        dataTag.put("transform", MapperUtils.mapTransformTag(emitterTag.getCompound("transform")));
        dataTag.put("config", mapTrailConfig(emitterTag.getCompound("config")));

        newEmitterTag.put("data", dataTag);

        return newEmitterTag;
    }

    public static CompoundTag mapTrailConfig(CompoundTag trailConfigTag){
        CompoundTag newTrailConfigTag = new CompoundTag();
        newTrailConfigTag.putInt("startDelay",  trailConfigTag.getByte("startDelay"));
        newTrailConfigTag.put("lights", MapperUtils.mapLightTag(trailConfigTag.getCompound("lights")));
        newTrailConfigTag.put("uvAnimation", MapperUtils.mapUVTag(trailConfigTag.getCompound("uvAnimation")));
        newTrailConfigTag.put("colorOverTrail", MapperUtils.mapTypedValue(trailConfigTag.getCompound("colorOverTrail"), false));
        newTrailConfigTag.put("widthOverTrail",  MapperUtils.mapTypedValue(trailConfigTag.getCompound("widthOverTrail"), false));
        newTrailConfigTag.putFloat("minVertexDistance",  trailConfigTag.getFloat("minVertexDistance"));
        newTrailConfigTag.putInt("time",  trailConfigTag.getInt("time"));
        newTrailConfigTag.putString("uvMode",  trailConfigTag.getString("uvMode"));
        newTrailConfigTag.putInt("duration",   trailConfigTag.getInt("duration"));
        newTrailConfigTag.put("renderer", MapperUtils.mapRendererTag(trailConfigTag.getCompound("renderer"),trailConfigTag));
        newTrailConfigTag.putByte("smoothInterpolation",  trailConfigTag.getByte("smoothInterpolation"));
        newTrailConfigTag.putByte("parallelRendering",   trailConfigTag.getByte("parallelRendering"));
        newTrailConfigTag.putByte("looping", trailConfigTag.getByte("looping"));

        return  newTrailConfigTag;
    }
}
