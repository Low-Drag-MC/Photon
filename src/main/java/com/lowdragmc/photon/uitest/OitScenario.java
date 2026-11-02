package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.KilaGraphExport;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.lowdragmc.photon.client.render.PhotonOit;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Photon's translucent effects under improved transparency ({@code PhotonOit}), one material path at a time, as a
 * green quad behind red stained glass ({@link FrameSeamsScenario}'s scene): each goes through the OIT passes, is tinted
 * by the glass, and blooms when it is HDR. Effects that cannot or may not join stay out.
 */
@LDLRegisterClient(name = "photon_oit", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class OitScenario implements UIScenario {

    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final Map<String, Integer> DRAWS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> COMPOSITED = new ConcurrentHashMap<>();
    private static final File GRAPH = new File(LDLib2.getAssetsDir(),
            "ldlib2/resources/global/uitest_photon_oit" + ShaderGraphResource.INSTANCE.getFileExtension());

    private enum Blend {
        DEFAULT,
        /** (SRC_ALPHA, ONE). */
        ADDITIVE,
        /** (ONE, ONE_MINUS_SRC_ALPHA). */
        PREMULTIPLIED
    }

    /** {@code joins}: drawn in the OIT passes; {@code tinted}: alpha-blended, so the glass's tint is checked. */
    private record Round(String tag, Supplier<IMaterial> material, boolean instanced, Blend blend,
                         boolean optOut, boolean joins, boolean tinted, boolean bloom) {
        static Round of(String tag, Supplier<IMaterial> material, boolean joins, boolean tinted, boolean bloom) {
            return new Round(tag, material, false, Blend.DEFAULT, false, joins, tinted, bloom);
        }
    }

    private static final List<Round> ROUNDS = List.of(
            Round.of("texture", OitScenario::texture, true, true, true),
            Round.of("texture_no_bloom", OitScenario::texture, true, true, false),
            new Round("additive", OitScenario::texture, false, Blend.ADDITIVE, false, true, false, true),
            Round.of("hdr", OitScenario::hdr, true, false, true),
            Round.of("hdr_no_bloom", OitScenario::hdr, true, false, false),
            Round.of("soft", OitScenario::soft, true, true, true),
            Round.of("lit", OitScenario::lit, true, true, true),
            new Round("instanced", OitScenario::texture, true, Blend.DEFAULT, false, true, true, true),
            Round.of("kila", OitScenario::kilaGreen, true, true, true),
            Round.of("graph", OitScenario::graph, true, true, true),
            new Round("graph_premultiplied", OitScenario::graph, false, Blend.PREMULTIPLIED, false, true, true, true),
            Round.of("custom_shader", () -> new CustomShaderMaterial(Photon.id("circle")), true, false, true),
            Round.of("screen_distortion", OitScenario::distortion, false, false, true),
            new Round("renderer_oit_off", OitScenario::texture, false, Blend.DEFAULT, true, false, false, true));

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render").requiresWorld(true).scenarioTimeoutMs(300_000);
    }

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
                    FrameSeamsScenario.build(sc.level(), origin);
                    TestClock.set(sc.level(), 6000);
                    var player = sc.player();
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo(sc.level(), origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + 0.5,
                            Set.<Relative>of(), 0f, 0f, true);
                })
                .serverTicks(10)
                .step("improved transparency on, Photon's OIT on", ctx -> {
                    LightTestConfig.set(PhotonConfig.INSTANCE.orderIndependentTransparency, true);
                    LightTestConfig.set(ctx.mc().options.improvedTransparency(), true);
                })
                .ticks(5)
                .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(10)
                .step("the level is drawn with order-independent transparency",
                        ctx -> ctx.require("improved transparency is on",
                                ctx.mc().gameRenderer.useImprovedTransparency()))
                .step("write the graph", OitScenario::writeGraph)
                .screenshot("empty");
        for (var round : ROUNDS) {
            s.step("effect: " + round.tag(), ctx -> {
                        LightTestConfig.set(PhotonConfig.INSTANCE.enableBloom, round.bloom());
                        start(ctx, round);
                    })
                    .frames(20)
                    .screenshot(round.tag())
                    .step("stop " + round.tag(), ctx -> {
                        DRAWS.put(round.tag(), PhotonOit.drawsLastFrame());
                        COMPOSITED.put(round.tag(), PhotonOit.compositedLastFrame());
                        stop(ctx);
                    });
        }
        s.step("every effect resolves with the glass", OitScenario::check);
        s.teardown("stop the effect", ctx -> {
            stop(ctx);
            DRAWS.clear();
            COMPOSITED.clear();
            LightTestConfig.restore();
            //noinspection ResultOfMethodCallIgnored
            GRAPH.delete();
            ShaderGraphRuntime.invalidate(new FilePath(GRAPH));
        });
        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
    }

    private static TextureMaterial texture() {
        return new TextureMaterial(Photon.id("textures/particle/white.png"));
    }

    private static TextureMaterial hdr() {
        var material = texture();
        // green 4, far over the bloom threshold
        material.setHdr(new HDRColor(0f, 3f, 0f, 1f, 1f));
        return material;
    }

    private static TextureMaterial soft() {
        var material = texture();
        material.getSoftParticles().setEnable(true);
        material.getSoftParticles().distance = 1f;
        return material;
    }

    private static TextureMaterial lit() {
        var material = texture();
        material.getLitParticles().setEnable(true);
        material.getLitParticles().intensity = 1f;
        return material;
    }

    private static KilaMaterial kilaGreen() {
        var material = new KilaMaterial();
        material.main.texture.texture = KilaTextures.WHITE;
        material.color = new HDRColor(0f, 1f, 0f, 1f, 1f);
        return material;
    }

    private static IMaterial graph() {
        return new ShaderGraphMaterial(new FilePath(GRAPH));
    }

    private static KilaMaterial distortion() {
        var material = kilaGreen();
        material.screenDistortion.setEnable(true);
        material.screenDistortion.texture.noise(KilaTexture.Noise.GRADIENT, 3);
        material.screenDistortion.strength.value = 0.2f;
        return material;
    }

    private static void writeGraph(TestContext ctx) {
        try {
            Files.createDirectories(GRAPH.getParentFile().toPath());
            var tag = new CompoundTag();
            tag.put("data", ShaderGraphResource.INSTANCE.serializeGraph(KilaGraphExport.export(kilaGreen()).graph()));
            tag.putString("type", ShaderGraphResource.INSTANCE.getName());
            NbtIo.write(tag, GRAPH.toPath());
            ShaderGraphRuntime.invalidate(new FilePath(GRAPH));
        } catch (IOException e) {
            ctx.require("the graph was written: " + e, false);
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
        var setting = new MaterialSetting(round.material().get());
        switch (round.blend()) {
            case ADDITIVE -> {
                setting.getBlendMode().setSrcColorFactor(BlendFactor.SRC_ALPHA);
                setting.getBlendMode().setDstColorFactor(BlendFactor.ONE);
            }
            case PREMULTIPLIED -> {
                setting.getBlendMode().setSrcColorFactor(BlendFactor.ONE);
                setting.getBlendMode().setDstColorFactor(BlendFactor.ONE_MINUS_SRC_ALPHA);
            }
            case DEFAULT -> {
            }
        }
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(setting);
        config.renderer.setLayer(RendererSetting.Layer.Translucent);
        // without OIT, LATE draws it over the glass
        config.renderer.setCompositeMode(FXCompositeMode.LATE);
        config.renderer.setUseGPUInstance(round.instanced());
        if (round.optOut()) {
            config.renderer.setOrderIndependentTransparency(RendererSetting.OitMode.OFF);
        }

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
        // on the effect's plane: clear of the glass, and behind it
        var open = Vec3.atLowerCornerOf(origin).add(-1.0, 2.6, 9.5);
        var throughGlass = Vec3.atLowerCornerOf(origin).add(3.5, 2.6, 9.5);
        var empty = ScreenshotCompare.load(ctx, "empty");
        ctx.require("the empty scene was captured", empty != null);
        var emptyOpen = FrameSeamsScenario.sample(ctx, empty, open);
        for (var round : ROUNDS) {
            var tag = round.tag();
            var image = ScreenshotCompare.load(ctx, tag);
            if (image == null) {
                ctx.check(tag + " was captured", false, "an image", "missing");
                continue;
            }
            var draws = DRAWS.getOrDefault(tag, -1);
            if (round.joins()) {
                ctx.check(tag + ": it went through the OIT passes", draws > 0, "> 0", draws);
            } else {
                ctx.check(tag + ": it stayed out of the OIT passes", draws == 0, "0", draws);
                if (round.optOut()) {
                    var glassRgb = FrameSeamsScenario.sample(ctx, image, throughGlass);
                    ctx.check(tag + ": LATE draws it over the glass", FrameSeamsScenario.green(glassRgb), "green",
                            FrameSeamsScenario.hex(glassRgb));
                }
                continue;
            }
            var openRgb = FrameSeamsScenario.sample(ctx, image, open);
            var glassRgb = FrameSeamsScenario.sample(ctx, image, throughGlass);
            ctx.check(tag + ": the effect is drawn", FrameSeamsScenario.greenish(openRgb), "green",
                    FrameSeamsScenario.hex(openRgb) + " (empty " + FrameSeamsScenario.hex(emptyOpen) + ")");
            if (round.tinted()) {
                ctx.check(tag + ": the glass in front of it tints it", FrameSeamsScenario.tinted(glassRgb, openRgb),
                        "redder and darker than " + FrameSeamsScenario.hex(openRgb), FrameSeamsScenario.hex(glassRgb));
            }
            var composited = COMPOSITED.getOrDefault(tag, false);
            ctx.check(tag + (round.bloom() ? ": Photon resolved the sum, for its bloom" : ": vanilla resolved the sum"),
                    composited == round.bloom(), round.bloom(), composited);
        }
        var bloomed = ScreenshotCompare.load(ctx, "hdr");
        var plain = ScreenshotCompare.load(ctx, "hdr_no_bloom");
        if (bloomed != null && plain != null) {
            var halo = halo(bloomed, plain);
            ctx.check("hdr: its bloom spreads past the quad's edge", halo > 0.002, "> 0.2% of the frame",
                    String.format("%.3f%%", halo * 100));
        }
        var ldr = ScreenshotCompare.load(ctx, "texture");
        var ldrPlain = ScreenshotCompare.load(ctx, "texture_no_bloom");
        if (ldr != null && ldrPlain != null) {
            var halo = halo(ldr, ldrPlain);
            ctx.check("texture: an effect below the bloom threshold does not glow", halo < 0.0005,
                    "< 0.05% of the frame",
                    String.format("%.3f%%", halo * 100));
        }
    }

    /** The share of the frame outside the quad that only the bloomed capture shows greener: the halo. */
    private static double halo(ScreenshotCompare bloomed, ScreenshotCompare plain) {
        var width = Math.min(bloomed.width(), plain.width());
        var height = Math.min(bloomed.height(), plain.height());
        long glowing = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                var without = plain.rgb(x, y);
                if (FrameSeamsScenario.greenish(without)) continue;
                var with = bloomed.rgb(x, y);
                if (((with >> 8) & 0xFF) > ((without >> 8) & 0xFF) + 12) glowing++;
            }
        }
        return (double) glowing / Math.max(1L, (long) width * height);
    }
}
