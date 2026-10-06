package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/** Starting points: each is a few modules set up the way that kind of effect is usually built. */
@OnlyIn(Dist.CLIENT)
public final class KilaPresets {
    public record Preset(String id, Consumer<KilaMaterial> setup) {
        public String langKey() {
            return "kila.preset." + id;
        }

        public KilaMaterial create() {
            var material = new KilaMaterial();
            setup.accept(material);
            return material;
        }
    }

    public static final Preset GLOW = new Preset("glow", m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.color = new HDRColor(1f, 0.5f, 0.15f, 1f, 1.5f);
        m.main.texture.texture = KilaTextures.GLOW;
    });

    public static final Preset SMOKE = new Preset("smoke", m -> {
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
    public static final Preset DISSOLVE = new Preset("dissolve", m -> {
        m.vertexColor = KilaMaterial.VertexColor.RGB_ONLY;
        m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        m.dissolve.setEnable(true);
        m.dissolve.progress.value = 1;
        m.dissolve.progress.bind(KilaDriver.Source.VERTEX_A, -1);
    });

    public static final Preset MAGIC_CIRCLE = new Preset("magic_circle", m -> {
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
    public static final Preset GROUND_CIRCLE = new Preset("ground_circle", m -> {
        MAGIC_CIRCLE.setup.accept(m);
        m.projection.setEnable(true);
        m.projection.depth = 2;
    });

    public static final Preset SLASH = new Preset("slash", m -> {
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

    public static final Preset SHOCKWAVE = new Preset("shockwave", m -> {
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
    public static final Preset ENERGY_SHIELD = new Preset("energy_shield", m -> {
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

    public static final Preset FIRE = new Preset("fire", m -> {
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
    public static final Preset LIGHTNING = new Preset("lightning", m -> {
        m.blend = BlendMode.Preset.ADDITIVE;
        m.worldLight = false;
        m.doubleSided = true;
        m.color = new HDRColor(0.45f, 0.65f, 1f, 1f, 2.5f);
        m.alphaSharpen = 6;
        m.main.texture.texture = KilaTextures.NOISE_CELLS;
        m.main.texture.colorMode = KilaTexture.ColorMode.LUMINANCE_AS_ALPHA;
        m.main.texture.scroll(0.6f, 0.25f);
        m.distortion.setEnable(true);
        m.distortion.strength.value = 0.06f;
        m.distortion.texture.scroll(1.2f, -0.8f);
        m.mask1.setEnable(true);
    });

    /** Shimmering air: the scene behind, pushed around by a rising noise. Alpha blend, so the soft edge fades it in. */
    public static final Preset HEAT_HAZE = new Preset("heat_haze", m -> {
        m.worldLight = false;
        m.fog = false;
        m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
        m.screenDistortion.setEnable(true);
        m.screenDistortion.texture.noise(KilaTexture.Noise.GRADIENT, 5).scroll(0f, 0.6f);
        m.screenDistortion.strength.value = 0.015f;
        m.depth.setEnable(true);
    });

    /** A vortex: cloud noise in polar space, twirled into spiral arms and pulled inwards. */
    public static final Preset PORTAL = new Preset("portal", m -> {
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
    public static final Preset LIT_SMOKE = new Preset("lit_smoke", m -> {
        m.color = new HDRColor(0.85f, 0.85f, 0.9f, 1f, 1f);
        m.main.texture.texture = KilaTextures.SMOKE6_POS;
        m.shading.setEnable(true);
        m.shading.model = KilaShading.Model.SIX_WAY;
        m.shading.ambient = 0.7f;
        m.shading.lightColor = new HDRColor(1f, 0.95f, 0.85f, 1f, 1.6f);
        m.depth.setEnable(true);
    });

    /** For a sphere or other mesh: glass that bends what is behind it, a painted reflection, a bright rim. */
    public static final Preset CRYSTAL = new Preset("crystal", m -> {
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
    public static final Preset FOG_BALL = new Preset("fog_ball", m -> {
        m.color = new HDRColor(0.8f, 0.85f, 0.8f, 0.8f, 1f);
        m.main.texture.texture = KilaTextures.NOISE_CLOUD;
        m.main.texture.colorMode = KilaTexture.ColorMode.LUMINANCE_AS_ALPHA;
        m.main.texture.scroll(0.03f, 0.05f);
        m.volume.setEnable(true);
        m.volume.density = 1.5f;
        m.shading.setEnable(true);
        m.shading.wrap = 0.8f;
    });

    public static final List<Preset> ALL = List.of(GLOW, SMOKE, DISSOLVE, MAGIC_CIRCLE, GROUND_CIRCLE, SLASH, SHOCKWAVE,
            ENERGY_SHIELD, FIRE, LIGHTNING, HEAT_HAZE, PORTAL, LIT_SMOKE, CRYSTAL, FOG_BALL);

    private KilaPresets() {
    }
}
