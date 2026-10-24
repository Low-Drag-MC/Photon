package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ValueSpace;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting.Mode;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * Rotation over Lifetime in World space, per render mode: a camera-facing billboard has no world orientation, so it
 * keeps spinning in its own plane; a model or a flat quad takes the spin about the world axes, outside its own euler.
 */
@LDLRegisterClient(name = "rotation_space", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RotationSpaceScenario implements UIScenario {
    private static final float ROLL = 90;

    @Override
    public void define(ScenarioBuilder s) {
        for (var space : List.of(ValueSpace.Local, ValueSpace.World)) {
            for (var mode : List.of(Mode.Billboard, Mode.VerticalBillboard, Mode.StretchedBillboard, Mode.Horizontal, Mode.Model)) {
                s.step(mode + " in " + space + " space", ctx -> check(ctx, mode, space));
            }
        }
    }

    private static void check(TestContext ctx, Mode mode, ValueSpace space) {
        var level = ctx.level();
        ctx.require("a client level", level != null);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(false);
        config.setDuration(100);
        config.setStartLifetime(NumberFunction.constant(100));
        config.setStartSpeed(NumberFunction.constant(0));
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.cycles = 1;
        burst.setCount(NumberFunction.constant(1));
        config.emission.getBursts().add(burst);
        config.renderer.setRenderMode(mode);
        var rotation = config.rotationOverLifetime;
        rotation.setEnable(true);
        rotation.setSpace(space);
        rotation.setRoll(NumberFunction.constant(ROLL));

        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        var runtime = fx.createInternalRuntime();
        var executor = new CollisionBenchScenario.BenchExecutor(level, RandomSource.create(7L));
        for (var object : runtime.objects.values()) {
            object.setEffect(executor);
            object.reset();
            object.setLevel(level);
        }
        try {
            for (int i = 0; i < 5; i++) {
                for (var object : runtime.objects.values()) {
                    if (object instanceof FXObject fxObject) fxObject.tick();
                }
            }
            TileParticle particle = null;
            for (var queue : emitter.getParticles().values()) {
                for (var each : queue) {
                    if (each instanceof TileParticle tile) particle = tile;
                }
            }
            ctx.require("a particle was born", particle != null);
            float roll = particle.getRealRotation(1).z;
            boolean ownAxes = space == ValueSpace.Local || mode == Mode.Billboard || mode == Mode.VerticalBillboard
                    || mode == Mode.StretchedBillboard;
            float expected = ownAxes ? (float) Math.toRadians(ROLL) : 0;
            ctx.check("roll in the particle's own euler", Math.abs(roll - expected) < 1e-3, expected, roll);
        } finally {
            for (var object : runtime.objects.values()) {
                if (object instanceof FXObject fxObject) fxObject.remove(true);
            }
        }
    }
}
