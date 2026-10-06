package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.math.HDRColor;
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
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDepth;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDissolve;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMainTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMatcap;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaScreenDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.FXCompositeMode;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import net.minecraft.world.level.GameRules;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL20;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * KilaMaterial's modules, drawn and compared by screenshot: a static quad over (or sunk into) the ground,
 * looked down on so what is behind it is textured ground rather than flat sky.
 */
@LDLRegisterClient(name = "kila_effects", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaEffectsScenario implements UIScenario {
    private static final double SIZE = 1.4;
    private static final int MIN_DRAWN_PIXELS = 5_000;

    /** {@code height}: where the quad's centre sits above the ground; below its half size it is cut by the ground. */
    private record Round(String tag, Supplier<IMaterial> material, double height, int count, boolean sphere,
                         boolean spread) {
        Round(String tag, Supplier<IMaterial> material) {
            this(tag, material, 0.9, 1, false, false);
        }

        Round(String tag, Supplier<IMaterial> material, double height) {
            this(tag, material, height, 1, false, false);
        }

        Round(String tag, Supplier<IMaterial> material, double height, int count) {
            this(tag, material, height, count, false, true);
        }

        Round(String tag, Supplier<IMaterial> material, double height, int count, boolean sphere) {
            this(tag, material, height, count, sphere, false);
        }
    }

    private static final int ROW_PARTICLES = 16;
    private static final ResourceLocation ROWS = Photon.id("kila_test/rows");
    // one colour per row of a 1 x 4 sheet, none of them the grass's or the sky's
    private static final int[] ROW_COLORS = {0xFFFF0000, 0xFF0000FF, 0xFFFF00FF, 0xFFFFFF00};

    private static final double SUNK = 0.45;

    private static final TextureMaterial SMOKE = smoke();
    // the sky changes colour with the time of day, and every picture is compared with the first
    private static final AtomicReference<Long> DAY_TIME = new AtomicReference<>();
    private static final AtomicReference<Boolean> DAYLIGHT = new AtomicReference<>(true);

    private static final List<Round> ROUNDS = List.of(
            new Round("haze_still", () -> haze(0)),
            new Round("haze_moving", () -> haze(0.06f)),
            new Round("convert_texture", () -> SMOKE),
            new Round("convert_kila", () -> KilaMaterial.fromTexture(SMOKE)),
            new Round("noise_whole", () -> noiseDissolve(0)),
            new Round("noise_half", () -> noiseDissolve(0.5f)),
            new Round("dispersion", KilaEffectsScenario::dispersion),
            new Round("debug_alpha", KilaEffectsScenario::debugAlpha),
            new Round("world_uv", KilaEffectsScenario::worldUv),
            new Round("uv_plain", () -> gradient(false)),
            new Round("uv_twirled", () -> gradient(true)),
            new Round("uv_rippled", KilaEffectsScenario::rippled),
            new Round("flow", KilaEffectsScenario::flow),
            new Round("contact_off", () -> contact(0, contact -> {
            }), SUNK),
            new Round("contact_on", () -> contact(0.3f, contact -> {
            }), SUNK),
            new Round("contact_hard", () -> contact(0.3f, contact -> contact.contactHardness = 0.95f), SUNK),
            new Round("contact_surface", () -> contact(0.3f, contact -> contact.measure = KilaDepth.Measure.SURFACE), SUNK),
            new Round("contact_broken", () -> contact(0.3f, contact -> contact.contactBreakup = 1), SUNK),
            new Round("contact_pulse", () -> contact(0.6f, contact -> {
                contact.contactPulses = 3;
                contact.contactPulseSpeed = 3;
            }), SUNK),
            new Round("contact_shape", () -> roundContact(true), SUNK),
            new Round("contact_fill", () -> roundContact(false), SUNK),
            new Round("offset_none", () -> offset(0), SUNK),
            new Round("offset_on", () -> offset(1), SUNK),
            new Round("dissolve_flat", () -> burning(false)),
            new Round("dissolve_ramp", () -> burning(true)),
            new Round("haze_masked", () -> sunkHaze(true), SUNK),
            new Round("haze_unmasked", () -> sunkHaze(false), SUNK),
            new Round("rows_fixed", () -> rows(false), 0.9, ROW_PARTICLES),
            new Round("rows_random", () -> rows(true), 0.9, ROW_PARTICLES),
            new Round("shade_left", () -> shaded(true, false)),
            new Round("shade_right", () -> shaded(false, false)),
            new Round("six_left", () -> shaded(true, true)),
            new Round("six_right", () -> shaded(false, true)),
            new Round("bump_on", KilaEffectsScenario::bumped),
            new Round("matcap", KilaEffectsScenario::matcapped),
            new Round("plain_white", () -> plainWhite()),
            new Round("volume", KilaEffectsScenario::volume),
            new Round("layer2_on", KilaEffectsScenario::twoLayers),
            new Round("motion_off", () -> motion(false)),
            new Round("motion_on", () -> motion(true)),
            new Round("fringe", KilaEffectsScenario::fringe),
            new Round("glass", KilaEffectsScenario::glass),
            new Round("orb_unsorted", () -> orb(false), 0.9, 1, true),
            new Round("orb_sorted", () -> orb(true), 0.9, 1, true),
            // two hazes on the same spot: stacked they add up, unstacked the second only sees the scene
            new Round("unstacked_single", () -> stacked(0.06f, false)),
            new Round("stack_single", () -> stacked(0.06f, true)),
            new Round("stack_double", () -> stacked(0.03f, true), 0.9, 2, false, false),
            new Round("unstacked_double", () -> stacked(0.03f, false), 0.9, 2, false, false),
            // the same in place, where the build bends its own working copy
            new Round("unstacked_single_inline", () -> stacked(0.06f, false)),
            new Round("stack_double_inline", () -> stacked(0.03f, true), 0.9, 2, false, false));

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
                // the sky and the light map follow the client's clock, a tick or more behind the server's
                .waitUntil("noon reached the client", ctx -> ctx.mc().level != null
                        && Math.abs(ctx.mc().level.getDayTime() % 24000 - 6000) < 40)
                .ticks(10)
                .step("look down at the ground ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    // the previous rotation too, or the first frames interpolate towards it and the base is off
                    player.setXRot(20);
                    player.xRotO = 20;
                    player.yRotO = player.getYRot();
                })
                .frames(20)
                .screenshot("fxbase");
        for (var round : ROUNDS) {
            s.step("start " + round.tag(), ctx -> start(ctx, round))
                    .frames(10)
                    .screenshot(round.tag())
                    .step("after " + round.tag(), ctx -> after(ctx, round));
            if (round.tag().equals("flow")) {
                s.frames(10).screenshot("flow_later");
            }
            if (round.tag().equals("contact_pulse")) {
                s.frames(10).screenshot("contact_pulse_later");
            }
            s.step("stop " + round.tag(), KilaEffectsScenario::stop);
        }
        s.step("the pictures", KilaEffectsScenario::comparePictures)
                .teardown("stop any effect", KilaEffectsScenario::stop)
                .teardown("look ahead again", ctx -> ctx.requirePlayer().setXRot(0))
                .teardownServer("let the sun move again", sc -> {
                    if (DAY_TIME.get() == null) return;
                    var level = sc.level();
                    level.setDayTime(DAY_TIME.get());
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(DAYLIGHT.get(), level.getServer());
                    DAY_TIME.set(null);
                });
    }

    // ---- materials ---------------------------------------------------------------------------------

    private static KilaMaterial unlit() {
        var material = new KilaMaterial();
        material.worldLight = false;
        material.fog = false;
        return material;
    }

    /** An opaque quad showing the scene behind it: at strength 0 it is invisible. */
    private static KilaMaterial haze(float strength) {
        var material = unlit();
        material.main.texture.texture = KilaTextures.WHITE;
        material.screenDistortion.setEnable(true);
        material.screenDistortion.texture.noise(KilaTexture.Noise.GRADIENT, 3);
        material.screenDistortion.strength.value = strength;
        return material;
    }

    private static TextureMaterial smoke() {
        var material = new TextureMaterial(Photon.id("textures/particle/smoke.png"));
        material.setHdr(new HDRColor(0.35f, 0.12f, 0.02f, 1f, 1f));
        return material;
    }

    private static KilaMaterial noiseDissolve(float progress) {
        var material = unlit();
        material.main.texture.texture = KilaTextures.WHITE;
        material.color = new HDRColor(1f, 0.3f, 0.1f, 1f, 1f);
        material.dissolve.setEnable(true);
        material.dissolve.texture.noise(KilaTexture.Noise.GRADIENT, 4);
        material.dissolve.progress.value = progress;
        material.dissolve.edgeWidth.value = 0;
        material.dissolve.softness = 0.02f;
        return material;
    }

    private static KilaMaterial dispersion() {
        var material = unlit();
        material.blend = BlendMode.Preset.ADDITIVE;
        material.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        material.main.dispersion = true;
        material.main.dispersionAmount.value = 0.12f;
        return material;
    }

    private static KilaMaterial debugAlpha() {
        var material = unlit();
        material.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        material.color = new HDRColor(0.6f, 0.1f, 0.9f, 1f, 1f);
        material.debugView = KilaMaterial.DebugView.ALPHA;
        return material;
    }

    private static KilaMaterial worldUv() {
        var material = unlit();
        material.main.texture.texture = KilaTextures.NOISE_CELLS;
        material.main.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        return material;
    }

    private static KilaMaterial gradient(boolean bent) {
        var material = unlit();
        material.main.texture.texture = KilaTextures.GRADIENT_LINEAR;
        material.uvEffects.setEnable(bent);
        material.uvEffects.twirl.value = 6;
        material.uvEffects.shear.set(4, 4);
        return material;
    }

    private static KilaMaterial rippled() {
        var material = gradient(false);
        material.uvEffects.setEnable(true);
        material.uvEffects.twirl.value = 0;
        material.uvEffects.rippleAmplitude = 0.08f;
        return material;
    }

    private static KilaMaterial flow() {
        var material = unlit();
        material.main.texture.texture = KilaTextures.NOISE_CLOUD;
        material.distortion.setEnable(true);
        material.distortion.mode = KilaDistortion.Mode.FLOW_MAP;
        material.distortion.texture.texture = KilaTextures.FLOW_SWIRL;
        material.distortion.texture.scroll(0, 0);
        material.distortion.strength.value = 0.4f;
        material.distortion.flowSpeed = 0.5f;
        return material;
    }

    /** A faint white quad, glowing cyan where it meets the ground: nothing else changes the picture. */
    private static KilaMaterial contact(float width, Consumer<KilaDepth> setup) {
        var material = unlit();
        material.main.texture.texture = KilaTextures.WHITE;
        material.color = new HDRColor(1f, 1f, 1f, 0.2f, 1f);
        material.depth.setEnable(true);
        material.depth.soft = false;
        material.depth.contactWidth.value = width;
        material.depth.contactColor = new HDRColor(0.2f, 1f, 1f, 1f, 4f);
        // the faint quad would dim it: here the band alone is measured
        material.depth.contactFollowsShape = false;
        setup.accept(material.depth);
        return material;
    }

    /** A soft disc: the glow follows its shape, or fills the quad. */
    private static KilaMaterial roundContact(boolean followShape) {
        var material = contact(0.4f, contact -> contact.contactFollowsShape = followShape);
        material.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        return material;
    }

    private static KilaMaterial offset(float blocks) {
        var material = unlit();
        material.main.texture.texture = KilaTextures.WHITE;
        material.color = new HDRColor(1f, 0.2f, 0.6f, 1f, 1f);
        material.cameraOffset = blocks;
        return material;
    }

    private static KilaMaterial burning(boolean gradient) {
        var material = noiseDissolve(0.5f);
        material.dissolve.edgeWidth.value = 0.25f;
        material.dissolve.edgeColor = new HDRColor(1f, 1f, 1f, 1f, 1f);
        material.dissolve.edgeGradient = gradient;
        // painted over, so the dark end chars the edge
        if (gradient) material.dissolve.edge = KilaDissolve.Edge.REPLACE;
        return material;
    }

    /** A 1 x 4 sheet standing still on its first frame: each particle shows row 0, or a row of its own. */
    private static KilaMaterial rows(boolean random) {
        registerRows();
        var material = unlit();
        material.main.texture.texture = ROWS;
        material.main.texture.nearest = true;
        material.main.flipbook = true;
        material.main.columns = 1;
        material.main.rows = 4;
        material.main.fps = 0;
        material.main.randomRow = random;
        return material;
    }

    private static void registerRows() {
        var textures = Minecraft.getInstance().getTextureManager();
        if (textures.getTexture(ROWS, null) != null) return;
        var image = new NativeImage(4, 4, false);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) image.setPixelRGBA(x, y, FastColor.ABGR32.fromArgb32(ROW_COLORS[y]));
        }
        textures.register(ROWS, new DynamicTexture(image));
    }

    /** The camera's left in the world, for a light that comes from one side of the screen. */
    private static Vector3f cameraLeft() {
        return new Vector3f(Minecraft.getInstance().gameRenderer.getMainCamera().getLeftVector());
    }

    /** Lit from one side of the screen: a sphere normal on a white quad, or the six-way smoke puff. */
    private static KilaMaterial shaded(boolean fromLeft, boolean sixWay) {
        var material = unlit();
        material.main.texture.texture = sixWay ? KilaTextures.SMOKE6_POS : KilaTextures.WHITE;
        material.shading.setEnable(true);
        material.shading.ambient = 0.1f;
        material.shading.wrap = 0;
        var light = cameraLeft();
        if (!fromLeft) light.negate();
        material.shading.lightDirection = light;
        if (sixWay) material.shading.model = KilaShading.Model.SIX_WAY;
        return material;
    }

    private static KilaMaterial bumped() {
        var material = shaded(true, false);
        material.shading.bump = KilaShading.Bump.NORMAL_MAP;
        material.shading.bumpStrength = 2;
        return material;
    }

    private static KilaMaterial matcapped() {
        var material = plainWhite();
        material.shading.setEnable(true);
        material.shading.ambient = 1;
        material.matcap.setEnable(true);
        material.matcap.blend = KilaMatcap.Blend.REPLACE;
        return material;
    }

    private static KilaMaterial plainWhite() {
        var material = unlit();
        material.main.texture.texture = KilaTextures.WHITE;
        return material;
    }

    private static KilaMaterial volume() {
        var material = plainWhite();
        material.volume.setEnable(true);
        material.volume.density = 2;
        return material;
    }

    private static KilaMaterial twoLayers() {
        var material = plainWhite();
        material.color = new HDRColor(0.2f, 0.2f, 0.6f, 1f, 1f);
        material.layer2.setEnable(true);
        material.layer2.texture.texture = KilaTextures.NOISE_CELLS;
        return material;
    }

    /** The turning puff's sheet, held halfway between two frames. */
    private static KilaMaterial motion(boolean vectors) {
        var material = unlit();
        material.main.texture.texture = KilaTextures.SMOKE_FLIP;
        material.main.flipbook = true;
        material.main.frameBlend = true;
        material.main.frameSource = KilaMainTexture.FrameSource.DRIVER;
        material.main.frame.value = 0.5f / 16;
        material.main.motionVectors = vectors;
        material.color = new HDRColor(1f, 0.6f, 0.2f, 1f, 1f);
        return material;
    }

    private static KilaMaterial fringe() {
        var material = haze(0.06f);
        material.screenDistortion.chromatic = 0.8f;
        return material;
    }

    /** A clear ball bending the ground behind it, by the sphere normal. */
    private static KilaMaterial glass() {
        var material = haze(0.08f);
        material.shading.setEnable(true);
        material.screenDistortion.source = KilaScreenDistortion.Source.NORMAL;
        return material;
    }

    /** A see-through sphere, white in front and red behind: drawn in mesh order a back face can land on top. */
    private static KilaMaterial orb(boolean backFacesFirst) {
        var material = plainWhite();
        material.color = new HDRColor(1f, 1f, 1f, 0.5f, 1f);
        material.backColor = new HDRColor(1f, 0f, 0f, 1f, 1f);
        material.doubleSided = true;
        material.backFacesFirst = backFacesFirst;
        return material;
    }

    /** Held still, so captures taken at different moments compare. */
    private static KilaMaterial stacked(float strength, boolean stack) {
        var material = haze(strength);
        material.screenDistortion.texture.scroll(0, 0);
        material.screenDistortion.stack = stack;
        return material;
    }

    private static KilaMaterial sunkHaze(boolean keepForeground) {
        var material = haze(0.08f);
        material.screenDistortion.keepForeground = keepForeground;
        return material;
    }

    // ---- rounds ------------------------------------------------------------------------------------

    private static void start(TestContext ctx, Round round) {
        stop(ctx);
        var material = round.material().get();
        ctx.put("material", material);
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(200);
        config.setStartLifetime(NumberFunction.constant(200));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setMaxParticles(round.count());
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        if (round.sphere()) {
            config.renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
            config.renderer.setModel(new MeshData(new ObjModelSource(Photon.id("models/sphere.obj"))));
        }
        if (round.spread()) {
            config.setStartSize(new NumberFunction3(0.35, 0.35, 0.35));
            config.shape.setShape(new Box());
            config.shape.setScale(new NumberFunction3(3.5, 0, 0));
        }
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(round.count()));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(material));
        if (round.tag().endsWith("_inline")) config.renderer.setCompositeMode(FXCompositeMode.VANILLA);
        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        var player = ctx.requirePlayer();
        var pos = player.blockPosition().relative(player.getDirection(), 3);
        var executor = new BlockEffectExecutor(fx, player.level(), pos);
        executor.setOffset(0.5, round.height(), 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
        ctx.put("copiesAtStart", RenderPassPipeline.sceneSamplerCopies());
        ctx.check("the effect started (" + round.tag() + ")", executor.getRuntime() != null, "a runtime", "null");
    }

    private static void after(TestContext ctx, Round round) {
        int copies = RenderPassPipeline.sceneSamplerCopies() - ctx.<Integer>get("copiesAtStart");
        ctx.put("copies_" + round.tag(), copies);
        if (round.tag().equals("world_uv")) {
            // the program keeps the last value uploaded to it: the draw just before this step
            var material = ctx.<KilaMaterial>get("material");
            var variant = material.layout().variant(MaterialContext.NORMAL);
            ctx.require("the world-space variant compiled", variant != null);
            int program = variant.shader().getId();
            int location = GL20.glGetUniformLocation(program, "KilaCameraPos");
            ctx.require("the variant reads KilaCameraPos", location >= 0);
            var value = new float[3];
            GL20.glGetUniformfv(program, location, value);
            var camera = ctx.mc().gameRenderer.getMainCamera().getPosition();
            double off = Math.abs(value[0] - camera.x) + Math.abs(value[1] - camera.y) + Math.abs(value[2] - camera.z);
            ctx.check("a world-space slot is handed the camera position", off < 0.05,
                    "%.2f %.2f %.2f".formatted(camera.x, camera.y, camera.z),
                    "%.2f %.2f %.2f".formatted(value[0], value[1], value[2]));
        }
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    // ---- the pictures ------------------------------------------------------------------------------

    /** The middle of the window: away from the hotbar, the chat line and any debug overlay. */
    private static ScreenshotCompare.Region sceneRegion(TestContext ctx) {
        var window = ctx.mc().getWindow();
        int w = window.getWidth();
        int h = window.getHeight();
        return new ScreenshotCompare.Region(w / 6, h / 8, w * 5 / 6, h * 3 / 4);
    }

    private static void comparePictures(TestContext ctx) {
        var base = ScreenshotCompare.load(ctx, "fxbase");
        ctx.require("the base capture exists", base != null);
        var region = sceneRegion(ctx);

        // where the quad is, off the solid orange one: every round puts its quad in the same place
        var whole = ScreenshotCompare.load(ctx, "noise_whole");
        ctx.require("the solid quad capture exists", whole != null);
        var located = whole.diff(base, 60, region);
        ctx.require("the solid quad is on screen", located.changed() != null && located.count() > MIN_DRAWN_PIXELS);
        var quad = located.changed();
        var inside = new ScreenshotCompare.Region(quad.left() + 4, quad.top() + 4, quad.right() - 4, quad.bottom() - 4);
        int insideArea = (inside.right() - inside.left()) * (inside.bottom() - inside.top());

        var still = ScreenshotCompare.load(ctx, "haze_still");
        var moving = ScreenshotCompare.load(ctx, "haze_moving");
        ctx.require("the haze captures exist", still != null && moving != null);
        var stillDiff = still.diff(base, 12, inside);
        ctx.check("an undistorted haze cannot be told from no haze", stillDiff.count() < insideArea / 100,
                "< " + insideArea / 100 + " px", stillDiff.count() + " px at " + stillDiff.box());
        var movingDiff = moving.diff(base, 12, inside);
        ctx.check("a distorted haze shifts the ground behind it", movingDiff.count() > 3000,
                "> 3000 px", movingDiff.count() + " px at " + movingDiff.box());
        ctx.check("the haze took a scene copy", ctx.<Integer>get("copies_haze_moving") > 0,
                "> 0", ctx.<Integer>get("copies_haze_moving"));
        ctx.check("a plain texture material took none", ctx.<Integer>get("copies_convert_texture") == 0,
                0, ctx.<Integer>get("copies_convert_texture"));

        var texture = ScreenshotCompare.load(ctx, "convert_texture");
        var converted = ScreenshotCompare.load(ctx, "convert_kila");
        ctx.require("the conversion captures exist", texture != null && converted != null);
        var drawn = texture.diff(base, 24, region);
        ctx.check("the texture material is on screen", drawn.count() > MIN_DRAWN_PIXELS,
                "> " + MIN_DRAWN_PIXELS + " px", drawn.count() + " px at " + drawn.box());
        var parity = converted.diff(texture, 6, region);
        ctx.check("the converted material draws what the texture material drew", parity.count() < drawn.count() / 50,
                "< " + drawn.count() / 50 + " px", parity.count() + " px at " + parity.box());

        var half = ScreenshotCompare.load(ctx, "noise_half");
        ctx.require("the noise capture exists", half != null);
        int wholeCount = whole.diff(base, 24, region).count();
        int halfCount = half.diff(base, 24, region).count();
        ctx.check("the undissolved quad is on screen", wholeCount > MIN_DRAWN_PIXELS, "> " + MIN_DRAWN_PIXELS + " px",
                wholeCount + " px");
        double share = wholeCount == 0 ? 0 : (double) halfCount / wholeCount;
        ctx.check("a procedural noise at progress 0.5 dissolves part of it, not all", share > 0.15 && share < 0.85,
                "0.15 .. 0.85", "%.2f (%d of %d px)".formatted(share, halfCount, wholeCount));

        // dispersion: one side fringes red, the other blue — measured over the ground, as the sky's blue is
        // already saturated and an added blue would not show
        var dispersion = ScreenshotCompare.load(ctx, "dispersion");
        ctx.require("the dispersion capture exists", dispersion != null);
        var disc = dispersion.diff(base, 24, quad);
        ctx.require("the dispersed disc is on screen", disc.count() > MIN_DRAWN_PIXELS / 2);
        int third = (quad.right() - quad.left()) / 3;
        int groundTop = quad.bottom() - (quad.bottom() - quad.top()) * 2 / 5;
        var left = new ScreenshotCompare.Region(quad.left(), groundTop, quad.left() + third, quad.bottom());
        var right = new ScreenshotCompare.Region(quad.right() - third, groundTop, quad.right(), quad.bottom());
        double leftShift = redOverBlue(dispersion, left) - redOverBlue(base, left);
        double rightShift = redOverBlue(dispersion, right) - redOverBlue(base, right);
        ctx.check("the two sides fringe in opposite colours", leftShift * rightShift < 0
                        && Math.abs(leftShift - rightShift) > 20,
                "opposite signs, > 20 apart", "left %+.1f, right %+.1f".formatted(leftShift, rightShift));

        var debug = ScreenshotCompare.load(ctx, "debug_alpha");
        ctx.require("the debug capture exists", debug != null);
        int rgb = debug.rgb((quad.left() + quad.right()) / 2, (quad.top() + quad.bottom()) / 2);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        ctx.check("the alpha view shows the solid centre as white, not the purple colour",
                Math.min(r, Math.min(g, b)) > 200 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 16,
                "grey > 200", "%d %d %d".formatted(r, g, b));

        var world = ScreenshotCompare.load(ctx, "world_uv");
        ctx.require("the world uv capture exists", world != null);
        int worldCount = world.diff(base, 24, region).count();
        ctx.check("the world-mapped quad is on screen", worldCount > MIN_DRAWN_PIXELS, "> " + MIN_DRAWN_PIXELS + " px",
                worldCount + " px");

        var plain = ScreenshotCompare.load(ctx, "uv_plain");
        var twirled = ScreenshotCompare.load(ctx, "uv_twirled");
        ctx.require("the uv effect captures exist", plain != null && twirled != null);
        var bent = twirled.diff(plain, 24, inside);
        ctx.check("the twirl and shear bend the texture", bent.count() > insideArea / 5,
                "> " + insideArea / 5 + " px", bent.count() + " px at " + bent.box());

        var rippled = ScreenshotCompare.load(ctx, "uv_rippled");
        ctx.require("the ripple capture exists", rippled != null);
        var rings = rippled.diff(plain, 24, inside);
        ctx.check("ripples run through the texture", rings.count() > insideArea / 10, "> " + insideArea / 10 + " px",
                rings.count() + " px at " + rings.box());

        var flow = ScreenshotCompare.load(ctx, "flow");
        var flowLater = ScreenshotCompare.load(ctx, "flow_later");
        ctx.require("the flow captures exist", flow != null && flowLater != null);
        var flowed = flowLater.diff(flow, 12, region);
        ctx.check("a flow map keeps the texture moving", flowed.count() > 1500, "> 1500 px",
                flowed.count() + " px at " + flowed.box());

        contactPictures(ctx, base, region);
        rowPictures(ctx, region);
        shadingPictures(ctx, base, region, inside, insideArea);
    }

    private static double brightness(ScreenshotCompare capture, ScreenshotCompare.Region region) {
        var mean = capture.mean(region);
        return mean[0] + mean[1] + mean[2];
    }

    /** Pixels as bright as a whole spark of the turning puff: red near full, nothing the sky or grass reach. */
    private static int sparks(ScreenshotCompare capture, ScreenshotCompare.Region region) {
        int n = 0;
        for (int y = region.top(); y < region.bottom(); y++) {
            for (int x = region.left(); x < region.right(); x++) {
                if (((capture.rgb(x, y) >> 16) & 0xFF) > 235) n++;
            }
        }
        return n;
    }

    private static void shadingPictures(TestContext ctx, ScreenshotCompare base, ScreenshotCompare.Region region,
                                        ScreenshotCompare.Region inside, int insideArea) {
        int mid = (inside.left() + inside.right()) / 2;
        var leftHalf = new ScreenshotCompare.Region(inside.left(), inside.top(), mid, inside.bottom());
        var rightHalf = new ScreenshotCompare.Region(mid, inside.top(), inside.right(), inside.bottom());
        for (var kind : List.of("shade", "six")) {
            var fromLeft = capture(ctx, kind + "_left");
            var fromRight = capture(ctx, kind + "_right");
            double leftLit = brightness(fromLeft, leftHalf) - brightness(fromLeft, rightHalf);
            double rightLit = brightness(fromRight, rightHalf) - brightness(fromRight, leftHalf);
            ctx.check(kind + ": lit from the left, the left is brighter; from the right, the right",
                    leftLit > 30 && rightLit > 30, "> 30 both", "%.1f, %.1f".formatted(leftLit, rightLit));
        }
        var bumps = capture(ctx, "bump_on").diff(capture(ctx, "shade_left"), 12, inside);
        ctx.check("a normal map roughens the lit sphere", bumps.count() > insideArea / 10,
                "> " + insideArea / 10 + " px", bumps.count() + " px");
        var matcap = capture(ctx, "matcap");
        int centre = matcap.rgb((inside.left() + inside.right()) / 2, (inside.top() + inside.bottom()) / 2);
        int r = (centre >> 16) & 0xFF, b = centre & 0xFF;
        ctx.check("the matcap paints its glassy blue in the middle", b > r + 40, "blue > red + 40",
                "r %d b %d".formatted(r, b));
        int square = capture(ctx, "plain_white").diff(base, 24, region).count();
        int ball = capture(ctx, "volume").diff(base, 24, region).count();
        ctx.check("a volume rounds the square quad off into a ball", ball < square * 85 / 100 && ball > square / 4,
                square / 4 + " .. " + square * 85 / 100 + " px", ball + " px");
        var layered = capture(ctx, "layer2_on").diff(capture(ctx, "plain_white"), 24, inside);
        ctx.check("the third layer shows", layered.count() > insideArea / 4, "> " + insideArea / 4 + " px",
                layered.count() + " px");
        // halfway, a crossfade shows each spark twice at half strength; pushed along the vectors it stays whole
        int crossfade = sparks(capture(ctx, "motion_off"), region);
        int pushed = sparks(capture(ctx, "motion_on"), region);
        ctx.check("halfway between two frames motion vectors keep the sparks whole, a crossfade halves them",
                pushed > crossfade * 2 + 20, "> " + (crossfade * 2 + 20) + " px", pushed + " px");
        var fringed = capture(ctx, "fringe").diff(capture(ctx, "haze_moving"), 12, inside);
        ctx.check("a colour fringe splits the distorted ground", fringed.count() > 1000, "> 1000 px",
                fringed.count() + " px");
        var sorted = capture(ctx, "orb_sorted");
        var resorted = sorted.diff(capture(ctx, "orb_unsorted"), 30, region);
        ctx.check("drawn back faces first, the see-through orb's back no longer shows through its front",
                resorted.count() > 200, "> 200 px", resorted.count() + " px at " + resorted.box());
        var single = capture(ctx, "stack_single");
        var moving = capture(ctx, "unstacked_single").diff(base, 12, inside).count();
        ctx.check("one unstacked haze bends the frame", moving > 2000, "> 2000 px", moving + " px");
        var alike = single.diff(capture(ctx, "unstacked_single"), 12, inside);
        ctx.check("one stacked haze bends the frame as one unstacked haze does", alike.count() < moving / 10 + 50,
                "< " + (moving / 10 + 50) + " px", alike.count() + " px at " + alike.box());
        var summed = capture(ctx, "stack_double").diff(single, 12, inside);
        ctx.check("two stacked hazes add up to one twice as strong", summed.count() < moving / 10 + 50,
                "< " + (moving / 10 + 50) + " px", summed.count() + " px at " + summed.box());
        var unsummed = capture(ctx, "unstacked_double").diff(single, 12, inside);
        ctx.check("unstacked, the second does not see the first", unsummed.count() > moving / 4,
                "> " + moving / 4 + " px", unsummed.count() + " px");
        ctx.check("stacked hazes take no scene copy of their own", ctx.<Integer>get("copies_stack_double") == 0,
                0, ctx.<Integer>get("copies_stack_double"));
        var inline = capture(ctx, "stack_double_inline").diff(capture(ctx, "unstacked_single_inline"), 12, inside);
        ctx.check("drawn in place too, two stacked hazes add up", inline.count() < moving / 10 + 50,
                "< " + (moving / 10 + 50) + " px", inline.count() + " px at " + inline.box());
        var bent = capture(ctx, "glass").diff(base, 12, inside);
        ctx.check("a ball of glass bends the ground behind it by its normal", bent.count() > 2000, "> 2000 px",
                bent.count() + " px");
    }

    /** How many pixels show each row's colour. */
    private static int[] rowColors(ScreenshotCompare capture, ScreenshotCompare.Region region) {
        var counts = new int[ROW_COLORS.length];
        for (int y = region.top(); y < region.bottom(); y++) {
            for (int x = region.left(); x < region.right(); x++) {
                int rgb = capture.rgb(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                for (int i = 0; i < ROW_COLORS.length; i++) {
                    int c = ROW_COLORS[i];
                    if (near(r, (c >> 16) & 0xFF) && near(g, (c >> 8) & 0xFF) && near(b, c & 0xFF)) counts[i]++;
                }
            }
        }
        return counts;
    }

    private static boolean near(int channel, int target) {
        return target > 0 ? channel > 150 : channel < 90;
    }

    private static void rowPictures(TestContext ctx, ScreenshotCompare.Region region) {
        var fixed = rowColors(capture(ctx, "rows_fixed"), region);
        var random = rowColors(capture(ctx, "rows_random"), region);
        int fixedShown = (int) java.util.Arrays.stream(fixed).filter(n -> n > 50).count();
        int randomShown = (int) java.util.Arrays.stream(random).filter(n -> n > 50).count();
        ctx.check("without a random row every particle shows the same row", fixedShown == 1,
                "1 colour", java.util.Arrays.toString(fixed));
        ctx.check("with one, the particles show rows of their own", randomShown >= 2,
                ">= 2 colours", java.util.Arrays.toString(random));
    }

    private static ScreenshotCompare capture(TestContext ctx, String tag) {
        var capture = ScreenshotCompare.load(ctx, tag);
        ctx.require("the " + tag + " capture exists", capture != null);
        return capture;
    }

    private static void contactPictures(TestContext ctx, ScreenshotCompare base, ScreenshotCompare.Region region) {
        var off = capture(ctx, "contact_off");
        var sunkQuad = off.diff(base, 20, region);
        ctx.require("the sunk quad is on screen", sunkQuad.changed() != null && sunkQuad.count() > MIN_DRAWN_PIXELS / 2);
        var on = capture(ctx, "contact_on").diff(off, 40, region);
        ctx.check("a contact glow lights the quad", on.count() > 800, "> 800 px", on.count() + " px at " + on.box());
        var quad = sunkQuad.changed();
        ctx.check("where it meets the ground: the lower part of the quad",
                on.changed() != null && on.changed().top() + on.changed().bottom() > quad.top() + quad.bottom(),
                "below the middle of " + quad, String.valueOf(on.changed()));
        int hard = capture(ctx, "contact_hard").diff(off, 40, region).count();
        ctx.check("a hard band glows over more of its width", hard > on.count() * 11 / 10,
                "> " + on.count() * 11 / 10 + " px", hard + " px");
        int surface = capture(ctx, "contact_surface").diff(off, 40, region).count();
        ctx.check("measured square to the ground, the band seen at a slant is wider", surface > on.count() * 11 / 10,
                "> " + on.count() * 11 / 10 + " px", surface + " px");
        int broken = capture(ctx, "contact_broken").diff(off, 40, region).count();
        ctx.check("a noise eats into the band, not all of it", broken < on.count() && broken > on.count() / 20,
                on.count() / 20 + " .. " + on.count() + " px", broken + " px");
        var pulsed = capture(ctx, "contact_pulse_later").diff(capture(ctx, "contact_pulse"), 30, region);
        ctx.check("the pulses move", pulsed.count() > 300, "> 300 px", pulsed.count() + " px at " + pulsed.box());
        int shaped = capture(ctx, "contact_shape").diff(base, 40, region).count();
        int filled = capture(ctx, "contact_fill").diff(base, 40, region).count();
        ctx.check("not following the disc, the glow fills the quad where it touches", filled > shaped + 300,
                "> " + (shaped + 300) + " px", filled + " px");

        int buried = capture(ctx, "offset_none").diff(base, 24, region).count();
        int raised = capture(ctx, "offset_on").diff(base, 24, region).count();
        ctx.check("a camera offset shows the part under the ground", raised > buried + 500,
                "> " + (buried + 500) + " px", raised + " px");

        var edge = capture(ctx, "dissolve_ramp").diff(capture(ctx, "dissolve_flat"), 30, region);
        ctx.check("an edge gradient colours the burning edge", edge.count() > 300, "> 300 px",
                edge.count() + " px at " + edge.box());

        var leak = capture(ctx, "haze_unmasked").diff(capture(ctx, "haze_masked"), 12, region);
        ctx.check("the foreground mask keeps the ground in front of the haze out of it", leak.count() > 150,
                "> 150 px", leak.count() + " px at " + leak.box());
    }

    private static double redOverBlue(ScreenshotCompare capture, ScreenshotCompare.Region region) {
        var mean = capture.mean(region);
        return mean[0] - mean[2];
    }
}
