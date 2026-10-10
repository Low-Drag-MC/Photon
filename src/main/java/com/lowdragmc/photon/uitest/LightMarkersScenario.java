package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.LightDebug;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.render.PhotonCameraUtils;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.function.Consumer;

/** {@code /photonlight markers} draws each light's cube where the light is, also after the view turns. */
@LDLRegisterClient(name = "dynamic_light_markers", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightMarkersScenario implements UIScenario {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Vec3[] CAMERA = {Vec3.ZERO};
    private static final Consumer<FrameGraphSetupEvent> CAPTURE = event -> {
        VIEW_PROJECTION.set(PhotonCameraUtils.levelProjection()).mul(event.getModelViewMatrix());
        CAMERA[0] = event.getCameraState().pos;
    };

    @Override
    public void define(ScenarioBuilder s) {
        s.server("no animals", sc -> NoMobs.claim(sc.player()))
                .step("markers on, a still view", ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                    LightTestConfig.set(ctx.mc().options.bobView(), false);
                    ctx.mc().options.hideGui = true;
                    LightDebug.markers = true;
                    NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, CAPTURE);
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .frames(5)
                .step("a red light ahead", ctx -> {
                    var at = ctx.requirePlayer().getEyePosition().add(ctx.requirePlayer().getLookAngle().scale(3));
                    ctx.put("at", at);
                    ctx.put("light", PhotonLights.add(new DynamicLight().at(at.x, at.y, at.z).color(1f, 0.1f, 0.1f)
                            .intensity(2).range(4).shadows(false)));
                })
                .frames(5)
                .screenshot("ahead")
                .step("its marker is on it", ctx -> check(ctx, "ahead"))
                .step("turn", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setYRot(player.getYRot() + 25);
                    player.yRotO = player.getYRot();
                })
                .frames(5)
                .screenshot("turned")
                .step("and stays on it as the view turns", ctx -> check(ctx, "turned"))
                .teardown("markers off", ctx -> {
                    var light = ctx.<DynamicLight>get("light");
                    if (light != null) PhotonLights.remove(light);
                    LightDebug.markers = false;
                    NeoForge.EVENT_BUS.unregister(CAPTURE);
                    ctx.mc().options.hideGui = false;
                    LightTestConfig.restore();
                })
                .teardownServer("animals back", sc -> NoMobs.release(sc.player()));
    }

    private static void check(TestContext ctx, String shot) {
        var capture = ScreenshotCompare.load(ctx, shot);
        ctx.require("the " + shot + " capture exists", capture != null);
        var at = ctx.<Vec3>get("at");
        var camera = CAMERA[0];
        var clip = VIEW_PROJECTION.transform(new Vector4f((float) (at.x - camera.x), (float) (at.y - camera.y),
                (float) (at.z - camera.z), 1f));
        var window = ctx.mc().getWindow();
        int x = Math.round((clip.x / clip.w * 0.5f + 0.5f) * window.getWidth());
        int y = Math.round((0.5f - clip.y / clip.w * 0.5f) * window.getHeight());
        boolean red = false;
        for (int dy = -3; dy <= 3 && !red; dy++) {
            for (int dx = -3; dx <= 3 && !red; dx++) {
                int rgb = capture.rgb(x + dx, y + dy);
                red = ((rgb >> 16) & 0xFF) > 180 && ((rgb >> 8) & 0xFF) < 90 && (rgb & 0xFF) < 90;
            }
        }
        int rgb = capture.rgb(x, y);
        ctx.check("the marker is drawn at the light (" + shot + ")", red, "red at " + x + "," + y,
                "%d %d %d".formatted((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
    }
}
