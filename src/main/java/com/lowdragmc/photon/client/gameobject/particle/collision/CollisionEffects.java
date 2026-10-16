package com.lowdragmc.photon.client.gameobject.particle.collision;

import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSettings;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class CollisionEffects {
    private CollisionEffects() {}

    public static void contact(ParticleEmitter emitter, SweptCollisionParticle particle, CollisionFrame.Hit hit, Vec3 point, boolean first, boolean death) {
        if (particle.getRuntime().subEmitters.isEnable()) {
            for (SubEmittersSetting.Emitter effect : particle.getRuntime().subEmitters.emitters.get()) {
                var event = effect.getEvent();
                if (event != SubEmittersSetting.Event.Collision && !(first && event == SubEmittersSetting.Event.FirstCollision) && !(death && event == SubEmittersSetting.Event.Death)) continue;
                if (particle.getRandomSource().nextFloat() >= effect.getEmitProbability().get(particle.getT(0), () -> particle.getMemRandom("sub_emitter_probability")).floatValue()) continue;
                ResourceLocation location = effect.getFxLocation();
                if (location == null) continue;
                Vector4f color = effect.isInheritColor() ? new Vector4f(particle.getRealColor(0)) : null;
                Vector3f size = effect.isInheritSize() ? new Vector3f(particle.getRealSize(0)) : null;
                Vector3f angles = particle.getRealRotation(0);
                Quaternionf rotation = effect.isInheritRotation() ? new Quaternionf().rotationXYZ(angles.x, angles.y, angles.z) : null;
                spawn(emitter, location, point, hit.normal(), color, size, rotation, effect.isInheritLifetime() ? (int) particle.getAge() : -1, effect.isInheritDuration() ? particle.getLifetime() : -1);
            }
        }
        String configured = switch (hit.candidate().target().kind()) {
            case BLOCK -> emitter.config.physics.collision.blockEffect;
            case ENTITY -> emitter.config.physics.collision.entityEffect;
            case FLUID -> emitter.config.physics.collision.fluidEffect;
        };
        if (!configured.isBlank()) {
            ResourceLocation location = ResourceLocation.tryParse(configured);
            if (location != null) spawn(emitter, location, point, hit.normal(), null, null, null, -1, -1);
        }
    }

    private static void spawn(ParticleEmitter emitter, ResourceLocation location, Vec3 point, Vec3 normal, Vector4f color, Vector3f size, Quaternionf rotation, int age, int lifetime) {
        boolean align = emitter.config.physics.collision.alignEffectsToNormal;
        emitter.scheduleSubEmitterSpawn(() -> {
            var definition = FXHelper.getFX(location);
            if (definition == null || emitter.getEffectExecutor() == null) return;
            var runtime = definition.createRuntime();
            runtime.root.updatePos(new Vector3f((float) point.x, (float) point.y, (float) point.z));
            if (align) runtime.root.updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f((float) normal.x, (float) normal.y, (float) normal.z)));
            for (var object : runtime.objects.values()) {
                if (!(object instanceof IParticleEmitter child)) continue;
                if (age >= 0) child.setAge(age);
                if (lifetime >= 0) child.self().setLifetime(lifetime);
                if (color != null) child.setRGBAColor(new Vector4f(color));
                if (size != null) child.transform().scale(new Vector3f(size));
                if (rotation != null) child.transform().rotation(new Quaternionf(rotation));
            }
            runtime.emit(emitter.getEffectExecutor());
        });
    }
}

