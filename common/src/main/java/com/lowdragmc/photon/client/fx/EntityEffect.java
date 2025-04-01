package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.*;

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
    public void updateFXObjectTick(IFXObject fxObject) {
        if (runtime != null && fxObject == runtime.root) {
            if (!entity.isAlive()) {
                runtime.destroy(forcedDeath);
                CACHE.computeIfAbsent(entity, p -> new ArrayList<>()).remove(this);
                if (CACHE.get(entity).isEmpty()) {
                    CACHE.remove(entity);
                }
            }
        }
    }

    @Override
    public void updateFXObjectFrame(IFXObject fxObject, float partialTicks) {
        if (runtime != null && fxObject == runtime.root) {
            var position = entity.getPosition(partialTicks);
            runtime.root.updatePos(new Vector3f((float) (position.x + offset.x), (float) (position.y + offset.y), (float) (position.z + offset.z)));
        }
    }

    @Override
    public void start() {
        if (!entity.isAlive()) return;

        var effects = CACHE.computeIfAbsent(entity, p -> new ArrayList<>());
        if (!allowMulti) {
            var iter = effects.iterator();
            while (iter.hasNext()) {
                var effect = iter.next();
                boolean removed = false;
                if (effect.runtime != null && !effect.runtime.isAlive()) {
                    iter.remove();
                    removed = true;
                }
                if ((effect.fx.equals(fx) || Objects.equals(effect.fx.getFxLocation(), fx.getFxLocation())) && !removed) {
                    return;
                }
            }
        }
        this.runtime = fx.createRuntime();
        var root = this.runtime.getRoot();
        root.updatePos(entity.getPosition(0).toVector3f().add(offset.x, offset.y, offset.z));
        root.updateRotation(rotation);
        root.updateScale(scale);
        this.runtime.emmit(this);
        effects.add(this);
    }
}
