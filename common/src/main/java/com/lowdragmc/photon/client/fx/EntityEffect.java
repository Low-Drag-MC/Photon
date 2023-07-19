package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote EntityEffect
 */
@Environment(EnvType.CLIENT)
public class EntityEffect extends FXEffect {
    public static Map<Entity, List<EntityEffect>> CACHE = new HashMap<>();
    public final Entity entity;

    public EntityEffect(FX fx, Level level, Entity entity) {
        super(fx, level);
        this.entity = entity;
    }

    @Override
    public boolean updateEmitter(IParticleEmitter emitter) {
        if (!entity.isAlive()) {
            emitter.remove(forcedDeath);
            return forcedDeath;
        } else {
            emitter.updatePos(new Vector3f((float) (entity.getX() + xOffset), (float) (entity.getY() + yOffset), (float) (entity.getZ() + zOffset)));
        }
        return false;
    }

    @Override
    public void start() {
        if (!entity.isAlive()) return;
        this.emitters.clear();
        this.emitters.addAll(fx.generateEmitters());
        if (this.emitters.isEmpty()) return;
        if (!allowMulti) {
            var effects = CACHE.computeIfAbsent(entity, p -> new ArrayList<>());
            var iter = effects.iterator();
            while (iter.hasNext()) {
                var effect = iter.next();
                boolean removed = false;
                if (effect.emitters.stream().noneMatch(e -> e.self().isAlive())) {
                    iter.remove();
                    removed = true;
                }
                if (effect.fx.equals(fx) && !removed) {
                    return;
                }
            }
            effects.add(this);
        }
        var realPos = entity.getPosition(0).toVector3f().add((float) xOffset, (float) yOffset, (float) zOffset);
        for (var emitter : emitters) {
            if (!emitter.isSubEmitter()) {
                emitter.reset();
                emitter.self().setDelay(delay);
                emitter.emmitToLevel(this, level, realPos.x, realPos.y, realPos.z, xRotation, yRotation, zRotation);
            }
        }
    }
}
