package com.lowdragmc.photon.client.fx.compat;

import com.lowdragmc.lowdraglib2.utils.TagBuilder;
import net.minecraft.nbt.*;

public class ParticleEmitterMapper implements Mapper{

    public static CompoundTag mapParticleEmitter(CompoundTag emitterTag){
        CompoundTag newEmitterTag = new CompoundTag();
        newEmitterTag.putString("type", emitterTag.getString("_type") + "_emitter");
        CompoundTag dataTag = new CompoundTag();

        //data tag
        dataTag.putString("name", emitterTag.getString("name"));
        dataTag.putInt("version",  emitterTag.getInt("_version"));
        dataTag.put("transform", MapperUtils.mapTransformTag(emitterTag.getCompound("transform")));
        dataTag.put("config", mapConfigTag(emitterTag.getCompound("config")));
        newEmitterTag.put("data", dataTag);


        return newEmitterTag;
    }


    public static CompoundTag mapConfigTag(CompoundTag configTag){
        CompoundTag newConfigTag = new CompoundTag();
        newConfigTag.putString("simulationSpace",  configTag.getString("simulationSpace"));
        newConfigTag.put("startLifetime", MapperUtils.mapTypedValue(configTag.getCompound("startLifetime"), false));
        newConfigTag.putInt("prewarm", 0);
        newConfigTag.putInt("maxParticles", configTag.getInt("maxParticles"));
        newConfigTag.putInt("duration", configTag.getInt("duration"));
        newConfigTag.putByte("parallelRendering", configTag.getByte("parallelRendering"));
        newConfigTag.putByte("looping",  configTag.getByte("looping"));
        newConfigTag.putByte("parallelUpdate",  configTag.getByte("parallelUpdate"));
        newConfigTag.put("startDelay", MapperUtils.mapTypedValue(configTag.getCompound("startDelay"), false));
        newConfigTag.put("sizeOverLifetime",  MapperUtils.mapTypedValue(configTag.getCompound("sizeOverLifetime"), false));
        newConfigTag.put("startRotation", MapperUtils.mapTyped3Vec(configTag.getCompound("startRotation"), null, false));
        newConfigTag.put("rotationBySpeed", mapRotationBySpeedTag(configTag.getCompound("rotationBySpeed")));
        newConfigTag.put("physics", mapPhysicsTag(configTag.getCompound("physics")));
        newConfigTag.put("startColor", MapperUtils.mapTypedValue(configTag.getCompound("startColor"), false));
        newConfigTag.put("startSpeed", MapperUtils.mapTypedValue(configTag.getCompound("startSpeed"), false));
        newConfigTag.put("inheritVelocity", mapInheritVelocity(configTag.getCompound("inheritVelocity")));
        newConfigTag.put("colorOverLifetime",  mapColorOTTag(configTag.getCompound("colorOverLifetime")));
        newConfigTag.put("startSize", MapperUtils.mapTyped3Vec(configTag.getCompound("startSize"), null, true));
        newConfigTag.put("sizeOverLifetime", mapSizeOverLifeTimeTag(configTag.getCompound("sizeOverLifetime")));
        newConfigTag.put("forceOverLifetime", mapForceOTTag(configTag.getCompound("forceOverLifetime")));
        newConfigTag.put("noise", mapNoiseTag(configTag.getCompound("noise")));
        newConfigTag.put("emission", mapEmissionTag(configTag.getCompound("emission")));
        newConfigTag.put("sizeBySpeed", mapSizeBySpeedTag(configTag.getCompound("sizeBySpeed")));
        newConfigTag.put("velocityOverLifetime", mapVelocityOLTag(configTag.getCompound("velocityOverLifetime")));
        newConfigTag.put("rotationOverLifetime", mapRotationOLTTag(configTag.getCompound("rotationOverLifetime")));
        newConfigTag.put("shape", mapShapeTag(configTag.getCompound("shape")));
        newConfigTag.put("trails", mapTrailsTag(configTag.getCompound("trails")));
        newConfigTag.put("subEmitters", mapSubEmittersTag(configTag.getCompound("subEmitters")));
        newConfigTag.put("lifetimeByEmitterSpeed", mapLTByEmitterSpeed(configTag.getCompound("lifetimeByEmitterSpeed")));
        newConfigTag.put("additionalGPUDataSetting", new CompoundTag());
        newConfigTag.getCompound("additionalGPUDataSetting").putByte("_enable", (byte)0);
        newConfigTag.put("renderer", MapperUtils.mapRendererTag(configTag.getCompound("renderer"), configTag));
        newConfigTag.put("uvAnimation", MapperUtils.mapUVTag(configTag.getCompound("uvAnimation")));
        newConfigTag.put("lights", MapperUtils.mapLightTag(configTag.getCompound("lights")));
        newConfigTag.put("colorBySpeed", mapColorBSTag(configTag.getCompound("colorBySpeed")));

        return newConfigTag;
    }



    public static CompoundTag mapRotationBySpeedTag(CompoundTag rotationTag){
        CompoundTag newRotationTag = new CompoundTag();
        newRotationTag.putByte("_enable", rotationTag.getByte("enable"));
        if(newRotationTag.getByte("enable") == 1){
            newRotationTag.put("speedRange",  rotationTag.getCompound("speedRange"));
            newRotationTag.put("roll", MapperUtils.mapTypedValue(rotationTag.getCompound("roll"), false));
            newRotationTag.put("pitch", MapperUtils.mapTypedValue(rotationTag.getCompound("pitch"), false));
            newRotationTag.put("yaw", MapperUtils.mapTypedValue(rotationTag.getCompound("yaw"), false));
        }
        return newRotationTag;
    }

    public static CompoundTag mapColorOTTag(CompoundTag colorTag){
        CompoundTag newColorTag = new CompoundTag();
        newColorTag.putByte("_enable", colorTag.getByte("enable"));
        if(newColorTag.getByte("_enable") == 1){
            newColorTag.put("color", MapperUtils.mapTypedValue(colorTag.getCompound("color"), false));
        }
            return newColorTag;

    }

    public static CompoundTag mapSizeOverLifeTimeTag(CompoundTag sizeOverLifeTimeTag){
        CompoundTag newSizeOverLifeTimeTag = new CompoundTag();
        newSizeOverLifeTimeTag.putByte("_enable", sizeOverLifeTimeTag.getByte("enable"));
        if(newSizeOverLifeTimeTag.getByte("_enable") == 1){
            newSizeOverLifeTimeTag.put("size", MapperUtils.mapTyped3Vec(sizeOverLifeTimeTag.getCompound("size"), null, false));
        }
        return newSizeOverLifeTimeTag;
    }

    public static CompoundTag mapForceOTTag(CompoundTag forceOTTag){
        CompoundTag newForceOTTag = new CompoundTag();
        newForceOTTag.putByte("_enable", forceOTTag.getByte("enable"));
        if(newForceOTTag.getByte("enable") == 1){
            newForceOTTag.putString("simulationSpace", forceOTTag.getString("simulationSpace"));
            newForceOTTag.put("force", MapperUtils.mapTyped3Vec(forceOTTag.getCompound("force"), null, false));
        }
        return newForceOTTag;
    }


    public static CompoundTag mapNoiseTag(CompoundTag noiseTag){
        CompoundTag newNoiseTag = new CompoundTag();
        newNoiseTag.putByte("_enable", noiseTag.getByte("enable"));
        if(newNoiseTag.getByte("enable") == 1){
            newNoiseTag.put("size", MapperUtils.mapTypedValue(noiseTag.getCompound("size"), false));
            newNoiseTag.put("rotation", MapperUtils.mapTypedValue(noiseTag.getCompound("rotation"), false));
            newNoiseTag.put("position", MapperUtils.mapTyped3Vec(noiseTag.getCompound("position"), null, true));
            CompoundTag remap = new CompoundTag();
            remap.putByte("_enable",  noiseTag.getCompound("remap").getByte("enable"));
            if(remap.getByte("enable") == 1){
                remap.put("remapCurve", MapperUtils.mapTypedValue(noiseTag.getCompound("remap").getCompound("remapCurve"), false));
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
            newPhysicsTag.put("friction", MapperUtils.mapTypedValue(physicsTag.getCompound("friction"), false));
            newPhysicsTag.put("gravity", MapperUtils.mapTypedValue(physicsTag.getCompound("gravity"), false));
            newPhysicsTag.put("bounceSpreadRate", MapperUtils.mapTypedValue(physicsTag.getCompound("bounceSpreadRate"), false));
            newPhysicsTag.put("bounceRate", MapperUtils.mapTypedValue(physicsTag.getCompound("bounceRate"), false));
            newPhysicsTag.put("bounceChance", MapperUtils.mapTypedValue(physicsTag.getCompound("bounceChance"), false));
        }
        return newPhysicsTag;
    }

    public static CompoundTag mapEmissionTag(CompoundTag emissionTag){
        CompoundTag newEmissionTag = new CompoundTag();
        newEmissionTag.put("emissionRate", MapperUtils.mapTypedValue(emissionTag.getCompound("emissionRate"), false));
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
            newSizeBySpeedTag.put("size", MapperUtils.mapTyped3Vec(sizeBySpeedTag.getCompound("size"), null, false));
        }

        return newSizeBySpeedTag;
    }

    public static CompoundTag mapVelocityOLTag(CompoundTag velocityOTTag){
        CompoundTag newVelocityOTTag = new CompoundTag();
        newVelocityOTTag.putByte("_enable", velocityOTTag.getByte("enable"));
        if(newVelocityOTTag.getByte("enable") == 1){
            newVelocityOTTag.put("speedModifier",  MapperUtils.mapTypedValue(velocityOTTag.getCompound("speedModifier"), false));
            newVelocityOTTag.putString("orbitalMode",   velocityOTTag.getString("orbitalMode"));
            newVelocityOTTag.put("offset",  MapperUtils.mapTyped3Vec(velocityOTTag.getCompound("offset"), null, false));
            newVelocityOTTag.put("orbital", MapperUtils.mapTyped3Vec(velocityOTTag.getCompound("orbital"), null, false));
            newVelocityOTTag.put("linear",  MapperUtils.mapTyped3Vec(velocityOTTag.getCompound("linear"), null, false));

        }

        return newVelocityOTTag;
    }

    public static CompoundTag mapRotationOLTTag(CompoundTag rotationOLTTag){
        CompoundTag newRotationOLTTag = new CompoundTag();
        newRotationOLTTag.putByte("_enable", rotationOLTTag.getByte("enable"));
        if(newRotationOLTTag.getByte("enable") == 1){
            newRotationOLTTag.put("roll", MapperUtils.mapTypedValue(rotationOLTTag.getCompound("roll"), false));
            newRotationOLTTag.put("pitch", MapperUtils.mapTypedValue(rotationOLTTag.getCompound("pitch"), false));
            newRotationOLTTag.put("yaw", MapperUtils.mapTypedValue(rotationOLTTag.getCompound("yaw"), false));

        }

        return newRotationOLTTag;
    }

    public static CompoundTag mapShapeTag(CompoundTag shapeTag){
        CompoundTag newShapeTag = new CompoundTag();
        newShapeTag.put("rotation", MapperUtils.mapTyped3Vec(shapeTag.getCompound("rotation"), null, false));
        newShapeTag.put("scale",  MapperUtils.mapTyped3Vec(shapeTag.getCompound("scale"), null, false));
        newShapeTag.put("position",  MapperUtils.mapTyped3Vec(shapeTag.getCompound("position"), null, false));
        newShapeTag.put("shape", MapperUtils.mapShape(shapeTag.getCompound("shape")));

        return newShapeTag;
    }



    public static CompoundTag mapTrailsTag(CompoundTag trailsTag){
        CompoundTag newTrailsTag = new CompoundTag();
        newTrailsTag.putByte("_enable", trailsTag.getByte("enable"));
        if(newTrailsTag.getByte("enable") == 1){
            newTrailsTag.putByte("dieWithParticles", trailsTag.getByte("dieWithParticles"));
            newTrailsTag.putByte("sizeAffectsWidth", trailsTag.getByte("sizeAffectsWidth"));
            newTrailsTag.putByte("inheritParticleColor", trailsTag.getByte("inheritParticleColor"));
            newTrailsTag.putByte("sizeAffectsLifetime", trailsTag.getByte("sizeAffectsLifetime"));
            newTrailsTag.putFloat("ratio",  trailsTag.getFloat("ratio"));
            newTrailsTag.put("colorOverLifetime", MapperUtils.mapTypedValue(trailsTag.getCompound("colorOverLifetime"), false));
            newTrailsTag.put("lifetime", MapperUtils.mapTypedValue(trailsTag.getCompound("lifetime"), false));
        }
        newTrailsTag.put("config", TrailEmitterMapper.mapTrailConfig(trailsTag.getCompound("config")));
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
        tag.getCompound("physicsSetting").putByte("_enable", (byte)0);

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
        tag.getCompound("section").putByte("_enable", (byte)0);

        tag.putByte("looping", (byte)1);


        tag.put("renderer", MapperUtils.blankRenderer());

        return tag;
    }

    public static CompoundTag mapInheritVelocity(CompoundTag inheritVelTag){
        CompoundTag newInheritVelTag = new CompoundTag();
        newInheritVelTag.putByte("_enable", inheritVelTag.getByte("enable"));
        if(inheritVelTag.getByte("enable") == 1){
            newInheritVelTag.put("multiply", MapperUtils.mapTypedValue(inheritVelTag.getCompound("multiply"), false));
            newInheritVelTag.putString("mode",  inheritVelTag.getString("mode"));
        }

        return newInheritVelTag;
    }

    public static CompoundTag mapLTByEmitterSpeed(CompoundTag lteTag){
        CompoundTag newLTETag = new CompoundTag();
        newLTETag.putByte("_enable", lteTag.getByte("enable"));
        if(lteTag.getByte("enable") == 1){
            newLTETag.put("multiplier", MapperUtils.mapTypedValue(lteTag.getCompound("multiplier"), false));
            newLTETag.put("speedRange", lteTag.getCompound("speedRange"));
        }

        return newLTETag;
    }

    public static CompoundTag mapSubEmittersTag(CompoundTag seTag){
        CompoundTag newSeTag = new CompoundTag();
        newSeTag.putByte("_enable", seTag.getByte("enable"));

        if(seTag.getByte("enable") == 1){
            ListTag payload = new ListTag();
            ListTag oldPayload = seTag.getList("emitters", Tag.TAG_COMPOUND);
            oldPayload.forEach(e -> payload.add(mapSubEmitter((CompoundTag)e)));
            newSeTag.put("payload", payload);
            newSeTag.putInt("uid", payload.size());
        }
        return newSeTag;
    }

    public static CompoundTag mapSubEmitter(CompoundTag subEmitterTag){
        CompoundTag newSubEmitterTag = new CompoundTag();
        newSubEmitterTag.putByte("inheritSize",  subEmitterTag.getByte("inheritSize"));
        newSubEmitterTag.putByte("inheritRotation",   subEmitterTag.getByte("inheritRotation"));
        newSubEmitterTag.putByte("inheritLifetime",  subEmitterTag.getByte("inheritLifetime"));
        newSubEmitterTag.putInt("tickInterval", subEmitterTag.getByte("tickInterval"));
        newSubEmitterTag.putByte("inheritColor",  subEmitterTag.getByte("inheritColor"));
        newSubEmitterTag.put("emitProbability", MapperUtils.mapTypedValue(subEmitterTag.getCompound("emitProbability"), false));
        newSubEmitterTag.putByte("inheritDuration",  subEmitterTag.getByte("inheritDuration"));
        newSubEmitterTag.putString("event", subEmitterTag.getString("event"));
        newSubEmitterTag.putString("fxLocation", subEmitterTag.getString("emitter"));

        return newSubEmitterTag;
    }

    public static CompoundTag mapColorBSTag(CompoundTag colorTag){
        CompoundTag newColorTag = new CompoundTag();
        newColorTag.putByte("_enable", colorTag.getByte("enable"));
        if(newColorTag.getByte("_enable") == 1){
            newColorTag.put("speedRange",  colorTag.getCompound("speedRange"));
            newColorTag.put("color", MapperUtils.mapTypedValue(colorTag.getCompound("color"), false));
        }
        return  newColorTag;
    }




}
