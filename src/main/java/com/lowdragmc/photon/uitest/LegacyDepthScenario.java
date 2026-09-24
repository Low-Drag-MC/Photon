package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

import java.util.List;

/**
 * Checks the legacy (forward-Z) depth shim: a legacy probe shader must match its reverse-Z twin pixel for pixel,
 * on the CPU and the instanced path.
 */
@LDLRegisterClient(name = "legacy_depth", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LegacyDepthScenario implements UIScenario {

    private static final String LEGACY = "devtest/depth_probe_legacy";
    private static final String NATIVE = "devtest/depth_probe_native";

    private record Round(String tag, String shader, boolean instanced) {
    }

    private static final List<Round> ROUNDS = List.of(
            new Round("native_cpu", NATIVE, false),
            new Round("legacy_cpu", LEGACY, false),
            new Round("legacy_inst", LEGACY, true));

    @Override
    public void define(ScenarioBuilder s) {
        s.step("look slightly down at the ground ahead", ctx -> {
            var player = ctx.mc().player;
            ctx.require("the client player exists", player != null);
            player.setXRot(20f);
        }).frames(4);
        for (var round : ROUNDS) {
            s.step("probe: " + round.tag(), ctx -> start(ctx, round))
                    .frames(10)
                    .screenshot(round.tag())
                    .step("stop " + round.tag(), LegacyDepthScenario::stop);
        }
        s.step("the legacy convention reconstructs what the native one does", LegacyDepthScenario::compare);
        s.teardown("stop the probe", LegacyDepthScenario::stop);
    }

    private static void start(TestContext ctx, Round round) {
        stop(ctx);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        // big enough to cover the whole view from 3 blocks away
        config.setStartSize(new NumberFunction3(60, 60, 60));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        // opaque, and drawn regardless of what is behind it: the probe only READS the scene depth
        var setting = new MaterialSetting(new CustomShaderMaterial(Photon.id(round.shader())))
                .setDepthTest(false).setDepthMask(false).setCull(false);
        setting.getBlendMode().setEnableBlend(false);
        config.renderer.getMaterials().add(setting);
        config.renderer.setUseGPUInstance(round.instanced());

        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        var player = ctx.mc().player;
        ctx.require("the client player exists", player != null);
        var pos = player.blockPosition().relative(player.getDirection(), 3);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0, 1.5, 0);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("probe", executor);
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("probe");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("probe");
    }

    private static boolean isSky(int rgb) {
        var r = (rgb >> 16) & 0xFF;
        var g = (rgb >> 8) & 0xFF;
        var b = rgb & 0xFF;
        return r > 200 && b > 200 && g < 60;
    }

    private static int grey(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static void compare(TestContext ctx) {
        var nativeCpu = ScreenshotCompare.load(ctx, "native_cpu");
        ctx.require("the native capture was written", nativeCpu != null);
        var width = nativeCpu.width();
        var height = nativeCpu.height();
        var whole = new ScreenshotCompare.Region(0, 0, width, height);

        // 1. the native probe on its own: sky above, ground below, further away toward the horizon
        var x = width / 2;
        ctx.check("the native probe paints the sky at the top of the view", isSky(nativeCpu.rgb(x, height / 20)),
                "magenta", Integer.toHexString(nativeCpu.rgb(x, height / 20)));
        var low = nativeCpu.rgb(x, height * 19 / 20);
        var mid = nativeCpu.rgb(x, height * 3 / 5);
        ctx.check("the native probe sees ground at the bottom of the view", !isSky(low), "grey",
                Integer.toHexString(low));
        ctx.check("the ground gets further away toward the horizon", !isSky(mid) && grey(mid) > grey(low),
                "grey(" + (height * 3 / 5) + ") > grey(" + (height * 19 / 20) + ")",
                Integer.toHexString(mid) + " vs " + Integer.toHexString(low));

        // 2. the legacy probe, through the shim, on both geometry paths: the same picture
        for (var tag : List.of("legacy_cpu", "legacy_inst")) {
            var legacy = ScreenshotCompare.load(ctx, tag);
            if (legacy == null) {
                ctx.check(tag + " was written", false, "an image", "missing");
                continue;
            }
            var diff = nativeCpu.diff(legacy, 8, whole);
            ctx.log("%s vs native: %d px (%.4f%%) %s".formatted(tag, diff.count(), 100 * diff.fraction(), diff.box()));
            ctx.check(tag + " matches the native reconstruction", diff.fraction() < 0.001,
                    "< 0.1% of the picture", "%.4f%% (%s)".formatted(100 * diff.fraction(), diff.box()));
        }
    }
}
