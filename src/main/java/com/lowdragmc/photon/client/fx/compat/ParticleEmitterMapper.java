package com.lowdragmc.photon.client.fx.compat;

import com.lowdragmc.lowdraglib2.utils.TagBuilder;
import net.minecraft.nbt.*;

public class ParticleEmitterMapper implements Mapper{

    public static CompoundTag mapParticleEmitter(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", emitterTag.getStringOr("_type", "") + "_emitter");
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getStringOr("name", ""));
        dataTag.putInt("version",  emitterTag.getIntOr("_version", 0));
        dataTag.put("transform", MapperUtils.mapTransformTag(emitterTag.getCompoundOrEmpty("transform")));
        dataTag.put("config", mapConfigTag(emitterTag.getCompoundOrEmpty("config")));
        newEmitterTag.put("data", dataTag);


        return newEmitterTag;
    }


    public static CompoundTag mapConfigTag(CompoundTag configTag){
        CompoundTag newConfigTag = new CompoundTag();
        newConfigTag.putString("simulationSpace",  configTag.getStringOr("simulationSpace", ""));
        newConfigTag.put("startLifetime", MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("startLifetime"), false));
        newConfigTag.putInt("prewarm", 0);
        newConfigTag.putInt("maxParticles", configTag.getIntOr("maxParticles", 0));
        newConfigTag.putInt("duration", configTag.getIntOr("duration", 0));
        newConfigTag.putByte("looping",  configTag.getByteOr("looping", (byte) 0));
        newConfigTag.putByte("parallelUpdate",  configTag.getByteOr("parallelUpdate", (byte) 0));
        newConfigTag.put("startDelay", MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("startDelay"), false));
        newConfigTag.put("sizeOverLifetime",  MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("sizeOverLifetime"), false));
        newConfigTag.put("startRotation", MapperUtils.mapTyped3Vec(configTag.getCompoundOrEmpty("startRotation"), null, false));
        newConfigTag.put("rotationBySpeed", mapRotationBySpeedTag(configTag.getCompoundOrEmpty("rotationBySpeed")));
        newConfigTag.put("physics", mapPhysicsTag(configTag.getCompoundOrEmpty("physics")));
        newConfigTag.put("startColor", MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("startColor"), false));
        newConfigTag.put("startSpeed", MapperUtils.mapTypedValue(configTag.getCompoundOrEmpty("startSpeed"), false));
        newConfigTag.put("inheritVelocity", mapInheritVelocity(configTag.getCompoundOrEmpty("inheritVelocity")));
        newConfigTag.put("colorOverLifetime",  mapColorOTTag(configTag.getCompoundOrEmpty("colorOverLifetime")));
        newConfigTag.put("startSize", MapperUtils.mapTyped3Vec(configTag.getCompoundOrEmpty("startSize"), null, true));
        newConfigTag.put("sizeOverLifetime", mapSizeOverLifeTimeTag(configTag.getCompoundOrEmpty("sizeOverLifetime")));
        newConfigTag.put("forceOverLifetime", mapForceOTTag(configTag.getCompoundOrEmpty("forceOverLifetime")));
        newConfigTag.put("noise", mapNoiseTag(configTag.getCompoundOrEmpty("noise")));
        newConfigTag.put("emission", mapEmissionTag(configTag.getCompoundOrEmpty("emission")));
        newConfigTag.put("sizeBySpeed", mapSizeBySpeedTag(configTag.getCompoundOrEmpty("sizeBySpeed")));
        newConfigTag.put("velocityOverLifetime", mapVelocityOLTag(configTag.getCompoundOrEmpty("velocityOverLifetime")));
        newConfigTag.put("rotationOverLifetime", mapRotationOLTTag(configTag.getCompoundOrEmpty("rotationOverLifetime")));
        newConfigTag.put("shape", mapShapeTag(configTag.getCompoundOrEmpty("shape")));
        newConfigTag.put("trails", mapTrailsTag(configTag.getCompoundOrEmpty("trails")));
        newConfigTag.put("subEmitters", mapSubEmittersTag(configTag.getCompoundOrEmpty("subEmitters")));
        newConfigTag.put("lifetimeByEmitterSpeed", mapLTByEmitterSpeed(configTag.getCompoundOrEmpty("lifetimeByEmitterSpeed")));
        newConfigTag.put("additionalGPUDataSetting", new CompoundTag());
        newConfigTag.getCompoundOrEmpty("additionalGPUDataSetting").putByte("_enable", (byte)0);
        newConfigTag.put("renderer", MapperUtils.mapRendererTag(configTag.getCompoundOrEmpty("renderer"), configTag));
        newConfigTag.put("uvAnimation", MapperUtils.mapUVTag(configTag.getCompoundOrEmpty("uvAnimation")));
        newConfigTag.put("lights", MapperUtils.mapLightTag(configTag.getCompoundOrEmpty("lights")));
        newConfigTag.put("colorBySpeed", mapColorBSTag(configTag.getCompoundOrEmpty("colorBySpeed")));

        return newConfigTag;
    }



    public static CompoundTag mapRotationBySpeedTag(CompoundTag rotationTag){
        CompoundTag newRotationTag = new CompoundTag();
        newRotationTag.putByte("_enable", rotationTag.getByteOr("enable", (byte) 0));
        if(newRotationTag.getByteOr("enable", (byte) 0) == 1){
            newRotationTag.put("speedRange",  rotationTag.getCompoundOrEmpty("speedRange"));
            newRotationTag.put("roll", MapperUtils.mapTypedValue(rotationTag.getCompoundOrEmpty("roll"), false));
            newRotationTag.put("pitch", MapperUtils.mapTypedValue(rotationTag.getCompoundOrEmpty("pitch"), false));
            newRotationTag.put("yaw", MapperUtils.mapTypedValue(rotationTag.getCompoundOrEmpty("yaw"), false));
        }
        return newRotationTag;
    }

    public static CompoundTag mapColorOTTag(CompoundTag colorTag){
        CompoundTag newColorTag = new CompoundTag();
        newColorTag.putByte("_enable", colorTag.getByteOr("enable", (byte) 0));
        if(newColorTag.getByteOr("_enable", (byte) 0) == 1){
            newColorTag.put("color", MapperUtils.mapTypedValue(colorTag.getCompoundOrEmpty("color"), false));
        }
            return newColorTag;

    }

    public static CompoundTag mapSizeOverLifeTimeTag(CompoundTag sizeOverLifeTimeTag){
        CompoundTag newSizeOverLifeTimeTag = new CompoundTag();
        newSizeOverLifeTimeTag.putByte("_enable", sizeOverLifeTimeTag.getByteOr("enable", (byte) 0));
        if(newSizeOverLifeTimeTag.getByteOr("_enable", (byte) 0) == 1){
            newSizeOverLifeTimeTag.put("size", MapperUtils.mapTyped3Vec(sizeOverLifeTimeTag.getCompoundOrEmpty("size"), null, false));
        }
        return newSizeOverLifeTimeTag;
    }

    public static CompoundTag mapForceOTTag(CompoundTag forceOTTag){
        CompoundTag newForceOTTag = new CompoundTag();
        newForceOTTag.putByte("_enable", forceOTTag.getByteOr("enable", (byte) 0));
        if(newForceOTTag.getByteOr("enable", (byte) 0) == 1){
            newForceOTTag.putString("simulationSpace", forceOTTag.getStringOr("simulationSpace", ""));
            newForceOTTag.put("force", MapperUtils.mapTyped3Vec(forceOTTag.getCompoundOrEmpty("force"), null, false));
        }
        return newForceOTTag;
    }


    public static CompoundTag mapNoiseTag(CompoundTag noiseTag){
        CompoundTag newNoiseTag = new CompoundTag();
        newNoiseTag.putByte("_enable", noiseTag.getByteOr("enable", (byte) 0));
        if(newNoiseTag.getByteOr("enable", (byte) 0) == 1){
            newNoiseTag.put("size", MapperUtils.mapTypedValue(noiseTag.getCompoundOrEmpty("size"), false));
            newNoiseTag.put("rotation", MapperUtils.mapTypedValue(noiseTag.getCompoundOrEmpty("rotation"), false));
            newNoiseTag.put("position", MapperUtils.mapTyped3Vec(noiseTag.getCompoundOrEmpty("position"), null, true));
            CompoundTag remap = new CompoundTag();
            remap.putByte("_enable",  noiseTag.getCompoundOrEmpty("remap").getByteOr("enable", (byte) 0));
            if(remap.getByteOr("enable", (byte) 0) == 1){
                remap.put("remapCurve", MapperUtils.mapTypedValue(noiseTag.getCompoundOrEmpty("remap").getCompoundOrEmpty("remapCurve"), false));
            }
            newNoiseTag.put("remap", remap);
            newNoiseTag.putString("quality",  noiseTag.getStringOr("quality", ""));
            newNoiseTag.putFloat("frequency",   noiseTag.getFloatOr("frequency", 0.0F));
        }

        return newNoiseTag;
    }

    public static CompoundTag mapPhysicsTag(CompoundTag physicsTag){
        CompoundTag newPhysicsTag = new CompoundTag();
        newPhysicsTag.putByte("_enable", physicsTag.getByteOr("enable", (byte) 0));
        if(newPhysicsTag.getByteOr("enable", (byte) 0) == 1){
            newPhysicsTag.putByte("hasCollision",  physicsTag.getByteOr("hasCollision", (byte) 0));
            newPhysicsTag.putByte("removeWhenCollided",  physicsTag.getByteOr("removeWhenCollided", (byte) 0));
            newPhysicsTag.put("friction", MapperUtils.mapTypedValue(physicsTag.getCompoundOrEmpty("friction"), false));
            newPhysicsTag.put("gravity", MapperUtils.mapTypedValue(physicsTag.getCompoundOrEmpty("gravity"), false));
            newPhysicsTag.put("bounceSpreadRate", MapperUtils.mapTypedValue(physicsTag.getCompoundOrEmpty("bounceSpreadRate"), false));
            newPhysicsTag.put("bounceRate", MapperUtils.mapTypedValue(physicsTag.getCompoundOrEmpty("bounceRate"), false));
            newPhysicsTag.put("bounceChance", MapperUtils.mapTypedValue(physicsTag.getCompoundOrEmpty("bounceChance"), false));
        }
        return newPhysicsTag;
    }

    public static CompoundTag mapEmissionTag(CompoundTag emissionTag){
        CompoundTag newEmissionTag = new CompoundTag();
        newEmissionTag.put("emissionRate", MapperUtils.mapTypedValue(emissionTag.getCompoundOrEmpty("emissionRate"), false));
        newEmissionTag.putString("emissionMode",  emissionTag.getStringOr("emissionMode", ""));
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
        newSizeBySpeedTag.putByte("_enable", sizeBySpeedTag.getByteOr("enable", (byte) 0));
        if(newSizeBySpeedTag.getByteOr("enable", (byte) 0) == 1){
            newSizeBySpeedTag.put("speedRange", sizeBySpeedTag.getCompoundOrEmpty("speedRange"));
            newSizeBySpeedTag.put("size", MapperUtils.mapTyped3Vec(sizeBySpeedTag.getCompoundOrEmpty("size"), null, false));
        }

        return newSizeBySpeedTag;
    }

    public static CompoundTag mapVelocityOLTag(CompoundTag velocityOTTag){
        CompoundTag newVelocityOTTag = new CompoundTag();
        newVelocityOTTag.putByte("_enable", velocityOTTag.getByteOr("enable", (byte) 0));
        if(newVelocityOTTag.getByteOr("enable", (byte) 0) == 1){
            newVelocityOTTag.put("speedModifier",  MapperUtils.mapTypedValue(velocityOTTag.getCompoundOrEmpty("speedModifier"), false));
            newVelocityOTTag.putString("orbitalMode",   velocityOTTag.getStringOr("orbitalMode", ""));
            newVelocityOTTag.put("offset",  MapperUtils.mapTyped3Vec(velocityOTTag.getCompoundOrEmpty("offset"), null, false));
            newVelocityOTTag.put("orbital", MapperUtils.mapTyped3Vec(velocityOTTag.getCompoundOrEmpty("orbital"), null, false));
            newVelocityOTTag.put("linear",  MapperUtils.mapTyped3Vec(velocityOTTag.getCompoundOrEmpty("linear"), null, false));

        }

        return newVelocityOTTag;
    }

    public static CompoundTag mapRotationOLTTag(CompoundTag rotationOLTTag){
        CompoundTag newRotationOLTTag = new CompoundTag();
        newRotationOLTTag.putByte("_enable", rotationOLTTag.getByteOr("enable", (byte) 0));
        if(newRotationOLTTag.getByteOr("enable", (byte) 0) == 1){
            newRotationOLTTag.put("roll", MapperUtils.mapTypedValue(rotationOLTTag.getCompoundOrEmpty("roll"), false));
            newRotationOLTTag.put("pitch", MapperUtils.mapTypedValue(rotationOLTTag.getCompoundOrEmpty("pitch"), false));
            newRotationOLTTag.put("yaw", MapperUtils.mapTypedValue(rotationOLTTag.getCompoundOrEmpty("yaw"), false));

        }

        return newRotationOLTTag;
    }

    public static CompoundTag mapShapeTag(CompoundTag shapeTag){
        CompoundTag newShapeTag = new CompoundTag();
        newShapeTag.put("rotation", MapperUtils.mapTyped3Vec(shapeTag.getCompoundOrEmpty("rotation"), null, false));
        newShapeTag.put("scale",  MapperUtils.mapTyped3Vec(shapeTag.getCompoundOrEmpty("scale"), null, false));
        newShapeTag.put("position",  MapperUtils.mapTyped3Vec(shapeTag.getCompoundOrEmpty("position"), null, false));
        newShapeTag.put("shape", MapperUtils.mapShape(shapeTag.getCompoundOrEmpty("shape")));

        return newShapeTag;
    }



    public static CompoundTag mapTrailsTag(CompoundTag trailsTag){
        CompoundTag newTrailsTag = new CompoundTag();
        newTrailsTag.putByte("_enable", trailsTag.getByteOr("enable", (byte) 0));
        if(newTrailsTag.getByteOr("enable", (byte) 0) == 1){
            newTrailsTag.putByte("dieWithParticles", trailsTag.getByteOr("dieWithParticles", (byte) 0));
            newTrailsTag.putByte("sizeAffectsWidth", trailsTag.getByteOr("sizeAffectsWidth", (byte) 0));
            newTrailsTag.putByte("inheritParticleColor", trailsTag.getByteOr("inheritParticleColor", (byte) 0));
            newTrailsTag.putByte("sizeAffectsLifetime", trailsTag.getByteOr("sizeAffectsLifetime", (byte) 0));
            newTrailsTag.putFloat("ratio",  trailsTag.getFloatOr("ratio", 0.0F));
            newTrailsTag.put("colorOverLifetime", MapperUtils.mapTypedValue(trailsTag.getCompoundOrEmpty("colorOverLifetime"), false));
            newTrailsTag.put("lifetime", MapperUtils.mapTypedValue(trailsTag.getCompoundOrEmpty("lifetime"), false));
        }
        newTrailsTag.put("config", TrailEmitterMapper.mapTrailConfig(trailsTag.getCompoundOrEmpty("config")));
        newTrailsTag.put("araConfig", buildAraConfig());

        return newTrailsTag;
    }



    public static CompoundTag buildAraConfig(){
        CompoundTag tag = new CompoundTag();
        tag.putInt("smoothness", 1);
        tag.putString("space", "World");
        tag.putString("textureMode", "Stretch");
        tag.putFloat("uvWidthFactor", 1);
        tag.putFloat("initialThickness", 1);
        tag.putFloat("smoothingDistance", 0.05f);


        tag.put("colorOverLength", MapperUtils.blankColorTag());

        tag.putString("alignment", "View");

        ListTag velTag = new ListTag();
        velTag.add(FloatTag.valueOf(0));
        velTag.add(FloatTag.valueOf(0));
        velTag.add(FloatTag.valueOf(0));
        tag.put("initialVelocity", velTag);

        tag.putFloat("timeInterval", 0.05f);
        tag.putFloat("thickness", 0.5f);

        tag.put("thicknessOverTime", MapperUtils.blankTypedTag());

        tag.putInt("duration", 100);
        tag.putString("sorting", "OlderOnTop");
        tag.putFloat("minDistance", 0.05f);
        tag.putByte("highQualityCorners", (byte)0);

        tag.put("thicknessOverLength", MapperUtils.blankTypedTag());

        tag.putInt("initialColor", -1);

        tag.put("physicsSetting", new CompoundTag());
        tag.getCompoundOrEmpty("physicsSetting").putByte("_enable", (byte)0);

        tag.putByte("emit", (byte)1);
        tag.putFloat("tileAnchor", 1);

        tag.put("colorOverSegmentTime", MapperUtils.blankColorTag());

        tag.putInt("cornerRoundness", 5);
        tag.putFloat("time", 1);

        tag.put("colorOverTime", MapperUtils.blankColorTag());

        tag.putString("customSpace", "");

        tag.put("thicknessOverSegmentTime", MapperUtils.blankTypedTag());

        tag.putFloat("uvFactor", 1);

        tag.put("section", new CompoundTag());
        tag.getCompoundOrEmpty("section").putByte("_enable", (byte)0);

        tag.putByte("looping", (byte)1);


        tag.put("renderer", MapperUtils.blankRenderer());

        return tag;
    }

    public static CompoundTag mapInheritVelocity(CompoundTag inheritVelTag){
        CompoundTag newInheritVelTag = new CompoundTag();
        newInheritVelTag.putByte("_enable", inheritVelTag.getByteOr("enable", (byte) 0));
        if(inheritVelTag.getByteOr("enable", (byte) 0) == 1){
            newInheritVelTag.put("multiply", MapperUtils.mapTypedValue(inheritVelTag.getCompoundOrEmpty("multiply"), false));
            newInheritVelTag.putString("mode",  inheritVelTag.getStringOr("mode", ""));
        }

        return newInheritVelTag;
    }

    public static CompoundTag mapLTByEmitterSpeed(CompoundTag lteTag){
        CompoundTag newLTETag = new CompoundTag();
        newLTETag.putByte("_enable", lteTag.getByteOr("enable", (byte) 0));
        if(lteTag.getByteOr("enable", (byte) 0) == 1){
            newLTETag.put("multiplier", MapperUtils.mapTypedValue(lteTag.getCompoundOrEmpty("multiplier"), false));
            newLTETag.put("speedRange", lteTag.getCompoundOrEmpty("speedRange"));
        }

        return newLTETag;
    }

    public static CompoundTag mapSubEmittersTag(CompoundTag seTag){
        CompoundTag newSeTag = new CompoundTag();
        newSeTag.putByte("_enable", seTag.getByteOr("enable", (byte) 0));

        if(seTag.getByteOr("enable", (byte) 0) == 1){
            ListTag payload = new ListTag();
            ListTag oldPayload = seTag.getListOrEmpty("emitters");
            oldPayload.forEach(e -> payload.add(mapSubEmitter((CompoundTag)e)));
            newSeTag.put("payload", payload);
            newSeTag.putInt("uid", payload.size());
        }
        return newSeTag;
    }

    public static CompoundTag mapSubEmitter(CompoundTag subEmitterTag){
        CompoundTag newSubEmitterTag = new CompoundTag();
        newSubEmitterTag.putByte("inheritSize",  subEmitterTag.getByteOr("inheritSize", (byte) 0));
        newSubEmitterTag.putByte("inheritRotation",   subEmitterTag.getByteOr("inheritRotation", (byte) 0));
        newSubEmitterTag.putByte("inheritLifetime",  subEmitterTag.getByteOr("inheritLifetime", (byte) 0));
        newSubEmitterTag.putInt("tickInterval", subEmitterTag.getByteOr("tickInterval", (byte) 0));
        newSubEmitterTag.putByte("inheritColor",  subEmitterTag.getByteOr("inheritColor", (byte) 0));
        newSubEmitterTag.put("emitProbability", MapperUtils.mapTypedValue(subEmitterTag.getCompoundOrEmpty("emitProbability"), false));
        newSubEmitterTag.putByte("inheritDuration",  subEmitterTag.getByteOr("inheritDuration", (byte) 0));
        newSubEmitterTag.putString("event", subEmitterTag.getStringOr("event", ""));
        newSubEmitterTag.putString("fxLocation", subEmitterTag.getStringOr("emitter", ""));

        return newSubEmitterTag;
    }

    public static CompoundTag mapColorBSTag(CompoundTag colorTag){
        CompoundTag newColorTag = new CompoundTag();
        newColorTag.putByte("_enable", colorTag.getByteOr("enable", (byte) 0));
        if(newColorTag.getByteOr("_enable", (byte) 0) == 1){
            newColorTag.put("speedRange",  colorTag.getCompoundOrEmpty("speedRange"));
            newColorTag.put("color", MapperUtils.mapTypedValue(colorTag.getCompoundOrEmpty("color"), false));
        }
        return  newColorTag;
    }




}
