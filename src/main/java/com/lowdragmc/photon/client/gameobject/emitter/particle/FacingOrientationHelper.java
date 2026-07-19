package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class FacingOrientationHelper {

    private static final float MIN_THRESHOLD = 0.0001f;

    private FacingOrientationHelper() {
    }

    public static Quaternionf compute(
            FacingMode mode,
            FacingDirectionSetting settings,
            TileParticle particle,
            Camera camera,
            float partialTick
    ) {
        return switch (mode) {
            case ROTATE_Y -> computeRotateY(camera);
            case LOOKAT_XYZ -> computeLookAtXYZ(particle, camera, partialTick);
            case LOOKAT_Y -> computeLookAtY(particle, camera, partialTick);
            case LOOKAT_DIRECTION -> computeLookAtDirection(settings, particle, camera, partialTick);
            case DIRECTION_X -> computeDirectionX(settings, particle);
            case DIRECTION_Y -> computeDirectionY(settings, particle);
            case DIRECTION_Z -> computeDirectionZ(settings, particle);
            case EMITTER_TRANSFORM_XY -> computeEmitterXY(particle);
            case EMITTER_TRANSFORM_XZ -> computeEmitterXZ(particle);
            case EMITTER_TRANSFORM_YZ -> computeEmitterYZ(particle);
            default -> new Quaternionf();
        };
    }

    private static Quaternionf computeRotateY(Camera camera) {
        return FacingOrientationMath.computeRotateY(camera.yRot());
    }

    private static Quaternionf computeLookAtXYZ(TileParticle particle, Camera camera, float partialTick) {
        Vector3f particlePos = particle.getWorldPos(partialTick);
        Vector3f cameraPos = camera.position().toVector3f();
        return FacingOrientationMath.computeLookAtXYZ(particlePos, cameraPos);
    }

    private static Quaternionf computeLookAtY(TileParticle particle, Camera camera, float partialTick) {
        Vector3f particlePos = particle.getWorldPos(partialTick);
        Vector3f cameraPos = camera.position().toVector3f();
        return FacingOrientationMath.computeLookAtY(particlePos, cameraPos);
    }

    private static Quaternionf computeLookAtDirection(
            FacingDirectionSetting settings,
            TileParticle particle,
            Camera camera,
            float partialTick
    ) {
        Vector3f particlePos = particle.getWorldPos(partialTick);
        Vector3f cameraPos = camera.position().toVector3f();
        Vector3f toCamera = new Vector3f(cameraPos).sub(particlePos);
        if (toCamera.lengthSquared() < MIN_THRESHOLD) {
            return new Quaternionf();
        }
        toCamera.normalize();

        Vector3f direction = resolveDirection(settings, particle);
        if (direction.lengthSquared() < MIN_THRESHOLD) {
            return FacingOrientationMath.computeLookAtXYZ(particlePos, cameraPos);
        }
        return FacingOrientationMath.computeLookAtDirection(direction, particlePos, cameraPos);
    }

    private static Quaternionf computeDirectionX(FacingDirectionSetting settings, TileParticle particle) {
        Vector3f dir = resolveDirection(settings, particle);
        return FacingOrientationMath.computeDirectionX(dir);
    }

    private static Quaternionf computeDirectionY(FacingDirectionSetting settings, TileParticle particle) {
        Vector3f dir = resolveDirection(settings, particle);
        return FacingOrientationMath.computeDirectionY(dir);
    }

    private static Quaternionf computeDirectionZ(FacingDirectionSetting settings, TileParticle particle) {
        Vector3f dir = resolveDirection(settings, particle);
        return FacingOrientationMath.computeDirectionZ(dir);
    }

    private static Quaternionf computeEmitterXY(TileParticle particle) {
        return FacingOrientationMath.computeEmitterPlane(FacingMode.EMITTER_TRANSFORM_XY, particle.getSpaceRotation());
    }

    private static Quaternionf computeEmitterXZ(TileParticle particle) {
        return FacingOrientationMath.computeEmitterPlane(FacingMode.EMITTER_TRANSFORM_XZ, particle.getSpaceRotation());
    }

    private static Quaternionf computeEmitterYZ(TileParticle particle) {
        return FacingOrientationMath.computeEmitterPlane(FacingMode.EMITTER_TRANSFORM_YZ, particle.getSpaceRotation());
    }

    private static Vector3f resolveDirection(FacingDirectionSetting setting, TileParticle particle) {
        if (setting == null || setting.getMode() == FacingDirectionSetting.Mode.DERIVE_FROM_VELOCITY) {
            Vector3f velocity = particle.getRealVelocity();
            float threshold = setting != null ? setting.getMinSpeedThreshold() : 0.01f;
            if (velocity.lengthSquared() > threshold * threshold) {
                return velocity;
            }
            return new Vector3f(0, 0, 0);
        }
        return FacingOrientationMath.toWorldDirection(setting.getCustomDirection(), particle.getSpaceRotation());
    }
}
