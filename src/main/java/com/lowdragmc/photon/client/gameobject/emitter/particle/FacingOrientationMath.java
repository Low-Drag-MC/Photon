package com.lowdragmc.photon.client.gameobject.emitter.particle;

import org.joml.Quaternionf;
import org.joml.Vector3f;

final class FacingOrientationMath {

    private static final Vector3f WORLD_UP = new Vector3f(0, 1, 0);
    private static final Vector3f LOCAL_X = new Vector3f(1, 0, 0);
    private static final float MIN_THRESHOLD = 0.0001f;

    private FacingOrientationMath() {
    }

    static Quaternionf computeRotateY(float cameraYRot) {
        float yaw = (float) Math.toRadians(180 - cameraYRot);
        return new Quaternionf().rotationY(yaw);
    }

    static Quaternionf computeLookAtXYZ(Vector3f particlePos, Vector3f cameraPos) {
        Vector3f toCamera = new Vector3f(cameraPos).sub(particlePos);
        if (toCamera.lengthSquared() < MIN_THRESHOLD) {
            return new Quaternionf();
        }
        return createLookAtRotation(toCamera.normalize(), WORLD_UP);
    }

    static Quaternionf computeLookAtY(Vector3f particlePos, Vector3f cameraPos) {
        float dx = cameraPos.x - particlePos.x;
        float dz = cameraPos.z - particlePos.z;

        if (dx * dx + dz * dz < MIN_THRESHOLD) {
            return new Quaternionf();
        }
        float yaw = (float) Math.atan2(dx, dz);
        return new Quaternionf().rotationY(yaw);
    }

    static Quaternionf computeLookAtDirection(Vector3f direction, Vector3f particlePos, Vector3f cameraPos) {
        Vector3f dir = normalizeDirection(direction, false);
        if (dir.lengthSquared() < MIN_THRESHOLD) {
            return computeLookAtXYZ(particlePos, cameraPos);
        }

        Quaternionf axisRotation = new Quaternionf().rotationTo(LOCAL_X, dir);
        Vector3f cameraInAxisSpace = new Vector3f(cameraPos).sub(particlePos);
        if (cameraInAxisSpace.lengthSquared() < MIN_THRESHOLD) {
            return axisRotation;
        }
        new Quaternionf(axisRotation).conjugate().transform(cameraInAxisSpace);

        float xRotation = (float) Math.atan2(-cameraInAxisSpace.y, cameraInAxisSpace.z);
        return new Quaternionf(axisRotation).mul(new Quaternionf().rotationX(xRotation));
    }

    static Quaternionf computeDirectionX(Vector3f direction) {
        Vector3f dir = normalizeDirection(direction, true);
        if (dir.lengthSquared() < MIN_THRESHOLD) {
            return new Quaternionf();
        }

        float y = (float) Math.atan2(dir.x, dir.z);
        float z = (float) Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z));
        return new Quaternionf().rotationYXZ(y - (float) Math.PI / 2, 0, z);
    }

    static Quaternionf computeDirectionY(Vector3f direction) {
        Vector3f dir = normalizeDirection(direction, true);
        if (dir.lengthSquared() < MIN_THRESHOLD) {
            return new Quaternionf();
        }

        float y = (float) Math.atan2(dir.x, dir.z);
        float x = (float) Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z));
        return new Quaternionf().rotationYXZ(y - (float) Math.PI, x - (float) Math.PI / 2, 0);
    }

    static Quaternionf computeDirectionZ(Vector3f direction) {
        Vector3f dir = normalizeDirection(direction, true);
        if (dir.lengthSquared() < MIN_THRESHOLD) {
            return new Quaternionf();
        }

        float y = (float) Math.atan2(dir.x, dir.z);
        float x = (float) Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z));
        return new Quaternionf().rotationYXZ(y, -x, 0);
    }

    static Quaternionf computeEmitterPlane(FacingMode mode, Quaternionf spaceRotation) {
        Quaternionf rotation = new Quaternionf(spaceRotation);
        return switch (mode) {
            case EMITTER_TRANSFORM_XZ -> rotation.rotateX(-(float) Math.PI / 2);
            case EMITTER_TRANSFORM_YZ -> rotation.rotateY((float) Math.PI / 2);
            default -> rotation;
        };
    }

    static Vector3f toWorldDirection(Vector3f localDirection, Quaternionf spaceRotation) {
        return new Quaternionf(spaceRotation).transform(new Vector3f(localDirection));
    }

    private static Quaternionf createLookAtRotation(Vector3f forward, Vector3f up) {
        return new Quaternionf().lookAlong(new Vector3f(forward).negate(), up).invert();
    }

    private static Vector3f normalizeDirection(Vector3f direction, boolean applyDirectionModeVerticalHack) {
        Vector3f dir = new Vector3f(direction);
        if (dir.lengthSquared() < MIN_THRESHOLD) {
            return new Vector3f();
        }
        dir.normalize();
        if (applyDirectionModeVerticalHack) {
            if (dir.x == 0 && dir.z == 0 && dir.y > 0) {
                dir.y = -1;
            } else if (dir.x == 0 && dir.z == 0 && dir.y < 0) {
                dir.y = 1;
                dir.z = -0.00001f;
            }
        }
        return dir;
    }
}
