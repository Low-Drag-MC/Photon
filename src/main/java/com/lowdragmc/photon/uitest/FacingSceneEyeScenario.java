package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.client.scene.SceneCamera;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingDirectionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingMode;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingOrientationHelper;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Look-at facing turns a particle to the viewer in an editor scene too, whose camera {@code position()} is zero. */
@LDLRegisterClient(name = "photon_facing_scene_eye", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FacingSceneEyeScenario implements UIScenario {
    @Override
    public void define(ScenarioBuilder s) {
        s.step("look-at particles face the scene's eye", FacingSceneEyeScenario::check);
    }

    private static void check(TestContext ctx) {
        var emitter = new ParticleEmitter();
        var particle = new TileParticle(emitter, emitter.config);
        var at = particle.getWorldPos(0);
        var setting = particle.getRuntime().renderer.getFacingDirection();
        for (var mode : new FacingMode[]{FacingMode.LOOKAT_XYZ, FacingMode.LOOKAT_Y}) {
            // eyes on opposite sides: a particle facing the eye turns half a circle
            var east = facing(mode, setting, particle, at, new Vector3f(5, 0, 0));
            var west = facing(mode, setting, particle, at, new Vector3f(-5, 0, 0));
            ctx.check(mode + ": faces an eye to the east", Math.abs(east.x) > 0.99f, "±x", east);
            ctx.check(mode + ": and turns to one to the west", east.dot(west) < -0.99f, "opposite", west);
        }
    }

    /** Where the particle's facing axis points with the scene's eye at {@code eye} (relative to the particle). */
    private static Vector3f facing(FacingMode mode, FacingDirectionSetting setting, TileParticle particle, Vector3f at,
                                   Vector3f eye) {
        var camera = new SceneCamera();
        camera.setSceneEye(new Vec3(at.x + eye.x, at.y + eye.y, at.z + eye.z));
        return FacingOrientationHelper.compute(mode, setting, particle, camera, 0).transform(new Vector3f(0, 0, 1));
    }
}
