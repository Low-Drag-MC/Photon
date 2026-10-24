package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Stacked screen distortion under shader packs: stacks only under AFTER_PACK. Packs missing from
 * {@code shaderpacks/} are skipped. Writes {@code stack_pack.md}.
 */
@LDLRegisterClient(name = "kila_stack_pack", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaStackPackScenario implements UIScenario {
    private static final int LOAD_WAIT_FRAMES = 600;
    private static final double SIZE = 1.4;
    private static final int MIN_DRAWN_PIXELS = 5_000;

    /** {@code mode} null lets the resolver classify the pack. */
    private record Setup(String tag, String pack, @Nullable IrisCompositeMode mode) {
    }

    private record Round(String tag, Supplier<KilaMaterial> material) {
    }

    private static final List<Setup> SETUPS = List.of(
            new Setup("bsl_after", "BSL_v10.1.3.zip", IrisCompositeMode.AFTER_PACK),
            new Setup("bsl_auto", "BSL_v10.1.3.zip", null),
            new Setup("kappa_auto", "Kappa_v5.4_T2.zip", null));

    private static final List<Round> ROUNDS = List.of(
            new Round("solid", KilaStackPackScenario::solid),
            new Round("still_stack", () -> haze(0, true)),
            new Round("moving_stack", () -> haze(0.06f, true)),
            new Round("still_plain", () -> haze(0, false)),
            new Round("moving_plain", () -> haze(0.06f, false)));

    private static final AtomicReference<Long> DAY_TIME = new AtomicReference<>();
    private static final AtomicReference<Boolean> DAYLIGHT = new AtomicReference<>(true);
    private static final List<String> ROWS = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui", ctx -> {
                    HiddenGui.hide(ctx.mc());
                    ROWS.clear();
                })
                .server("no animals: they wander between the shots", sc -> NoMobs.claim(sc.player()))
                .server("pin the sun at noon", sc -> {
                    var level = sc.level();
                    DAY_TIME.set(TestClock.time(level));
                    DAYLIGHT.set(TestClock.advancing(level));
                    TestClock.setAdvancing(level, false);
                    TestClock.set(level, 6000);
                })
                .ticks(2)
                .waitUntil("noon reached the client", ctx -> ctx.mc().level != null
                        && Math.abs(TestClock.time(ctx.mc().level) % 24000 - 6000) < 40)
                .ticks(10)
                .step("look down at the ground ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(20);
                    player.xRotO = 20;
                    player.yRotO = player.getYRot();
                });

        for (var setup : SETUPS) {
            if (!LightTestConfig.hasShaderPack(setup.pack())) continue;
            int[] waited = {0};
            s.step("load " + setup.tag(), ctx -> {
                        waited[0] = 0;
                        IrisCompat.setModeOverride(setup.mode());
                        LightTestConfig.selectShaderPack(setup.pack());
                    })
                    .step("wait for " + setup.tag(), ctx -> {
                        if (!IrisCompat.isUsingShaderPack() && ++waited[0] < LOAD_WAIT_FRAMES) ctx.repeat(setup.tag());
                    })
                    .frames(120)
                    .screenshot("sp_base_" + setup.tag());
            for (var round : ROUNDS) {
                String name = round.tag() + "_" + setup.tag();
                s.step("start " + name, ctx -> start(ctx, round.material().get()))
                        .frames(10)
                        .screenshot("sp_" + name)
                        .step("after " + name, ctx -> {
                            ctx.put("stacking_" + name, ctx.<KilaMaterial>get("material").screenDistortion.stacking);
                            var target = IrisCompat.diagnosticsTarget();
                            ctx.put("mode_" + setup.tag(), target == null ? "none" : target.compositeMode().name());
                        })
                        .step("stop " + name, KilaStackPackScenario::stop);
            }
            s.step("the pictures (" + setup.tag() + ")", ctx -> compare(ctx, setup));
        }

        s.step("write the table", KilaStackPackScenario::writeTable)
                .teardown("stop any effect", KilaStackPackScenario::stop)
                .teardown("restore", ctx -> {
                    HiddenGui.restore(ctx.mc());
                    ctx.requirePlayer().setXRot(0);
                    IrisCompat.setModeOverride(null);
                    LightTestConfig.restore();
                })
                .teardownServer("animals back", sc -> NoMobs.release(sc.player()))
                .teardownServer("let the sun move again", sc -> {
                    if (DAY_TIME.get() == null) return;
                    TestClock.set(sc.level(), DAY_TIME.get());
                    TestClock.setAdvancing(sc.level(), DAYLIGHT.get());
                    DAY_TIME.set(null);
                });
    }

    private static KilaMaterial unlit() {
        var material = new KilaMaterial();
        material.worldLight = false;
        material.fog = false;
        material.main.texture.texture = KilaTextures.WHITE;
        return material;
    }

    private static KilaMaterial solid() {
        return unlit();
    }

    /** Held still, so captures compare. */
    private static KilaMaterial haze(float strength, boolean stack) {
        var material = unlit();
        material.screenDistortion.setEnable(true);
        material.screenDistortion.texture.noise(KilaTexture.Noise.GRADIENT, 3);
        material.screenDistortion.texture.scroll(0, 0);
        material.screenDistortion.strength.value = strength;
        material.screenDistortion.stack = stack;
        return material;
    }

    private static void start(TestContext ctx, KilaMaterial material) {
        stop(ctx);
        ctx.put("material", material);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(material));
        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        var player = ctx.requirePlayer();
        var pos = player.blockPosition().relative(player.getDirection(), 3);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0.5, 0.9, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    private static ScreenshotCompare capture(TestContext ctx, String name) {
        var capture = ScreenshotCompare.load(ctx, "sp_" + name);
        ctx.require("the " + name + " capture exists", capture != null);
        return capture;
    }

    private static void compare(TestContext ctx, Setup setup) {
        String tag = setup.tag();
        var window = ctx.mc().getWindow();
        var region = new ScreenshotCompare.Region(window.getWidth() / 6, window.getHeight() / 8,
                window.getWidth() * 5 / 6, window.getHeight() * 3 / 4);
        var base = capture(ctx, "base_" + tag);
        var located = capture(ctx, "solid_" + tag).diff(base, 60, region);
        ctx.require("the solid quad is on screen (" + tag + ")",
                located.changed() != null && located.count() > MIN_DRAWN_PIXELS);
        var quad = located.changed();
        // a pack's clouds drift between two captures, by more than the haze bends some runs: compare below the horizon
        int horizon = horizon(base, (quad.left() + quad.right()) / 2, quad.top(), quad.bottom());
        ctx.log("horizon at y " + horizon + " (" + tag + ")");
        var inside = new ScreenshotCompare.Region(quad.left() + 4, Math.max(quad.top(), horizon) + 4,
                quad.right() - 4, quad.bottom() - 4);
        // packs jitter TAA: weigh changes inside the quad against a box beside it
        var beside = beside(inside, window.getWidth());

        String mode = ctx.get("mode_" + tag);
        boolean afterPack = IrisCompositeMode.AFTER_PACK.name().equals(mode);
        boolean stacking = ctx.<Boolean>get("stacking_moving_stack_" + tag);
        ctx.check("stacks exactly when the layer lands after the pack (" + tag + ", " + mode + ")",
                stacking == afterPack, afterPack, stacking);

        var stillStack = capture(ctx, "still_stack_" + tag);
        var stillPlain = capture(ctx, "still_plain_" + tag);
        var movingStack = capture(ctx, "moving_stack_" + tag);
        var movingPlain = capture(ctx, "moving_plain_" + tag);
        int stillStackIn = stillStack.diff(base, 12, inside).count();
        int stillStackBeside = stillStack.diff(base, 12, beside).count();
        int stillPlainIn = stillPlain.diff(base, 12, inside).count();
        int movingStackIn = movingStack.diff(base, 12, inside).count();
        int movingStackBeside = movingStack.diff(base, 12, beside).count();
        int movingPlainIn = movingPlain.diff(base, 12, inside).count();

        ctx.check("the stacked haze bends the frame (" + tag + ")", movingStackIn > movingStackBeside * 2 + 2000,
                "> " + (movingStackBeside * 2 + 2000) + " px", movingStackIn + " px");
        if (stacking) {
            ctx.check("a still stacked haze leaves the finished frame alone (" + tag + ")",
                    stillStackIn < stillStackBeside * 3 / 2 + 300,
                    "< " + (stillStackBeside * 3 / 2 + 300) + " px", stillStackIn + " px");
        } else {
            int apart = movingStack.diff(movingPlain, 12, inside).count();
            int apartBeside = movingStack.diff(movingPlain, 12, beside).count();
            ctx.check("off the after-pack path a stacked haze draws as an unstacked one (" + tag + ")",
                    apart < apartBeside * 3 / 2 + movingPlainIn / 10 + 50,
                    "< " + (apartBeside * 3 / 2 + movingPlainIn / 10 + 50) + " px", apart + " px");
        }
        ROWS.add("| %s | %s | %s | %s | %d | %d | %d | %d | %d | %d |".formatted(tag, setup.pack(), mode,
                stacking, (inside.right() - inside.left()) * (inside.bottom() - inside.top()), stillStackBeside,
                stillStackIn, stillPlainIn, movingStackIn, movingPlainIn));
    }

    /** The first row of ground (green over blue) down column {@code x}, or {@code top} when there is no sky above it. */
    private static int horizon(ScreenshotCompare base, int x, int top, int bottom) {
        for (int y = Math.max(0, top); y < bottom; y++) {
            int c = base.rgb(x, y);
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (g > b + 30 && g >= r) return y;
        }
        return top;
    }

    /** {@code box} moved sideways, onto the same mix of sky and ground. */
    private static ScreenshotCompare.Region beside(ScreenshotCompare.Region box, int screenWidth) {
        int width = box.right() - box.left();
        int left = box.right() + width / 4;
        if (left + width > screenWidth) left = Math.max(0, box.left() - width - width / 4);
        return new ScreenshotCompare.Region(left, box.top(), left + width, box.bottom());
    }

    private static void writeTable(TestContext ctx) {
        var text = new StringBuilder("# Stacked screen distortion under shader packs\n\n")
                .append("Pixels inside the quad that differ from the empty scene (threshold 12). \"still\" is strength 0: ")
                .append("ideally invisible. \"beside\" is the still stacked capture in a same-sized box next to the quad.\n\n")
                .append("| setup | pack | mode | stacking | quad px | beside | still stack | still plain | moving stack | moving plain |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        ROWS.forEach(row -> text.append(row).append('\n'));
        var file = ctx.outDir().resolve("stack_pack.md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
        } catch (IOException e) {
            ctx.check("table written", false, file.toString(), e.getMessage());
        }
    }
}
