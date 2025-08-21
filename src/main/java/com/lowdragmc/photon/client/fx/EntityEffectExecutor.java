package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Math;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote EntityEffect
 */
@OnlyIn(Dist.CLIENT)
public class EntityEffectExecutor extends FXEffectExecutor {
    public enum AutoRotate {
        NONE,
        FORWARD,
        LOOK,
        XROT,
    }
    public static Map<Entity, List<EntityEffectExecutor>> CACHE = new HashMap<>();
    public final Entity entity;
    public final AutoRotate autoRotate;

    public EntityEffectExecutor(FX fx, Level level, Entity entity, AutoRotate autoRotate) {
        super(fx, level);
        this.entity = entity;
        this.autoRotate = autoRotate;
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
            if (!entity.isAlive()) return;
            var position = entity.getEyePosition(partialTicks);
            runtime.root.updatePos(new Vector3f((float) (position.x + offset.x), (float) (position.y + offset.y), (float) (position.z + offset.z)));
            if (autoRotate != AutoRotate.NONE) {
                switch (autoRotate) {
                    case FORWARD -> {
                        var forward = entity.getForward();
                        var newRotation = new Quaternionf(rotation).rotateXYZ(
                                0,
                                (float) Math.atan2(-forward.z, forward.x),
                                (float) forward.y
                        );
                        runtime.root.updateRotation(newRotation);
                    }
                    case LOOK -> {
                        var lookAngles = entity.getLookAngle();
                        var newRotation = new Quaternionf(rotation).rotateXYZ(
                                0,
                                (float) Math.atan2(-lookAngles.z, lookAngles.x),
                                (float) lookAngles.y
                        );
                        runtime.root.updateRotation(newRotation);
                    }
                    case XROT -> {
                        var newRotation = new Quaternionf(rotation).rotateXYZ(
                                0,
                                Math.toRadians(-90 - entity.getVisualRotationYInDegrees()),
                                0
                        );
                        runtime.root.updateRotation(newRotation);
                    }
                }
            }
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
        root.updatePos(entity.getEyePosition().toVector3f().add(offset.x, offset.y, offset.z));
        root.updateRotation(rotation);
        root.updateScale(scale);
        this.runtime.emmit(this, delay);
        effects.add(this);
    }
}
