package com.lowdragmc.photon.client.render;

import com.lowdragmc.lowdraglib2.client.scene.SceneCamera;
import net.minecraft.client.Camera;
import org.joml.Vector3f;

/**
 * The two camera positions Photon extraction must keep apart:
 * <ul>
 *   <li>{@link #renderOrigin} — what vertex positions are made relative to ({@code camera.position()}).
 *       The editor's {@link SceneCamera} intentionally returns ZERO there (world-space extraction).</li>
 *   <li>{@link #facingEye} — the true eye position for view-dependent math (billboard planes, ribbon
 *       up-vectors). Using {@code position()} for this breaks in the editor, where it is ZERO.</li>
 * </ul>
 */
public final class PhotonCameraUtils {

    private PhotonCameraUtils() {
    }

    /** Origin that emitted vertex positions must be relative to. */
    public static Vector3f renderOrigin(Camera camera) {
        return camera.position().toVector3f();
    }

    /** The real eye position for facing/orientation math. */
    public static Vector3f facingEye(Camera camera) {
        if (camera instanceof SceneCamera scene) {
            return scene.sceneEye().toVector3f();
        }
        return camera.position().toVector3f();
    }
}
