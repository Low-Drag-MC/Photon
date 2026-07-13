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
 * Plays an FX attached to an entity: the FX root follows the entity's eye position every frame
 * (plus the configured offset/rotation, optionally auto-rotated to the entity's facing) and the FX
 * is destroyed when the entity dies. All accesses run on the client tick/render thread.
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
        if (runtime == null || fxObject != runtime.root) {
            return;
        }
        if (!entity.isAlive()) {
            // anchor gone: stop the FX (force drops remnants immediately) and retire right away
            runtime.destroy(forcedDeath);
            retire(CACHE, entity);
        } else if (runtimeEnded()) {
            // self-evict finished runtimes instead of lingering until the next same-key start()
            retire(CACHE, entity);
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
        if (shouldSkipStart(effects)) {
            return;
        }
        resetFinishedNotification();
        this.runtime = fx.createRuntime();
        var root = this.runtime.getRoot();
        root.updatePos(entity.getEyePosition().toVector3f().add(offset.x, offset.y, offset.z));
        root.updateRotation(rotation);
        root.updateScale(scale);
        this.runtime.emit(this, delay);
        effects.add(this);
    }
}
