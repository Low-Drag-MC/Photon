package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

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
public class EntityEffect implements IFXEffect {
    public static Map<Entity, List<EntityEffect>> CACHE = new HashMap<>();
    @Getter
    public final FX fx;
    public final Level level;
    public final Entity entity;
    @Setter
    private double xOffset, yOffset, zOffset;
    @Setter
    private int delay;
    @Setter
    private boolean forcedDeath;
    @Setter
    private boolean allowMulti;
    //runtime
    @Getter
    private final List<IParticleEmitter> emitters = new ArrayList<>();

    public EntityEffect(FX fx, Level level, Entity entity) {
        this.fx = fx;
        this.level = level;
        this.entity = entity;
    }

    @Override
    public void setOffset(double x, double y, double z) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
    }

    @Override
    public boolean updateEmitter(IParticleEmitter emitter) {
        if (entity.isRemoved()) {
            emitter.remove(forcedDeath);
            return forcedDeath;
        } else {
            emitter.self().setPos(entity.getX() + xOffset, entity.getY() + yOffset, entity.getZ() + zOffset);
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
            emitter.reset();
            emitter.self().setDelay(delay);
            emitter.setFXEffect(this);
            emitter.emmitToLevel(level, realPos.x, realPos.y, realPos.z);
        }
    }

}
