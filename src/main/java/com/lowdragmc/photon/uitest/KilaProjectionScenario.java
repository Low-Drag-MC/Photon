package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KilaMaterial's ground projection on a one-block step and a slab: projected, the ring lands on the ground and
 * both tops; with a short reach, on the two tops only.
 */
@LDLRegisterClient(name = "kila_projection", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaProjectionScenario implements UIScenario {
    /** between the slab's top (0.5) and the step's (1) */
    private static final double HEIGHT = 0.75;
    private static final AtomicReference<Long> DAY_TIME = new AtomicReference<>();
    private static final AtomicReference<Boolean> DAYLIGHT = new AtomicReference<>(true);
    private static final List<BlockPos> BUILT = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render").requiresWorld(true);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.server("pin the sun at noon", sc -> {
                    var level = sc.level();
                    DAY_TIME.set(level.getDayTime());
                    DAYLIGHT.set(level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
                    level.setDayTime(6000);
                })
                .ticks(2)
                .waitUntil("noon reached the client", ctx -> ctx.mc().level != null
                        && Math.abs(ctx.mc().level.getDayTime() % 24000 - 6000) < 40)
                .ticks(10)
                .step("look down at the ground ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(30);
                    player.xRotO = 30;
                    player.yRotO = player.getYRot();
                })
                .frames(20)
                .screenshot("bare")
                .server("build a step and a slab", sc -> {
                    var player = sc.player();
                    var direction = player.getDirection();
                    var centre = player.blockPosition().relative(direction, 4);
                    var step = centre.relative(direction.getClockWise());
                    var slab = centre.relative(direction.getCounterClockWise());
                    sc.level().setBlockAndUpdate(step, Blocks.STONE.defaultBlockState());
                    sc.level().setBlockAndUpdate(slab, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                    BUILT.add(step);
                    BUILT.add(slab);
                })
                .ticks(5)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(20)
                .screenshot("terrain");
        round(s, "floating", 0);
        round(s, "projected", 1.5f);
        round(s, "shallow", 0.3f);
        s.step("the pictures", KilaProjectionScenario::comparePictures)
                .teardown("stop any effect", KilaProjectionScenario::stop)
                .teardown("look ahead again", ctx -> ctx.requirePlayer().setXRot(0))
                .teardownServer("put the world back", sc -> {
                    for (var pos : BUILT) sc.level().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    BUILT.clear();
                    if (DAY_TIME.get() == null) return;
                    sc.level().setDayTime(DAY_TIME.get());
                    sc.level().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(DAYLIGHT.get(), sc.level().getServer());
                    DAY_TIME.set(null);
                });
    }

    private static void round(ScenarioBuilder s, String tag, float reach) {
        s.step("start " + tag, ctx -> start(ctx, ring(reach)))
                .frames(10)
                .screenshot(tag)
                .step("stop " + tag, KilaProjectionScenario::stop);
    }

    /** A ring; reach 0 leaves it a plain quad. */
    private static KilaMaterial ring(float reach) {
        var material = new KilaMaterial();
        material.worldLight = false;
        material.fog = false;
        material.main.texture.texture = KilaTextures.RING;
        material.color = new HDRColor(1f, 0.2f, 0.9f, 1f, 1f);
        material.projection.setEnable(reach > 0);
        material.projection.depth = reach;
        material.projection.fade = 1;
        return material;
    }

    private static void start(TestContext ctx, KilaMaterial material) {
        stop(ctx);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        // the decal is half of it: three blocks across
        config.setStartSize(new NumberFunction3(6, 6, 6));
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
        var pos = player.blockPosition().relative(player.getDirection(), 4);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0.5, HEIGHT, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
        ctx.check("the effect started", executor.getRuntime() != null, "a runtime", "null");
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) executor.getRuntime().destroy(true);
        ctx.state().remove("executor");
    }

    private static ScreenshotCompare capture(TestContext ctx, String tag) {
        var capture = ScreenshotCompare.load(ctx, tag);
        ctx.require("the " + tag + " capture exists", capture != null);
        return capture;
    }

    private static void comparePictures(TestContext ctx) {
        var window = ctx.mc().getWindow();
        int w = window.getWidth();
        int h = window.getHeight();
        var region = new ScreenshotCompare.Region(w / 10, h / 10, w * 9 / 10, h * 4 / 5);
        var bare = capture(ctx, "bare");
        var terrain = capture(ctx, "terrain");
        var blocks = terrain.diff(bare, 20, region);
        ctx.require("the step and the slab are on screen", blocks.changed() != null && blocks.count() > 2000);

        var floating = capture(ctx, "floating").diff(terrain, 40, region);
        var projected = capture(ctx, "projected").diff(terrain, 40, region);
        var shallow = capture(ctx, "shallow").diff(terrain, 40, region);
        ctx.check("the floating ring is on screen", floating.count() > 2000, "> 2000 px",
                floating.count() + " px at " + floating.box());
        ctx.check("the projected ring is on screen", projected.count() > 2000, "> 2000 px",
                projected.count() + " px at " + projected.box());
        var moved = capture(ctx, "projected").diff(capture(ctx, "floating"), 40, region);
        ctx.check("projected, it lies on the ground, not where the quad floats", moved.count() > floating.count() / 2,
                "> " + floating.count() / 2 + " px", moved.count() + " px at " + moved.box());
        ctx.check("reaching a little below the quad, it lands on less", shallow.count() < projected.count() / 2
                        && shallow.count() > 100, "100 .. " + projected.count() / 2 + " px",
                shallow.count() + " px at " + shallow.box());
        var box = blocks.changed();
        var lands = shallow.changed();
        ctx.check("and only on the blocks standing up into it", lands != null && lands.left() >= box.left() - 4
                        && lands.right() <= box.right() + 4 && lands.top() >= box.top() - 4 && lands.bottom() <= box.bottom() + 4,
                "inside " + box, String.valueOf(lands));
    }
}
