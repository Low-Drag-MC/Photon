package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.lowdragmc.photon.client.render.PhotonCameraUtils;
import com.lowdragmc.photon.client.render.PhotonOit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where each stage lands in the world ({@code PhotonFrameSeams}, {@code PhotonOit}), under classic transparency,
 * improved transparency and a shader pack: an effect is drawn and hidden by a pillar in front of it; stained glass in
 * front tints an opaque effect; a translucent one is hidden by the glass in {@link FXCompositeMode#VANILLA}, drawn over
 * it in {@link FXCompositeMode#LATE}, and tinted by it when it joins the OIT passes.
 */
@LDLRegisterClient(name = "photon_frame_seams", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FrameSeamsScenario implements UIScenario {

    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();

    /** A pack whose particle programs light what they draw, so the effect stays recognisably green. */
    private static final String PACK = "BSL_v10.1.3.zip";

    /** {@code photonOit}: Photon's OIT config, which only matters with {@code oit}. */
    private record Round(String tag, boolean oit, boolean pack, RendererSetting.Layer layer, FXCompositeMode mode,
                         boolean photonOit) {
        Round(String tag, boolean oit, boolean pack, RendererSetting.Layer layer, FXCompositeMode mode) {
            this(tag, oit, pack, layer, mode, true);
        }

        boolean orderIndependent() {
            return oit && photonOit && layer == RendererSetting.Layer.Translucent;
        }
    }

    private static final List<Round> ROUNDS = List.of(
            new Round("classic_vanilla", false, false, RendererSetting.Layer.Translucent, FXCompositeMode.VANILLA),
            new Round("classic_late", false, false, RendererSetting.Layer.Translucent, FXCompositeMode.LATE),
            new Round("classic_opaque", false, false, RendererSetting.Layer.Opaque, FXCompositeMode.VANILLA),
            new Round("oit_vanilla", true, false, RendererSetting.Layer.Translucent, FXCompositeMode.VANILLA),
            new Round("oit_late", true, false, RendererSetting.Layer.Translucent, FXCompositeMode.LATE),
            new Round("oit_opaque", true, false, RendererSetting.Layer.Opaque, FXCompositeMode.VANILLA),
            new Round("oit_vanilla_seams", true, false, RendererSetting.Layer.Translucent, FXCompositeMode.VANILLA,
                    false),
            new Round("oit_late_seams", true, false, RendererSetting.Layer.Translucent, FXCompositeMode.LATE, false),
            // a pack turns improved transparency off, and draws every stage into Photon's layer
            new Round("pack_vanilla", false, true, RendererSetting.Layer.Translucent, FXCompositeMode.VANILLA),
            new Round("pack_late", false, true, RendererSetting.Layer.Translucent, FXCompositeMode.LATE),
            new Round("pack_opaque", false, true, RendererSetting.Layer.Opaque, FXCompositeMode.VANILLA));
    private static final Map<String, Integer> OIT_DRAWS = new ConcurrentHashMap<>();

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui, no section fade-in", ctx -> {
                    HiddenGui.hide(ctx.mc());
                    LightTestConfig.set(ctx.mc().options.chunkSectionFadeInTime(), 0.0);
                })
                // Iris turns improved transparency off while its shaders are enabled
                .step("shaders off", ctx -> {
                    if (IrisCompat.isModInstalled()) LightTestConfig.selectShaderPack(null);
                })
                .frames(5)
                .server("build the scene", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    build(sc.level(), origin);
                    // daylight: the effect is lit by the lightmap like any particle
                    TestClock.set(sc.level(), 6000);
                    var player = sc.player();
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo(sc.level(), origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + 0.5,
                            Set.<Relative>of(), 0f, 0f, true);
                })
                .serverTicks(10);
        Boolean[] modes = {false, true};
        for (var oit : modes) {
            s.step("improved transparency " + (oit ? "on" : "off"),
                            ctx -> LightTestConfig.set(ctx.mc().options.improvedTransparency(), oit))
                    .ticks(5)
                    .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                    .frames(10)
                    .step("the level is drawn " + (oit ? "with" : "without") + " order-independent transparency",
                            ctx -> ctx.require("improved transparency is " + (oit ? "on" : "off"),
                                    ctx.mc().gameRenderer.useImprovedTransparency() == oit));
            for (var round : ROUNDS) {
                if (round.oit() != oit || round.pack()) continue;
                s.step("effect: " + round.tag(), ctx -> {
                            LightTestConfig.set(PhotonConfig.INSTANCE.orderIndependentTransparency, round.photonOit());
                            start(ctx, round);
                        })
                        .frames(12)
                        .screenshot(round.tag())
                        .step("stop " + round.tag(), ctx -> {
                            OIT_DRAWS.put(round.tag(), PhotonOit.drawsLastFrame());
                            stop(ctx);
                        });
            }
        }
        if (LightTestConfig.hasShaderPack(PACK)) {
            int[] waited = {0};
            s.step("improved transparency off",
                            ctx -> LightTestConfig.set(ctx.mc().options.improvedTransparency(), false))
                    .step("load " + PACK, ctx -> LightTestConfig.selectShaderPack(PACK))
                    .step("wait for the pack", ctx -> {
                        if (!IrisCompat.isUsingShaderPack() && ++waited[0] < 600) ctx.repeat("pack");
                    })
                    .frames(120);
            for (var round : ROUNDS) {
                if (!round.pack()) continue;
                s.step("effect: " + round.tag(), ctx -> start(ctx, round))
                        .frames(30)
                        .screenshot(round.tag())
                        .step("stop " + round.tag(), FrameSeamsScenario::stop);
            }
        } else {
            s.step("no " + PACK, ctx -> LightTestConfig.missingShaderPack(ctx, PACK));
        }
        s.step("every stage lands where it belongs", FrameSeamsScenario::check);
        s.teardown("stop the effect", ctx -> {
            OIT_DRAWS.clear();
            stop(ctx);
            LightTestConfig.restore();
        });
        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
    }

    /** The camera at the origin's top, looking along +z: glass on the left, a pillar on the right, a wall behind. */
    static void build(ServerLevel level, BlockPos origin) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -2; z <= 16; z++) {
                for (int y = 1; y <= 10; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        for (int x = -10; x <= 10; x++) {
            for (int y = 1; y <= 10; y++) {
                level.setBlock(origin.offset(x, y, 14), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        for (int x = 1; x <= 8; x++) {
            for (int y = 1; y <= 6; y++) {
                level.setBlock(origin.offset(x, y, 4), Blocks.STAINED_GLASS.red().defaultBlockState(), 2);
            }
        }
        for (int y = 1; y <= 5; y++) {
            level.setBlock(origin.offset(-3, y, 6), Blocks.STONE.defaultBlockState(), 2);
        }
    }

    private static void start(TestContext ctx, Round round) {
        stop(ctx);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(12, 12, 12));
        config.setStartColor(NumberFunction.color(0xFF00FF00));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        var setting = new MaterialSetting(new TextureMaterial(Photon.id("textures/particle/white.png")));
        if (round.layer() == RendererSetting.Layer.Opaque) {
            setting.getBlendMode().setEnableBlend(false);
            setting.setDepthMask(true);
        }
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(setting);
        config.renderer.setLayer(round.layer());
        config.renderer.setCompositeMode(round.mode());

        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), ORIGIN.get().offset(0, 0, 9));
        executor.setOffset(0.5, 2.6, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("effect", executor);
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("effect");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("effect");
    }

    private static void check(TestContext ctx) {
        var origin = ORIGIN.get();
        // on the effect's plane (z + 9.5), seen past the glass's edge / through it / where the pillar is in front
        var open = Vec3.atLowerCornerOf(origin).add(-1.0, 2.6, 9.5);
        var throughGlass = Vec3.atLowerCornerOf(origin).add(3.5, 2.6, 9.5);
        var pillar = Vec3.atLowerCornerOf(origin).add(-2.5, 2.0, 6.0);
        for (var round : ROUNDS) {
            if (round.pack() && !LightTestConfig.hasShaderPack(PACK)) continue;
            var image = ScreenshotCompare.load(ctx, round.tag());
            if (image == null) {
                ctx.check(round.tag() + " was captured", false, "an image", "missing");
                continue;
            }
            var openRgb = sample(ctx, image, open);
            var glassRgb = sample(ctx, image, throughGlass);
            var pillarRgb = sample(ctx, image, pillar);
            if (round.pack()) {
                // the pack lights, fogs and grades the frame: green stays green, not #00ff00, and its glass is its own
                ctx.check(round.tag() + ": the effect is drawn", greenish(openRgb), "green", hex(openRgb));
                ctx.check(round.tag() + ": the pillar in front of it hides it", !greenish(pillarRgb), "stone",
                        hex(pillarRgb));
                continue;
            }
            ctx.check(round.tag() + ": the effect is drawn", green(openRgb), "green", hex(openRgb));
            ctx.check(round.tag() + ": the pillar in front of it hides it", !greenish(pillarRgb), "stone",
                    hex(pillarRgb));
            if (round.oit() && round.layer() == RendererSetting.Layer.Translucent) {
                var draws = OIT_DRAWS.getOrDefault(round.tag(), -1);
                ctx.check(round.tag() + (round.photonOit() ? ": it went through the OIT passes"
                        : ": it stayed out of the OIT passes"), round.photonOit() ? draws > 0 : draws == 0,
                        round.photonOit() ? "> 0" : "0", draws);
            }
            if (round.layer() == RendererSetting.Layer.Opaque || round.orderIndependent()) {
                ctx.check(round.tag() + ": the glass in front of it tints it", tinted(glassRgb, openRgb),
                        "redder and darker than " + hex(openRgb), hex(glassRgb));
            } else if (round.mode() == FXCompositeMode.LATE) {
                ctx.check(round.tag() + ": it draws over the glass in front of it", green(glassRgb), "green",
                        hex(glassRgb));
            } else {
                ctx.check(round.tag() + ": the glass in front of it hides it", !greenish(glassRgb),
                        "red glass over stone",
                        hex(glassRgb));
            }
        }
    }

    /** {@code world} as drawn this frame, averaged over a 5x5 box. */
    static int sample(TestContext ctx, ScreenshotCompare image, Vec3 world) {
        var camera = ctx.mc().gameRenderer.mainCamera();
        var relative = world.subtract(camera.position()).toVector3f();
        var clip = new Vector4f(relative, 1f)
                .mul(camera.getViewRotationMatrix(new Matrix4f()))
                .mul(PhotonCameraUtils.levelProjection());
        var x = (int) ((clip.x / clip.w * 0.5f + 0.5f) * image.width());
        var y = (int) ((0.5f - clip.y / clip.w * 0.5f) * image.height());
        int r = 0, g = 0, b = 0, n = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                var px = Math.clamp(x + dx, 0, image.width() - 1);
                var py = Math.clamp(y + dy, 0, image.height() - 1);
                var c = image.rgb(px, py);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        return (r / n) << 16 | (g / n) << 8 | (b / n);
    }

    static boolean green(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return g > 150 && r < 90 && b < 90;
    }

    /** Green dominating both other channels — a shader pack's shadowed stone is bluish grey, green above red too. */
    static boolean greenish(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return g > r + 30 && g > b + 20;
    }

    /** The effect's green with the glass's red blended over it: redder and darker than the unobstructed effect. */
    static boolean tinted(int rgb, int open) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF;
        int openR = (open >> 16) & 0xFF, openG = (open >> 8) & 0xFF;
        return r > openR + 20 && g < openG - 40 && g > r;
    }

    static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
