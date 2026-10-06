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
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;

import java.util.ArrayList;
import java.util.List;

/** Every KilaMaterial preset side by side, for a person to look at; a second capture shows the scrolling moves. */
@LDLRegisterClient(name = "kila_gallery", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaGalleryScenario implements UIScenario {
    private static final int COLUMNS = 5;

    @Override
    public void define(ScenarioBuilder s) {
        s.step("look straight ahead", ctx -> ctx.requirePlayer().setXRot(0))
                .frames(4)
                .screenshot("empty")
                .step("put up the presets", KilaGalleryScenario::start)
                .frames(30)
                .screenshot("gallery")
                .frames(20)
                .screenshot("gallery_later")
                .step("the wall is drawn and moving", KilaGalleryScenario::check)
                .teardown("take it down", KilaGalleryScenario::stop);
    }

    private static void start(TestContext ctx) {
        stop(ctx);
        var player = ctx.requirePlayer();
        var ahead = player.getDirection();
        var right = ahead.getClockWise();
        var executors = new ArrayList<BlockEffectExecutor>();
        var presets = KilaPresets.ALL;
        for (int i = 0; i < presets.size(); i++) {
            var preset = presets.get(i);
            int column = i % COLUMNS;
            int row = i / COLUMNS - 1;
            double along = 9;
            double across = (column - (COLUMNS - 1) / 2.0) * 3.0;
            double up = 3.7 - row * 2.5;
            var emitter = emitter(preset.create(), preset == KilaPresets.ENERGY_SHIELD || preset == KilaPresets.CRYSTAL);
            var fx = new FX();
            fx.getFxData().objects().add(emitter);
            var origin = player.blockPosition();
            var executor = new BlockEffectExecutor(fx, player.level(), origin);
            executor.setOffset(ahead.getStepX() * along + right.getStepX() * across + 0.5,
                    up, ahead.getStepZ() * along + right.getStepZ() * across + 0.5);
            executor.setAllowMulti(true);
            executor.start();
            executors.add(executor);
        }
        ctx.put("executors", executors);
    }

    private static ParticleEmitter emitter(KilaMaterial material, boolean sphere) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        double size = sphere ? 1.8 : 1.2;
        config.setStartSize(new NumberFunction3(size, size, size));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        if (sphere) {
            config.renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
            config.renderer.setModel(new MeshData(new ObjModelSource(Photon.id("models/sphere.obj"))));
        }
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(material));
        return emitter;
    }

    private static void check(TestContext ctx) {
        var empty = ScreenshotCompare.load(ctx, "empty");
        var gallery = ScreenshotCompare.load(ctx, "gallery");
        var later = ScreenshotCompare.load(ctx, "gallery_later");
        ctx.require("all three captures were written", empty != null && gallery != null && later != null);
        var region = gallery.whole();
        var drawn = gallery.diff(empty, 24, region);
        int expected = KilaPresets.ALL.size() * 1200;
        ctx.check("the presets are on screen", drawn.count() > expected, "> " + expected + " px",
                drawn.count() + " px at " + drawn.box());
        var moved = later.diff(gallery, 12, region);
        ctx.check("scrolling and rotating presets move between captures", moved.count() > 2000, "> 2000 px",
                moved.count() + " px at " + moved.box());
    }

    private static void stop(TestContext ctx) {
        List<BlockEffectExecutor> executors = ctx.get("executors");
        if (executors != null) {
            for (var executor : executors) {
                if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
            }
        }
        ctx.state().remove("executors");
    }
}
