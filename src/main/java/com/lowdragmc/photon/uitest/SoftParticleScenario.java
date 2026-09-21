package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderInstanceMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.SoftParticles;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.SpriteMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Soft particles on {@link TextureMaterial} and {@link SpriteMaterial}. Three things nothing else answers.
 *
 * <p><b>The GLSL still compiles.</b> {@code photon:soft_particle.glsl} is imported by all three particle
 * fragment shaders, which {@code PhotonShaders.registerShaders} builds at launch — a syntax error there
 * does not fail a build, it hangs the client while it starts. The per-variant compile below covers the
 * {@code #define}d ones the launch never builds.
 *
 * <p><b>Disabled costs no scene capture.</b> The point of the feature being optional: the scene depth copy
 * in {@code RenderPassPipeline.getSceneSampler()} is a full-screen blit, and it is pull-based — nothing
 * takes one unless a material asks for the samplers. Reading the code is not proof; a stray unconditional
 * {@code getSceneSamplers()} call would be invisible otherwise, so this asserts the counter across frames.
 *
 * <p><b>Enabled changes the picture.</b> A quad through the ground, captured both ways.
 */
@LDLRegisterClient(name = "soft_particle", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SoftParticleScenario implements UIScenario {

    /** Every render-path variant — each one compiles the fragment shader afresh with its own defines. */
    private static final Map<String, MaterialContext> VARIANTS = Map.of(
            "PARTICLE_INSTANCE", MaterialContext.PARTICLE_INSTANCE,
            "PARTICLE_MODEL_INSTANCE", MaterialContext.PARTICLE_MODEL_INSTANCE,
            "PARTICLE_MODEL_INSTANCE+PHOTON_TANGENT", MaterialContext.PARTICLE_MODEL_INSTANCE_TANGENT,
            "PARTICLE_MODEL_INSTANCE+PHOTON_VAT", MaterialContext.PARTICLE_MODEL_INSTANCE_VAT,
            "TRAIL_INSTANCE", MaterialContext.TRAIL_INSTANCE,
            "ARA_TRAIL_INSTANCE", MaterialContext.ARA_TRAIL_INSTANCE,
            "BEAM_INSTANCE", MaterialContext.BEAM_INSTANCE);

    /** Three blocks across, centred a block up, so the lower half runs into the ground. */
    private static final double SIZE = 3;
    /** Deliberately generous: a wide band of the quad fades, so the diff is unambiguous. */
    private static final float FADE_DISTANCE = 4f;
    /** Floor for "the quad is actually on screen" — without it every diff below could be measuring sky. */
    private static final int MIN_DRAWN_PIXELS = 5_000;
    /**
     * Which {@link MaterialContext} the pass actually drew with this round.
     *
     * <p>⚠️ Without this the rounds are indistinguishable: a static billboard renders pixel-identically on
     * either geometry path (which is the point), so an instanced round that silently fell back to the CPU
     * path would produce exactly the same numbers and read as coverage it does not have.
     */
    private static final Set<String> DREW_VARIANTS = ConcurrentHashMap.newKeySet();

    @Override
    public void define(ScenarioBuilder s) {
        s.step("every particle program compiles with the soft-particle include", ctx -> {
            // All three fragment shaders import it. The plain variants are built at launch; these are the
            // #defined ones, which nothing else in the build ever compiles.
            var texture = new TextureMaterial();
            var pixelArt = new TextureMaterial();
            pixelArt.getPixelArt().setEnable(true);
            var sprite = new SpriteMaterial();
            Map<String, ShaderInstanceMaterial> shaders = Map.of(
                    "hdr_particle", texture,
                    "pixel_hdr_particle", pixelArt,
                    "sprite_hdr_particle", sprite);
            shaders.forEach((shaderName, material) -> VARIANTS.forEach((name, context) -> {
                Throwable failure = null;
                Object shader = null;
                try {
                    shader = material.getShader(context);
                } catch (Throwable e) {
                    failure = e;
                }
                ctx.check("%s / %s compiles".formatted(shaderName, name), failure == null && shader != null,
                        "compiled", failure == null ? "compiled" : String.valueOf(failure.getMessage()));
            }));
        })

        .step("look slightly down at the ground ahead", SoftParticleScenario::aimCamera)
        .frames(4)
        .screenshot("empty");

        // Both geometry paths (the CPU one draws through the launch-registered program, the instanced one
        // through a #define variant that only this scenario ever builds), then the other material that
        // carries the toggle — SpriteMaterial has its own fragment shader and its own uniform binding.
        for (Round round : List.of(
                new Round("cpu", false, false),
                new Round("inst", true, false),
                new Round("sprite", false, true))) {
            String tag = round.tag();
            s.step("a quad through the ground, soft OFF (" + tag + ")",
                            ctx -> startQuad(ctx, round))
                    .frames(8)
                    .screenshot("off_" + tag)

                    .step("soft OFF took no scene copy (" + tag + ")", ctx -> {
                        int before = ctx.<Integer>get("copiesAtStart");
                        int now = RenderPassPipeline.sceneSamplerCopies();
                        ctx.check("no full-screen scene copy was taken (" + tag + ")", now == before,
                                before, now);
                    })

                    .step("turn soft particles on (" + tag + ")", ctx -> {
                        ctx.put("copiesBeforeOn", RenderPassPipeline.sceneSamplerCopies());
                        var soft = ctx.<SoftParticles>get("soft");
                        ctx.require("the material is still around", soft != null);
                        soft.setEnable(true);
                        soft.distance = FADE_DISTANCE;
                    })
                    .frames(8)
                    .screenshot("on_" + tag)

                    .step("soft ON does take one (" + tag + ")", ctx -> {
                        int before = ctx.<Integer>get("copiesBeforeOn");
                        int now = RenderPassPipeline.sceneSamplerCopies();
                        ctx.check("the scene copy happened once it was wanted (" + tag + ")", now > before,
                                "> " + before, now);
                    })

                    .step("it drew down the path this round is about (" + tag + ")", ctx -> {
                        var wanted = Set.of(round.instanced() ? "PARTICLE_INSTANCE" : "");
                        // exactly, not contains: "the instanced round also drew the plain variant" is the
                        // fallback this whole check exists to catch
                        ctx.check("the pass drew the %s variant and only that one".formatted(
                                        describe(wanted)),
                                DREW_VARIANTS.equals(wanted),
                                describe(wanted), describe(DREW_VARIANTS));
                    })

                    .step("the fade is visible in the picture (" + tag + ")",
                            ctx -> compareCaptures(ctx, tag))

                    .step("stop the effect (" + tag + ")", SoftParticleScenario::stopQuad);
        }

        s.teardown("stop any effect", SoftParticleScenario::stopQuad);
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
        DREW_VARIANTS.clear();
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

        ShaderInstanceMaterial material;
        SoftParticles soft;
        if (round.sprite()) {
            var sprite = new RecordingSprite();
            sprite.spriteLocation = pickSpriteSet(ctx);
            material = sprite;
            soft = sprite.getSoftParticles();
        } else {
            // a solid texture, not the default soft circle: the fade has to be the only thing softening
            // the edge, or the diff is measuring the sprite's own alpha ramp
            var textured = new RecordingTexture(
                    ResourceLocation.parse("textures/block/white_concrete.png"));
            material = textured;
            soft = textured.getSoftParticles();
        }
        config.renderer.setUseGPUInstance(round.instanced());
        config.renderer.getMaterials().add(new MaterialSetting(material));

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
        ctx.put("copiesAtStart", RenderPassPipeline.sceneSamplerCopies());
        ctx.check("the effect started", executor.getRuntime() != null, "a runtime", "null");
        ctx.check("soft particles are off to begin with", !soft.isEnable(), "off", "on");
    }

    /** Any registered particle sprite set; prefer flame, whose core is solid enough to read in a diff. */
    private static ResourceLocation pickSpriteSet(TestContext ctx) {
        var sets = ctx.mc().particleEngine.spriteSets.keySet();
        ctx.require("the particle engine has sprite sets registered", !sets.isEmpty());
        var flame = ResourceLocation.withDefaultNamespace("flame");
        // the lowest, not "the first one": HashMap order would make the capture differ run to run
        return sets.contains(flame) ? flame
                : sets.stream().min(ResourceLocation::compareTo).orElseThrow();
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

    /** ⚠️ The plain variant's key is the EMPTY STRING, and {@code Set.of("")} prints as {@code []} —
     *  exactly like the empty set a never-drawn material would report. Name it instead. */
    private static String describe(Set<String> variants) {
        return variants.isEmpty() ? "(nothing drawn)"
                : variants.stream().map(v -> v.isEmpty() ? "(no defines)" : v).sorted().toList().toString();
    }

    private static final class RecordingTexture extends TextureMaterial {
        private RecordingTexture(ResourceLocation texture) {
            super(texture);
        }

        @Override
        public ShaderInstance getShader(MaterialContext context) {
            DREW_VARIANTS.add(context.getVariantKey());
            return super.getShader(context);
        }
    }

    private static final class RecordingSprite extends SpriteMaterial {
        @Override
        public ShaderInstance getShader(MaterialContext context) {
            DREW_VARIANTS.add(context.getVariantKey());
            return super.getShader(context);
        }
    }
}
