package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * Starting points: each is a few modules set up the way that kind of effect is usually built. Between them every
 * module and the main options of each are used somewhere ({@code KilaPresetsTest}), so they double as examples.
 */
@OnlyIn(Dist.CLIENT)
public final class KilaPresets {
    public enum Category {
        BASIC, FIRE_SMOKE, MAGIC, GROUND, SURFACE, SCREEN, STYLIZED;

        public String langKey() {
            return "kila.preset.category." + name().toLowerCase();
        }
    }

    /** What the preset is built to be drawn on. */
    public enum Target {
        PARTICLE, TRAIL, MESH;

        public String langKey() {
            return "kila.preset.target." + name().toLowerCase();
        }
    }

    public record Preset(String id, Category category, Target target, Consumer<KilaMaterial> setup) {
        Preset(String id, Category category, Consumer<KilaMaterial> setup) {
            this(id, category, Target.PARTICLE, setup);
        }

        public String langKey() {
            return "kila.preset." + id;
        }

        public KilaMaterial create() {
            var material = new KilaMaterial();
            setup.accept(material);
            return material;
        }
    }

    public static final Preset GLOW = new Preset("glow", Category.BASIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.color = new HDRColor(1f, 0.5f, 0.15f, 1f, 1.5f);
        m.main.texture.texture = KilaTextures.GLOW;
    });

    public static final Preset SMOKE = new Preset("smoke", Category.FIRE_SMOKE, m -> {
        m.color = new HDRColor(0.85f, 0.85f, 0.88f, 0.9f, 1f);
        m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        m.layer.setEnable(true);
        m.layer.texture.texture = KilaTextures.NOISE_CLOUD;
        m.layer.texture.scroll(0.04f, 0.06f);
        m.layer.strength.value = 0.7f;
        m.distortion.setEnable(true);
        m.distortion.strength.value = 0.04f;
        m.depth.setEnable(true);
    });

    /** Burns away as the particle's colour-over-lifetime alpha drops — which also works on trails. */
    public static final Preset DISSOLVE = new Preset("dissolve", Category.BASIC, m -> {
        m.vertexColor = KilaMaterial.VertexColor.RGB_ONLY;
        m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        m.dissolve.setEnable(true);
        m.dissolve.progress.value = 1;
        m.dissolve.progress.bind(KilaDriver.Source.VERTEX_A, -1);
    });

    public static final Preset MAGIC_CIRCLE = new Preset("magic_circle", Category.MAGIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(0.35f, 0.75f, 1f, 1f, 3f);
        m.main.texture.texture = KilaTextures.RUNE_CIRCLE;
        m.main.texture.rotationSpeed = 30;
        m.main.texture.wrap(KilaTexture.Wrap.CLIP, KilaTexture.Wrap.CLIP);
        m.layer.setEnable(true);
        m.layer.blend = KilaLayer.Blend.ADD;
        m.layer.texture.texture = KilaTextures.NOISE_CELLS;
        m.layer.texture.polar = true;
        m.layer.texture.scroll(0f, 0.2f);
        m.layer.strength.value = 0.6f;
    });

    /** The magic circle painted onto the ground under a billboard, over steps and slopes. */
    public static final Preset GROUND_CIRCLE = new Preset("ground_circle", Category.GROUND, m -> {
        MAGIC_CIRCLE.setup.accept(m);
        m.projection.setEnable(true);
        m.projection.depth = 2;
    });

    public static final Preset SLASH = new Preset("slash", Category.BASIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(1f, 1f, 1f, 1f, 2.5f);
        m.main.texture.texture = KilaTextures.SLASH;
        m.main.texture.wrap(KilaTexture.Wrap.CLIP, KilaTexture.Wrap.CLIP);
        m.ramp.setEnable(true);
        m.ramp.input = KilaRamp.Input.ALPHA;
        m.ramp.gradient.getRgbP().clear();
        m.ramp.gradient.getRgbP().addAll(new GradientColor(0xFF001A60, 0xFF1060FF, 0xFF50C8FF, 0xFFFFFFFF).getRgbP());
        m.dissolve.setEnable(true);
        m.dissolve.progress.value = 1;
        m.dissolve.progress.bind(KilaDriver.Source.VERTEX_A, -1);
        m.dissolve.direction = KilaDissolve.Direction.U;
        m.dissolve.edgeWidth.value = 0.04f;
        m.vertexColor = KilaMaterial.VertexColor.RGB_ONLY;
    });

    public static final Preset SHOCKWAVE = new Preset("shockwave", Category.BASIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(0.8f, 0.9f, 1f, 1f, 2f);
        m.main.texture.texture = KilaTextures.RING;
        m.main.texture.wrap(KilaTexture.Wrap.CLAMP, KilaTexture.Wrap.CLAMP);
        m.distortion.setEnable(true);
        m.distortion.texture.polar = true;
        m.distortion.texture.scroll(0f, 0.3f);
        m.distortion.strength.value = 0.03f;
    });

    /** For a sphere or other mesh: a bright rim, a slowly crawling energy layer. */
    public static final Preset ENERGY_SHIELD = new Preset("energy_shield", Category.SURFACE, Target.MESH, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.color = new HDRColor(0.25f, 0.6f, 1f, 0.35f, 1f);
        m.main.texture.texture = KilaTextures.WHITE;
        m.layer.setEnable(true);
        m.layer.blend = KilaLayer.Blend.ADD;
        m.layer.texture.texture = KilaTextures.NOISE_CELLS;
        m.layer.texture.scroll(0.05f, 0.1f);
        m.fresnel.setEnable(true);
        m.fresnel.power = 3;
        m.fresnel.color = new HDRColor(0.4f, 0.8f, 1f, 1f, 4f);
        m.depth.setEnable(true);
        m.depth.distance = 0.3f;
        m.depth.contactWidth.value = 0.5f;
        // the body is faint: the ring where it meets the ground is not
        m.depth.contactFollowsShape = false;
        m.depth.measure = KilaDepth.Measure.SURFACE;
        m.depth.contactHardness = 0.3f;
        m.depth.contactBreakup = 0.5f;
    });

    public static final Preset FIRE = new Preset("fire", Category.FIRE_SMOKE, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.vertexColor = KilaMaterial.VertexColor.RGB_ONLY;
        m.color = new HDRColor(1f, 1f, 1f, 1f, 1.5f);
        m.main.texture.texture = KilaTextures.GLOW;
        m.distortion.setEnable(true);
        m.distortion.strength.value = 0.12f;
        m.distortion.texture.scroll(0f, 0.6f);
        m.ramp.setEnable(true);
        m.ramp.input = KilaRamp.Input.ALPHA;
        m.dissolve.setEnable(true);
        m.dissolve.texture.scroll(0f, 0.4f);
        m.dissolve.progress.value = 1;
        m.dissolve.progress.bind(KilaDriver.Source.VERTEX_A, -1);
        m.dissolve.softness = 0.2f;
        m.dissolve.edgeWidth.value = 0;
    });

    /** An electric web: the bright borders of a cell noise, crawling and jittering, fading out at the rim. */
    public static final Preset LIGHTNING = new Preset("lightning", Category.MAGIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(0.45f, 0.65f, 1f, 1f, 3.5f);
        m.alphaSharpen = 3;
        m.main.texture.texture = KilaTextures.NOISE_CELLS;
        m.main.texture.colorMode = KilaTexture.ColorMode.LUMINANCE_AS_ALPHA;
        m.main.texture.scroll(0.6f, 0.25f);
        m.distortion.setEnable(true);
        m.distortion.strength.value = 0.06f;
        m.distortion.texture.scroll(1.2f, -0.8f);
        m.mask1.setEnable(true);
        // the alpha power sharpens the mask too: this leaves it about linear
        m.mask1.power = 0.35f;
    });

    /** Shimmering air: the scene behind, pushed around by a rising noise. Alpha blend, so the soft edge fades it in. */
    public static final Preset HEAT_HAZE = new Preset("heat_haze", Category.SCREEN, m -> {
        m.worldLight = false;
        m.fog = false;
        m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        m.screenDistortion.setEnable(true);
        m.screenDistortion.texture.noise(KilaTexture.Noise.GRADIENT, 5).scroll(0f, 0.6f);
        m.screenDistortion.strength.value = 0.015f;
        m.depth.setEnable(true);
    });

    /** A vortex: cloud noise in polar space, twirled into spiral arms and pulled inwards. */
    public static final Preset PORTAL = new Preset("portal", Category.MAGIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(1f, 1f, 1f, 1f, 2.5f);
        m.main.texture.texture = KilaTextures.NOISE_CLOUD;
        m.main.texture.colorMode = KilaTexture.ColorMode.LUMINANCE_AS_ALPHA;
        m.main.texture.polar = true;
        m.main.texture.polarAngular = 3;
        m.main.texture.scroll(0.35f, 0.05f);
        m.uvEffects.setEnable(true);
        m.uvEffects.twirl.value = 7;
        m.mask1.setEnable(true);
        m.mask1.power = 0.6f;
        m.ramp.setEnable(true);
        m.ramp.input = KilaRamp.Input.ALPHA;
        m.ramp.gradient.getRgbP().clear();
        m.ramp.gradient.getRgbP().addAll(new GradientColor(0xFF12002E, 0xFF5B12D8, 0xFFB46CFF, 0xFFFFFFFF).getRgbP());
    });

    /** A smoke puff lit by the six lightmaps it was baked with: the key light and the dynamic ones fall on its sides. */
    public static final Preset LIT_SMOKE = new Preset("lit_smoke", Category.FIRE_SMOKE, m -> {
        m.color = new HDRColor(0.85f, 0.85f, 0.9f, 1f, 1f);
        m.main.texture.texture = KilaTextures.SMOKE6_POS;
        m.shading.setEnable(true);
        m.shading.model = KilaShading.Model.SIX_WAY;
        m.shading.ambient = 0.7f;
        m.shading.lightColor = new HDRColor(1f, 0.95f, 0.85f, 1f, 1.6f);
        m.depth.setEnable(true);
    });

    /** For a sphere or other mesh: glass that bends what is behind it, a painted reflection, a bright rim. */
    public static final Preset CRYSTAL = new Preset("crystal", Category.SURFACE, Target.MESH, m -> {
        m.worldLight = false;
        m.doubleSided = true;
        m.backFacesFirst = true;
        m.color = new HDRColor(0.55f, 0.8f, 1f, 0.35f, 1f);
        m.main.texture.texture = KilaTextures.WHITE;
        m.shading.setEnable(true);
        m.shading.normal = KilaShading.Normal.MESH;
        m.shading.bump = KilaShading.Bump.NORMAL_MAP;
        m.shading.bumpStrength = 0.4f;
        m.matcap.setEnable(true);
        m.fresnel.setEnable(true);
        m.fresnel.color = new HDRColor(0.5f, 0.85f, 1f, 1f, 2f);
        m.screenDistortion.setEnable(true);
        m.screenDistortion.source = KilaScreenDistortion.Source.NORMAL;
        m.screenDistortion.strength.value = 0.03f;
        m.screenDistortion.chromatic = 0.3f;
    });

    /** A ball of fog that thins towards its edge from any side, and where the ground cuts it. */
    public static final Preset FOG_BALL = new Preset("fog_ball", Category.FIRE_SMOKE, m -> {
        m.color = new HDRColor(0.8f, 0.85f, 0.8f, 0.8f, 1f);
        m.main.texture.texture = KilaTextures.NOISE_CLOUD;
        m.main.texture.colorMode = KilaTexture.ColorMode.LUMINANCE_AS_ALPHA;
        m.main.texture.scroll(0.03f, 0.05f);
        m.volume.setEnable(true);
        m.volume.density = 1.5f;
        m.shading.setEnable(true);
        m.shading.wrap = 0.8f;
    });

    /** Hit sparks: each its own brightness, pulled towards the camera so the wall they come off doesn't cut them. */
    public static final Preset SPARKS = new Preset("sparks", Category.BASIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.cameraOffset = 0.3f;
        m.color = new HDRColor(1f, 0.75f, 0.35f, 1f, 3f);
        m.main.texture.texture = KilaTextures.SPARK;
        m.intensity.value = 0.4f;
        m.intensity.bind(KilaDriver.Source.RANDOM, 1.2f);
        m.cameraFade.setEnable(true);
        m.cameraFade.near = 0.3f;
        m.cameraFade.range = 1.5f;
    });

    /** A billowing puff played once over the lifetime: a random puff per particle, its frames blended along their motion. */
    public static final Preset EXPLOSION_SMOKE = new Preset("explosion_smoke", Category.FIRE_SMOKE, m -> {
        m.blend = BlendMode.Preset.PREMULTIPLIED;
        m.color = new HDRColor(0.55f, 0.52f, 0.5f, 1f, 1f);
        m.main.texture.texture = KilaTextures.SMOKE_BURST;
        m.main.flipbook = true;
        m.main.columns = 8;
        m.main.rows = 4;
        m.main.randomRow = true;
        m.main.frameSource = KilaMainTexture.FrameSource.DRIVER;
        m.main.frame.bind(KilaDriver.Source.LIFE, 1);
        m.main.frameBlend = true;
        m.main.motionVectors = true;
        m.main.motionTexture.texture = KilaTextures.SMOKE_BURST_MV;
        m.depth.setEnable(true);
        m.lit.setEnable(true);
    });

    /** Fades to the tail and as points age, soft along its edges, waving, two noises crawling through it, colour fringes. */
    public static final Preset ENERGY_TRAIL = new Preset("energy_trail", Category.MAGIC, Target.TRAIL, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(0.4f, 0.85f, 1f, 1f, 2.5f);
        m.backColor = new HDRColor(1f, 0.45f, 0.9f, 1f, 1f);
        m.alpha.value = 0;
        m.alpha.bind(KilaDriver.Source.POINT_T, 1);
        m.intensity.value = 1.5f;
        m.intensity.bind(KilaDriver.Source.POINT_LIFE, -1);
        m.main.texture.texture = KilaTextures.TRAIL;
        m.main.texture.wrap(KilaTexture.Wrap.CLAMP, KilaTexture.Wrap.CLAMP);
        m.main.dispersion = true;
        m.main.dispersionDirection.set(0, 1);
        m.layer.setEnable(true);
        m.layer.blend = KilaLayer.Blend.ADD;
        m.layer.texture.texture = KilaTextures.NOISE_FLOW;
        m.layer.texture.tiling(2, 1).scroll(-1.5f, 0);
        m.layer.strength.value = 0.6f;
        m.layer2.setEnable(true);
        m.layer2.blend = KilaLayer.Blend.SCREEN;
        m.layer2.texture.texture = KilaTextures.NOISE_CELLS;
        m.layer2.texture.tiling(3, 1).scroll(-0.6f, 0.2f);
        m.layer2.strength.value = 0.5f;
        // the ramp turned across the ribbon and mirrored: 0 at both edges, 1 down the middle
        m.mask2.setEnable(true);
        m.mask2.texture.texture = KilaTextures.GRADIENT_LINEAR;
        m.mask2.texture.rotation = 90;
        m.mask2.texture.tiling(2, 1);
        m.mask2.texture.wrapU = KilaTexture.Wrap.MIRROR;
        m.uvEffects.setEnable(true);
        m.uvEffects.twirl.value = 0;
        m.uvEffects.waveAmplitude.value = 0.06f;
        m.ramp.setEnable(true);
        m.ramp.input = KilaRamp.Input.U;
        m.ramp.mode = KilaRamp.Mode.MULTIPLY;
        m.ramp.gradient.getRgbP().clear();
        m.ramp.gradient.getRgbP().addAll(new GradientColor(0xFFFFFFFF, 0xFF7FE6FF, 0xFF6A4BFF).getRgbP());
    });

    /** Forms from its core outwards as the emitter's custom data (stream 0, x) rises from 0 to 1; invisible at 0. */
    public static final Preset CHARGE_ORB = new Preset("charge_orb", Category.MAGIC, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.color = new HDRColor(0.7f, 0.35f, 1f, 1f, 2f);
        m.emission = new HDRColor(0.25f, 0.1f, 0.4f, 1f, 1f);
        m.main.texture.texture = KilaTextures.GLOW;
        m.dissolve.setEnable(true);
        m.dissolve.texture.texture = KilaTextures.NOISE_CELLS;
        m.dissolve.texture.scroll(0.15f, 0.1f);
        m.dissolve.progress.value = 1;
        m.dissolve.progress.bindCustom(0, 0, -1);
        m.dissolve.direction = KilaDissolve.Direction.RADIAL;
        m.dissolve.directionInvert = true;
        m.dissolve.edgeWidth.value = 0.12f;
        m.dissolve.edgeGradient = true;
        m.dissolve.edgeGradientColors.getRgbP().clear();
        m.dissolve.edgeGradientColors.getRgbP().addAll(new GradientColor(0xFFFFFFFF, 0xFFE0A0FF, 0xFF8030FF).getRgbP());
        m.uvEffects.setEnable(true);
        m.uvEffects.twirl.value = 0;
        m.uvEffects.shear.set(4, 4);
        m.uvEffects.rippleAmplitude = 0.02f;
    });

    /** A burn mark multiplied onto the ground, eaten away from its ragged edge over the lifetime. */
    public static final Preset SCORCH_MARK = new Preset("scorch_mark", Category.GROUND, m -> {
        m.blend = BlendMode.Preset.MULTIPLY;
        m.worldLight = false;
        m.fog = false;
        m.color = new HDRColor(0.2f, 0.16f, 0.13f, 1f, 1f);
        m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        m.layer.setEnable(true);
        m.layer.texture.texture = KilaTextures.NOISE_CLOUD;
        m.layer.texture.scroll(0, 0);
        m.layer.strength.value = 0.5f;
        m.projection.setEnable(true);
        m.projection.depth = 2;
        m.projection.size = 0.8f;
        m.dissolve.setEnable(true);
        m.dissolve.progress.value = 0;
        m.dissolve.progress.bind(KilaDriver.Source.LIFE, 1);
        m.dissolve.direction = KilaDissolve.Direction.TEXTURE;
        m.dissolve.directionTexture.texture = KilaTextures.GRADIENT_RADIAL;
        m.dissolve.directionWeight = 0.6f;
        m.dissolve.edgeWidth.value = 0;
    });

    /** Light through water, projected onto the ground in world space so it stays put, its cells bent by a flow map. */
    public static final Preset CAUSTICS = new Preset("caustics", Category.GROUND, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.color = new HDRColor(0.55f, 0.85f, 1f, 1f, 3f);
        m.alphaSharpen = 2;
        m.main.texture.texture = KilaTextures.NOISE_CELLS;
        m.main.texture.colorMode = KilaTexture.ColorMode.LUMINANCE_AS_ALPHA;
        m.main.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
        // eight cells per repeat: one about every block
        m.main.texture.tiling(0.125f, 0.125f);
        m.distortion.setEnable(true);
        m.distortion.mode = KilaDistortion.Mode.FLOW_MAP;
        m.distortion.strength.value = 0.15f;
        m.mask1.setEnable(true);
        m.mask1.power = 0.5f;
        m.projection.setEnable(true);
        m.projection.depth = 2;
        m.projection.size = 1;
    });

    /** For a big sphere through the terrain: a faint shell, bright bands pulsing out along where it cuts the ground. */
    public static final Preset SCAN_PULSE = new Preset("scan_pulse", Category.GROUND, Target.MESH, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(0.3f, 0.8f, 1f, 0.15f, 1f);
        m.main.texture.texture = KilaTextures.WHITE;
        m.fresnel.setEnable(true);
        m.fresnel.mode = KilaFresnel.Mode.FADE_CENTER;
        m.depth.setEnable(true);
        m.depth.contactWidth.value = 1.2f;
        m.depth.contactStrength.value = 1;
        m.depth.contactFollowsShape = false;
        m.depth.measure = KilaDepth.Measure.SURFACE;
        m.depth.contactPulses = 3;
        m.depth.contactPulseSpeed = 1.5f;
        m.depth.contactGradient = true;
    });

    /** Scanlines in screen space, a bright rim, slices jittering sideways. */
    public static final Preset HOLOGRAM = new Preset("hologram", Category.SURFACE, Target.MESH, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.color = new HDRColor(0.3f, 0.9f, 1f, 0.5f, 1.2f);
        m.main.texture.texture = KilaTextures.WHITE;
        m.layer.setEnable(true);
        m.layer.texture.texture = KilaTextures.GRADIENT_LINEAR;
        m.layer.texture.uvSource = KilaTexture.UvSource.SCREEN;
        m.layer.texture.rotation = 90;
        m.layer.texture.tiling(120, 1).scroll(0.4f, 0);
        m.layer.strength.value = 0.8f;
        m.fresnel.setEnable(true);
        m.fresnel.color = new HDRColor(0.4f, 0.95f, 1f, 1f, 3f);
        m.vertexOffset.setEnable(true);
        m.vertexOffset.direction = KilaVertexOffset.Direction.AXIS;
        m.vertexOffset.axis.set(1, 0, 0);
        m.vertexOffset.bidirectional = true;
        m.vertexOffset.strength.value = 0.04f;
        m.vertexOffset.texture.tiling(1, 6).scroll(0, 2.5f);
    });

    /** Opaque rock, bumped by a procedural height and lit by the world and the dynamic lights. */
    public static final Preset DEBRIS = new Preset("debris", Category.SURFACE, Target.MESH, m -> {
        m.blend = BlendMode.Preset.OPAQUE;
        m.depthWrite = true;
        m.color = new HDRColor(0.8f, 0.72f, 0.64f, 1f, 1f);
        m.main.texture.texture = KilaTextures.NOISE_PERLIN;
        m.shading.setEnable(true);
        m.shading.normal = KilaShading.Normal.MESH;
        m.shading.bump = KilaShading.Bump.HEIGHT;
        m.shading.bumpStrength = 1.5f;
        m.shading.ambient = 0.45f;
        m.lit.setEnable(true);
    });

    /** Heat behind an engine: the hazes of many particles add up instead of each bending the last. */
    public static final Preset JET_EXHAUST = new Preset("jet_exhaust", Category.SCREEN, m -> {
        m.worldLight = false;
        m.fog = false;
        m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        m.screenDistortion.setEnable(true);
        m.screenDistortion.stack = true;
        m.screenDistortion.texture.scroll(0, 1.2f);
        m.screenDistortion.strength.value = 0.004f;
        m.depth.setEnable(true);
    });

    /** Hand-drawn fire: a few colour bands, a hard cut-out edge with an outline, drawn on twelves with a boil. */
    public static final Preset TOON_FIRE = new Preset("toon_fire", Category.STYLIZED, m -> {
        m.worldLight = false;
        m.alphaClip = 0.35f;
        m.frameRate = 12;
        m.boil = 0.02f;
        m.main.texture.texture = KilaTextures.GLOW;
        m.ramp.setEnable(true);
        m.ramp.input = KilaRamp.Input.ALPHA;
        m.ramp.offset.bind(KilaDriver.Source.LIFE, -0.4f);
        m.dissolve.setEnable(true);
        m.dissolve.texture.noise(KilaTexture.Noise.VORONOI, 5).scroll(0, -0.8f);
        m.dissolve.progress.value = 0.1f;
        m.dissolve.progress.bind(KilaDriver.Source.LIFE, 0.8f);
        m.dissolve.softness = 0.01f;
        m.dissolve.edgeWidth.value = 0.06f;
        m.dissolve.edge = KilaDissolve.Edge.REPLACE;
        m.dissolve.edgeColor = new HDRColor(0.35f, 0.04f, 0.02f, 1f, 1f);
        m.colorAdjust.setEnable(true);
        m.colorAdjust.posterize = 4;
    });

    /** Pixel art: a looping puff from a random frame, snapped to a coarse grid and sampled without filtering, twinkling. */
    public static final Preset PIXEL_SPELL = new Preset("pixel_spell", Category.STYLIZED, m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.color = new HDRColor(0.75f, 0.4f, 1f, 1f, 2f);
        m.main.texture.texture = KilaTextures.SMOKE_FLIP;
        m.main.texture.nearest = true;
        m.main.flipbook = true;
        m.main.randomStart = true;
        m.mask1.setEnable(true);
        m.mask1.texture.noise(KilaTexture.Noise.SIMPLE, 24);
        m.mask1.power = 0.6f;
        m.pixelate.setEnable(true);
        m.pixelate.pixels = 24;
    });

    public static final List<Preset> ALL = List.of(GLOW, SPARKS, DISSOLVE, SLASH, SHOCKWAVE,
            FIRE, SMOKE, EXPLOSION_SMOKE, LIT_SMOKE, FOG_BALL,
            MAGIC_CIRCLE, PORTAL, LIGHTNING, CHARGE_ORB, ENERGY_TRAIL,
            GROUND_CIRCLE, SCORCH_MARK, CAUSTICS, SCAN_PULSE,
            ENERGY_SHIELD, CRYSTAL, HOLOGRAM, DEBRIS,
            HEAT_HAZE, JET_EXHAUST,
            TOON_FIRE, PIXEL_SPELL);

    private KilaPresets() {
    }
}
