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
import com.lowdragmc.photon.client.gameobject.emitter.data.material.SoftParticles;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.SpriteMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonSceneCapture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Soft particles on {@link TextureMaterial} and {@link SpriteMaterial}. Three things nothing else answers.
 *
 * <p><b>The GLSL still compiles.</b> {@code photon:soft_particle.glsl} is imported by all three particle
 * fragment shaders, and the fade is behind {@code PHOTON_SOFT} — so the soft half of every stage is GLSL
 * that a normal session never compiles. 26.1 defers pipeline compilation to the first draw and reports a
 * failure by logging and handing back an invalid pipeline, so a broken variant would otherwise surface
 * in-game, on whichever emitter happened to enable the fade, and nowhere else.
 *
 * <p><b>Disabled costs no scene capture.</b> The point of the feature being optional: the scene depth copy
 * is a full-screen blit, and it is demand-driven — the drain takes one only when a job in the frame
 * declared a {@code SamplerSceneDepth} binding. Reading the code is not proof; a material that declared
 * the sampler unconditionally would be invisible otherwise, so this asserts
 * {@link PhotonSceneCapture#depthCaptures()} across frames.
 *
 * <p><b>Enabled changes the picture.</b> A quad through the ground, captured both ways.
 *
 * <p>⚠️ 1.21's version also asserted WHICH {@code #define} variant the pass drew, by overriding
 * {@code getShader(MaterialContext)} on the material. 26.1 has no such seam: a material returns a
 * RenderType and the CPU-vs-instanced choice is made later, inside {@code Emitter.bakeInstancedGroup},
 * where no material subclass can see it. The instanced round is therefore covered by running it at all
 * (a fallback to the CPU path would still have to produce the fade) rather than by naming the variant.
 */
@LDLRegisterClient(name = "soft_particle", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SoftParticleScenario implements UIScenario {

    /** Three blocks across, centred a block up, so the lower half runs into the ground. */
    private static final double SIZE = 3;
    /** Deliberately generous: a wide band of the quad fades, so the diff is unambiguous. */
    private static final float FADE_DISTANCE = 4f;
    /** Floor for "the quad is actually on screen" — without it every diff below could be measuring sky. */
    private static final int MIN_DRAWN_PIXELS = 5_000;

    /** The three fragment stages that import the include — plain, pixel-art and sprite. */
    private static final List<Identifier> FRAGMENT_STAGES = List.of(
            Photon.id("core/hdr_particle"),
            Photon.id("core/pixel_hdr_particle"),
            Photon.id("core/sprite_hdr_particle"));

    @Override
    public void define(ScenarioBuilder s) {
        s.step("every particle pipeline compiles with the fade compiled in", ctx -> {
            // the soft key, on the CPU vertex layout and on every instanced one. Unlike 1.21 this also
            // covers the geometry families a soft material can legitimately be put on but rarely is.
            var soft = PhotonPipelines.ParticlePipelineKey.DEFAULT.withSoftParticles(true);
            for (var fragment : FRAGMENT_STAGES) {
                var stage = fragment.getPath();
                check(ctx, stage + " / CPU vertex layout",
                        PhotonPipelines.hdrParticle(fragment, soft));
                for (var variant : PhotonPipelines.InstancedVariant.values()) {
                    check(ctx, stage + " / " + String.join("+", variant.defines),
                            PhotonPipelines.instancedHdrParticle(variant, fragment, soft));
                }
            }
        })

        .step("look slightly down at the ground ahead", SoftParticleScenario::aimCamera)
        .frames(4)
        .screenshot("empty");

        // Both geometry paths, then the other material that carries the toggle — SpriteMaterial has its
        // own fragment stage and its own PhotonMaterial values.
        for (Round round : List.of(
                new Round("cpu", false, false),
                new Round("inst", true, false),
                new Round("sprite", false, true))) {
            String tag = round.tag();
            s.step("a quad through the ground, soft OFF (" + tag + ")",
                            ctx -> startQuad(ctx, round))
                    .frames(8)
                    .screenshot("off_" + tag)

                    .step("soft OFF took no depth copy (" + tag + ")", ctx -> {
                        int before = ctx.<Integer>get("capturesAtStart");
                        int now = PhotonSceneCapture.depthCaptures();
                        ctx.check("no full-screen depth copy was taken (" + tag + ")", now == before,
                                before, now);
                    })

                    .step("turn soft particles on (" + tag + ")", ctx -> {
                        ctx.put("capturesBeforeOn", PhotonSceneCapture.depthCaptures());
                        var soft = ctx.<SoftParticles>get("soft");
                        ctx.require("the material is still around", soft != null);
                        soft.setEnable(true);
                        soft.distance = FADE_DISTANCE;
                    })
                    .frames(8)
                    .screenshot("on_" + tag)

                    .step("soft ON does take one (" + tag + ")", ctx -> {
                        int before = ctx.<Integer>get("capturesBeforeOn");
                        int now = PhotonSceneCapture.depthCaptures();
                        ctx.check("the depth copy happened once it was wanted (" + tag + ")", now > before,
                                "> " + before, now);
                    })

                    .step("the fade is visible in the picture (" + tag + ")",
                            ctx -> compareCaptures(ctx, tag))

                    .step("stop the effect (" + tag + ")", SoftParticleScenario::stopQuad);
        }

        s.teardown("stop any effect", SoftParticleScenario::stopQuad);
    }

    /** 26.1 logs the driver's compile/link error and hands back an INVALID pipeline rather than throwing,
     *  so both outcomes have to be treated as a failure here. */
    private static void check(TestContext ctx, String name, RenderPipeline pipeline) {
        Throwable failure = null;
        var valid = false;
        try {
            valid = RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
        } catch (Throwable e) {
            failure = e;
        }
        ctx.check("%s compiles".formatted(name), failure == null && valid, "compiled",
                failure != null ? String.valueOf(failure.getMessage())
                        : "the driver rejected it (the GLSL error is in the log)");
    }

    /**
     * ⚠️ The floor first. Both captures being identical would also "pass" a naive assertion that they
     * differ by less than everything — and two black frames are identical. So: prove the quad is drawn at
     * all against the empty scene, THEN prove the fade changed it.
     */
    private static void compareCaptures(TestContext ctx, String tag) {
        var empty = ScreenshotCompare.load(ctx, "empty");
        var off = ScreenshotCompare.load(ctx, "off_" + tag);
        var on = ScreenshotCompare.load(ctx, "on_" + tag);
        if (empty == null || off == null || on == null) {
            ctx.check("all three captures were written (" + tag + ")", false, "three images",
                    "missing: " + Stream.of(empty == null ? "empty" : null, off == null ? "off" : null,
                            on == null ? "on" : null).filter(Objects::nonNull).toList());
            return;
        }
        var region = sceneRegion(ctx);
        var drawn = off.diff(empty, 24, region);
        ctx.check("the quad is actually on screen (" + tag + ")", drawn.count() > MIN_DRAWN_PIXELS,
                "> " + MIN_DRAWN_PIXELS + " px", drawn.count() + " px at " + drawn.box());
        if (drawn.count() <= MIN_DRAWN_PIXELS) {
            return;
        }
        var faded = on.diff(off, 24, region);
        // A tenth of the quad is a low bar on purpose — how much of it sits within FADE_DISTANCE of the
        // ground depends on where the player spawned, and this is asserting "the fade does something",
        // not a pixel-exact look.
        ctx.check("turning soft particles on changed the render (" + tag + ")",
                faded.count() > drawn.count() / 10,
                "> " + (drawn.count() / 10) + " px", faded.count() + " px at " + faded.box());
    }

    /** The middle of the window: away from the hotbar, the chat line and any debug overlay. */
    private static ScreenshotCompare.Region sceneRegion(TestContext ctx) {
        var window = ctx.mc().getWindow();
        int w = window.getWidth();
        int h = window.getHeight();
        return new ScreenshotCompare.Region(w / 5, h / 6, w * 4 / 5, h * 2 / 3);
    }

    private static void aimCamera(TestContext ctx) {
        var player = ctx.mc().player;
        ctx.require("the client player exists", player != null);
        // down a little, so the ground plane crosses the middle of the view and the quad cuts into it
        player.setXRot(15f);
        player.setYRot(player.getYRot());
    }

    /** One big static billboard, straddling the ground a few blocks ahead. */
    private static void startQuad(TestContext ctx, Round round) {
        stopQuad(ctx);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setMaxParticles(1);
        // a point emitter, so the two captures cannot differ by where the particle landed
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);

        SoftParticles soft;
        MaterialSetting materialSetting;
        if (round.sprite()) {
            var sprite = new SpriteMaterial();
            sprite.spriteLocation = pickSpriteSet(ctx);
            soft = sprite.getSoftParticles();
            materialSetting = new MaterialSetting(sprite);
        } else {
            // a solid texture, not the default soft circle: the fade has to be the only thing softening
            // the edge, or the diff is measuring the sprite's own alpha ramp
            var textured = new TextureMaterial(Identifier.parse("textures/block/white_concrete.png"));
            soft = textured.getSoftParticles();
            materialSetting = new MaterialSetting(textured);
        }
        config.renderer.setUseGPUInstance(round.instanced());
        config.renderer.getMaterials().add(materialSetting);

        var fx = new FX();
        fx.getFxData().objects().add(emitter);

        var player = ctx.mc().player;
        ctx.require("the client player exists", player != null);
        var pos = player.blockPosition().relative(player.getDirection(), 6);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0, 1, 0);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("quadExecutor", executor);
        ctx.put("soft", soft);
        // ⚠️ read AFTER start(): building the effect must not itself take a copy either
        ctx.put("capturesAtStart", PhotonSceneCapture.depthCaptures());
        ctx.check("the effect started", executor.getRuntime() != null, "a runtime", "null");
        ctx.check("soft particles are off to begin with", !soft.isEnable(), "off", "on");
    }

    /** Any registered particle sprite set; prefer flame, whose core is solid enough to read in a diff. */
    private static Identifier pickSpriteSet(TestContext ctx) {
        var sets = ctx.mc().particleEngine.resourceManager.spriteSets.keySet();
        ctx.require("the particle engine has sprite sets registered", !sets.isEmpty());
        var flame = Identifier.withDefaultNamespace("flame");
        // the lowest, not "the first one": HashMap order would make the capture differ run to run
        return sets.contains(flame) ? flame
                : sets.stream().min(Identifier::compareTo).orElseThrow();
    }

    private static void stopQuad(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("quadExecutor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("quadExecutor");
    }

    /** Which round to run: a tag for the captures, the geometry path, and which material carries it. */
    private record Round(String tag, boolean instanced, boolean sprite) {
    }
}
