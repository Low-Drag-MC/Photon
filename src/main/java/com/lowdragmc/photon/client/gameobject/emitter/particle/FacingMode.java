package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;

/**
 * Additional facing algorithms for Billboard render mode.
 * DEFAULT keeps the original Photon billboard behavior.
 */
public enum FacingMode {
    DEFAULT,
    ROTATE_Y,
    LOOKAT_XYZ,
    LOOKAT_Y,
    LOOKAT_DIRECTION,
    DIRECTION_X,
    DIRECTION_Y,
    DIRECTION_Z,
    EMITTER_TRANSFORM_XY,
    EMITTER_TRANSFORM_XZ,
    EMITTER_TRANSFORM_YZ;

    public boolean requiresDirection() {
        return this == LOOKAT_DIRECTION || this == DIRECTION_X
                || this == DIRECTION_Y || this == DIRECTION_Z;
    }

    public Quaternionf compute(FacingDirectionSetting setting, TileParticle particle, Camera camera, float partialTick) {
        if (this == DEFAULT) {
            return camera.rotation();
        }
        return FacingOrientationHelper.compute(this, setting, particle, camera, partialTick);
    }
}
