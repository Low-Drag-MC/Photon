package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;

public class ParticleEmitterMapper implements Mapper{
    public static CompoundTag mapParticleEmitter(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", emitterTag.getString("_type"));
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getString("name"));
        dataTag.putInt("version",  emitterTag.getInt("_version"));
        dataTag.put("transform", mapTransformTag(emitterTag.getCompound("transform")));



        return newEmitterTag;
    }

    public static CompoundTag mapTransformTag(CompoundTag transformTag){
        CompoundTag newTransformTag = new CompoundTag();
        newTransformTag.put("_childrenId", new ListTag());
        newTransformTag.putString("_parentId", NbtUtils.loadUUID(transformTag.get("_parentID")).toString());
        newTransformTag.putString("id", NbtUtils.loadUUID(transformTag.get("id")).toString());
        newTransformTag.put("localRotation", MapperUtils.mapCoords(transformTag.get("localRotation")));
        newTransformTag.put("localScale", MapperUtils.mapCoords(transformTag.get("localScale")));
        newTransformTag.put("localPosition",  MapperUtils.mapCoords(transformTag.get("localPosition")));

        return newTransformTag;
    }

    public static CompoundTag mapConfigTag(CompoundTag configTag){
        CompoundTag newConfigTag = new CompoundTag();
        newConfigTag.putString("simulationSpace",  configTag.getString("simulationSpace"));
        newConfigTag.put("startLifetime", MapperUtils.mapTypedValue(configTag.getCompound("startLifetime")));
        newConfigTag.putInt("prewarm", 0);
        newConfigTag.putInt("maxParticles", configTag.getInt("maxParticles"));
        newConfigTag.putInt("duration", configTag.getInt("duration"));
        newConfigTag.putByte("parallelRendering", configTag.getByte("parallelRendering"));
        newConfigTag.putByte("looping",  configTag.getByte("looping"));
        newConfigTag.putByte("parallelUpdate",  configTag.getByte("parallelUpdate"));
        newConfigTag.put("startDelay", MapperUtils.mapTypedValue(configTag.getCompound("startDelay")));
        newConfigTag.put("sizeOverLifetime",  MapperUtils.mapTypedValue(configTag.getCompound("sizeOverLifetime")));
        newConfigTag.put("startRotation", MapperUtils.mapTyped3Vec(configTag.getCompound("startRotation"), null));
        newConfigTag.put()

    }

    public static CompoundTag mapRendererTag(CompoundTag renderTag, CompoundTag configTag){
        CompoundTag newRendererTag = new CompoundTag();
        newRendererTag.putString("renderMode", renderTag.getString("renderMode"));
        newRendererTag.putByte("useBlockUV",  renderTag.getByte("useBlockUV"));
        newRendererTag.putByte("shade",  renderTag.getByte("shade"));
        newRendererTag.putString("layer",  renderTag.getString("layer"));
        newRendererTag.put("cull", mapCullTag(renderTag.getCompound("cull")));
        return newRendererTag;
    }

    public static CompoundTag mapCullTag(CompoundTag cullTag){
        CompoundTag newCullTag = new CompoundTag();
        newCullTag.putByte("_enable", cullTag.getByte("_enable"));
        if(newCullTag.getByte("_enable") == 1){
            newCullTag.put("min", MapperUtils.mapCoords(cullTag.get("from")));
            newCullTag.put("max", MapperUtils.mapCoords(cullTag.get("to")));
        }
        return newCullTag;
    }

    public static CompoundTag mapRotationBySpeedTag(CompoundTag rotationTag){
        CompoundTag newRotationTag = new CompoundTag();
        newRotationTag.putByte("_enable", rotationTag.getByte("enable"));
        if(newRotationTag.getByte("enable") == 1){
            newRotationTag.put("speedRange",  rotationTag.getCompound("speedRange"));
            newRotationTag.put("roll", MapperUtils.mapTypedValue(rotationTag.getCompound("roll")));
            newRotationTag.put("pitch", MapperUtils.mapTypedValue(rotationTag.getCompound("pitch")));
            newRotationTag.put("yaw", MapperUtils.mapTypedValue(rotationTag.getCompound("yaw")));
        }
        return newRotationTag;
    }

    public static CompoundTag mapSizeOverLifeTimeTag(CompoundTag sizeOverLifeTimeTag){
        CompoundTag newSizeOverLifeTimeTag = new CompoundTag();
        newSizeOverLifeTimeTag.putByte("_enable", sizeOverLifeTimeTag.getByte("enable"));
        if(newSizeOverLifeTimeTag.getByte("enable") == 1){
            newSizeOverLifeTimeTag.put("size", MapperUtils.mapTyped3Vec(sizeOverLifeTimeTag.getCompound("size"), null));
        }
        return newSizeOverLifeTimeTag;
    }

    public static CompoundTag mapForceOTTag(CompoundTag forceOTTag){
        CompoundTag newForceOTTag = new CompoundTag();
        newForceOTTag.putByte("_enable", forceOTTag.getByte("enable"));
        if(newForceOTTag.getByte("enable") == 1){
            newForceOTTag.putString("simulationSpace", forceOTTag.getString("simulationSpace"));
            newForceOTTag.put("force", MapperUtils.mapTyped3Vec(forceOTTag.getCompound("force"), null));
        }
        return newForceOTTag;
    }

    public static CompoundTag mapLightTag(CompoundTag lightTag){
        CompoundTag newLightTag = new CompoundTag();
        newLightTag.putByte("_enable", lightTag.getByte("enable"));
        if(newLightTag.getByte("enable") == 1){
            newLightTag.put("blockLight", MapperUtils.mapTypedValue(lightTag.getCompound("blockLight")));
            newLightTag.put("skyLight", MapperUtils.mapTypedValue(lightTag.getCompound("skyLight")));
        }
        return newLightTag;
    }

    public static CompoundTag mapNoiseTag(CompoundTag noiseTag){
        CompoundTag newNoiseTag = new CompoundTag();
        newNoiseTag.putByte("_enable", noiseTag.getByte("enable"));
        if(newNoiseTag.getByte("enable") == 1){
            newNoiseTag.put("size", MapperUtils.mapTypedValue(noiseTag.getCompound("size")));
            newNoiseTag.put("rotation", MapperUtils.mapTypedValue(noiseTag.getCompound("rotation")));
            newNoiseTag.put("position", MapperUtils.mapTyped3Vec(noiseTag.getCompound("position"), null));
            CompoundTag remap = new CompoundTag();
            remap.putByte("_enable",  noiseTag.getCompound("remap").getByte("enable"));
            if(remap.getByte("enable") == 1){
                remap.put("remapCurve", MapperUtils.mapTypedValue(noiseTag.getCompound("remap").getCompound("remapCurve")));
            }
            newNoiseTag.put("remap", remap);
            newNoiseTag.putString("quality",  noiseTag.getString("quality"));
            newNoiseTag.putFloat("frequency",   noiseTag.getFloat("frequency"));
        }

        return newNoiseTag;
    }

    public static CompoundTag mapPhysicsTag(CompoundTag physicsTag){
        CompoundTag newPhysicsTag = new CompoundTag();
        newPhysicsTag.putByte("_enable", physicsTag.getByte("enable"));
        if(newPhysicsTag.getByte("enable") == 1){
            newPhysicsTag.putByte("hasCollision",  physicsTag.getByte("hasCollision"));
            newPhysicsTag.putByte("removeWhenCollided",  physicsTag.getByte("removeWhenCollided"));
            newPhysicsTag.put("friction", MapperUtils.mapTypedValue(physicsTag.getCompound("friction")));
            newPhysicsTag.put("gravity", MapperUtils.mapTypedValue(physicsTag.getCompound("gravity")));
            newPhysicsTag.put("bounceSpreadRate", MapperUtils.mapTypedValue(physicsTag.getCompound("bounceSpreadRate")));
            newPhysicsTag.put("bounceRate", MapperUtils.mapTypedValue(physicsTag.getCompound("bounceRate")));
            newPhysicsTag.put("bounceChance", MapperUtils.mapTypedValue(physicsTag.getCompound("bounceChance")));
        }
        return newPhysicsTag;
    }

    public static CompoundTag mapEmissionTag(CompoundTag emissionTag){
        CompoundTag newEmissionTag = new CompoundTag();
        newEmissionTag.put("emissionRate", MapperUtils.mapTypedValue(emissionTag.getCompound("emissionRate")));
        newEmissionTag.putString("emissionMode",  emissionTag.getString("emissionMode"));
        CompoundTag bursts = new CompoundTag();
        bursts.put("payload", new ListTag());
        bursts.putInt("uid", 0);
        newEmissionTag.put("bursts",  bursts);
        CompoundTag distanceRate = new CompoundTag();
        distanceRate.putString("type", "constant");
        CompoundTag dRData = new CompoundTag();
        dRData.putInt("number", 0);
        distanceRate.put("data",  dRData);
        newEmissionTag.put("distanceRate", distanceRate);

        return  newEmissionTag;

    }

    public static CompoundTag mapSizeBySpeedTag(CompoundTag sizeBySpeedTag){
        CompoundTag newSizeBySpeedTag = new CompoundTag();
        newSizeBySpeedTag.putByte("_enable", sizeBySpeedTag.getByte("enable"));
        if(newSizeBySpeedTag.getByte("enable") == 1){
            newSizeBySpeedTag.put("speedRange", sizeBySpeedTag.getCompound("speedRange"));
            newSizeBySpeedTag.put("size", MapperUtils.mapTyped3Vec(sizeBySpeedTag.getCompound("size"), null));
        }

        return newSizeBySpeedTag;
    }

    public static CompoundTag mapVelocityOTTag(CompoundTag velocityOTTag){
        CompoundTag newVelocityOTTag = new CompoundTag();
        newVelocityOTTag.putByte("_enable", velocityOTTag.getByte("enable"));
        if(newVelocityOTTag.getByte("enable") == 1){
            newVelocityOTTag.put("speedModifier",  MapperUtils.mapTypedValue(velocityOTTag.getCompound("speedModifier")));
            newVelocityOTTag.putString("orbitalMode",   velocityOTTag.getString("orbitalMode"));
            newVelocityOTTag.put("offset",  MapperUtils.mapTyped3Vec(velocityOTTag.getCompound("offset"), null));
            newVelocityOTTag.put("orbital", MapperUtils.mapTyped3Vec(velocityOTTag.getCompound("orbital"), null));
            newVelocityOTTag.put("linear",  MapperUtils.mapTyped3Vec(velocityOTTag.getCompound("linear"), null));

        }

        return newVelocityOTTag;
    }

    public static CompoundTag mapRotationOLTTag(CompoundTag rotationOLTTag){
        CompoundTag newRotationOLTTag = new CompoundTag();
        newRotationOLTTag.putByte("_enable", rotationOLTTag.getByte("enable"));
        if(newRotationOLTTag.getByte("enable") == 1){
            newRotationOLTTag.put("roll", MapperUtils.mapTypedValue(rotationOLTTag.getCompound("roll")));
            newRotationOLTTag.put("pitch", MapperUtils.mapTypedValue(rotationOLTTag.getCompound("pitch")));
            newRotationOLTTag.put("yaw", MapperUtils.mapTypedValue(rotationOLTTag.getCompound("yaw")));

        }

        return newRotationOLTTag;
    }

    public static CompoundTag mapShapeTag(CompoundTag shapeTag){
        CompoundTag newShapeTag = new CompoundTag();
        newShapeTag.put("rotation", MapperUtils.mapTyped3Vec(shapeTag.getCompound("rotation"), null));
        newShapeTag.put("scale",  MapperUtils.mapTyped3Vec(shapeTag.getCompound("scale"), null));
        newShapeTag.put("position",  MapperUtils.mapTyped3Vec(shapeTag.getCompound("position"), null));
        //Need to map shape here...

        return newShapeTag;
    }
}
