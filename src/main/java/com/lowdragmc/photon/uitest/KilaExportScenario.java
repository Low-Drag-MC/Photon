package com.lowdragmc.photon.uitest;

import com.lowdragmc.kilagraph.rendertype.format.KGVertexFormat;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphEditorView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.BlockNodeElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeElement;
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
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDissolve;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDriver;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaFresnel;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaLayer;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMainTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMatcap;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaRamp;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaScreenDistortion;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaShading;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTexture;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaUvEffects;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaVertexOffset;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.KilaGraphExport;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A KilaMaterial and the shader graph exported from it draw the same: each configuration twice in one frame, Kila on
 * the left and the graph on the right, against a plain wall at noon, compared cell by cell. What depends on where it
 * is drawn (world and screen uvs) is drawn at one spot twice instead, and holds still.
 */
@LDLRegisterClient(name = "kila_export", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class KilaExportScenario implements UIScenario {
    private static final double DISTANCE = 5;
    private static final double APART = 1.25;
    /** a half extent */
    private static final float SIZE = 1;
    private static final int GRID = 6;
    /** per channel, 0..255, on a cell's mean */
    private static final double TOLERANCE = 6;
    private static final int START_COLOR = 0xE6FFCC99;
    private static final AtomicReference<Long> DAY_TIME = new AtomicReference<>();
    private static final AtomicReference<Boolean> DAYLIGHT = new AtomicReference<>(true);
    private static final Map<BlockPos, BlockState> WALL = new LinkedHashMap<>();
    private static final List<File> FILES = new ArrayList<>();

    /** {@code still}: drawn at one spot twice, so it must hold still; for what depends on where it is drawn. */
    private record Case(String id, boolean still, Consumer<KilaMaterial> setup) {
        KilaMaterial create() {
            var material = new KilaMaterial();
            setup.accept(material);
            return material;
        }
    }

    private static Case pair(String id, Consumer<KilaMaterial> setup) {
        return new Case(id, false, setup);
    }

    private static final List<Case> CASES = List.of(
            pair("plain", m -> {
            }),
            pair("texture_chain", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.main.texture.tiling(2, 1.5f);
                m.main.texture.offset.set(0.1f, 0.3f);
                m.main.texture.rotation = 30;
                m.color = new HDRColor(1f, 0.6f, 0.3f, 1f, 1.5f);
            }),
            pair("polar_spin", m -> {
                m.main.texture.texture = KilaTextures.NOISE_FLOW;
                m.main.texture.polar = true;
                m.main.texture.polarAngular = 3;
                m.main.texture.polarRadial = 1.2f;
                m.main.texture.rotationSpeed = 45;
                m.main.texture.scroll(0.2f, 0.1f);
                m.mask1.setEnable(true);
            }),
            pair("wraps", m -> {
                m.main.texture.texture = KilaTextures.GRADIENT_RADIAL;
                m.main.texture.tiling(1.6f, 1.6f);
                m.main.texture.offset.set(-0.3f, -0.3f);
                m.main.texture.wrap(KilaTexture.Wrap.MIRROR, KilaTexture.Wrap.CLIP);
            }),
            pair("clamp_nearest", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.main.texture.nearest = true;
                m.main.texture.wrap(KilaTexture.Wrap.CLAMP, KilaTexture.Wrap.CLAMP);
                m.main.texture.tiling(1.5f, 1.5f);
                m.main.texture.offset.set(-0.25f, -0.25f);
            }),
            pair("clip_repeat", m -> {
                m.main.texture.texture = KilaTextures.RING;
                m.main.texture.wrap(KilaTexture.Wrap.CLIP, KilaTexture.Wrap.REPEAT);
                m.main.texture.tiling(1.4f, 2);
                m.main.texture.offset.set(-0.2f, 0);
            }),
            pair("luminance_alpha", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CLOUD;
                m.main.texture.colorMode = KilaTexture.ColorMode.LUMINANCE_AS_ALPHA;
                m.alphaSharpen = 2;
                m.color = new HDRColor(0.3f, 0.7f, 1f, 1f, 2f);
            }),
            pair("red_alpha_only", m -> {
                m.main.texture.texture = KilaTextures.RING;
                m.main.texture.colorMode = KilaTexture.ColorMode.RED_AS_ALPHA;
                m.layer.setEnable(true);
                m.layer.texture.texture = KilaTextures.NOISE_PERLIN;
                m.layer.texture.colorMode = KilaTexture.ColorMode.ALPHA_ONLY;
            }),
            pair("layer_multiply", m -> layer(m, KilaLayer.Blend.MULTIPLY, true, false, 1)),
            pair("layer_add", m -> layer(m, KilaLayer.Blend.ADD, true, true, 0.8f)),
            pair("layer_lerp", m -> layer(m, KilaLayer.Blend.LERP, true, true, 0.6f)),
            pair("layer_screen", m -> {
                layer(m, KilaLayer.Blend.SCREEN, true, true, 1);
                m.layer2.setEnable(true);
                m.layer2.texture.texture = KilaTextures.GRADIENT_LINEAR;
                m.layer2.blend = KilaLayer.Blend.MULTIPLY;
                m.layer2.affectsColor = false;
            }),
            pair("ramp", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CLOUD;
                m.ramp.setEnable(true);
            }),
            pair("ramp_u_alpha", m -> {
                m.ramp.setEnable(true);
                m.ramp.input = KilaRamp.Input.U;
                m.ramp.mode = KilaRamp.Mode.REPLACE_ALPHA;
                m.ramp.offset.value = 0.1f;
                m.ramp.intensity = 2;
                m.ramp.gradient.getAP().clear();
                m.ramp.gradient.getAP().add(new org.joml.Vector2f(0, 0.2f));
                m.ramp.gradient.getAP().add(new org.joml.Vector2f(1, 1));
            }),
            pair("ramp_multiply_red", m -> {
                m.main.texture.texture = KilaTextures.GLOW;
                m.ramp.setEnable(true);
                m.ramp.input = KilaRamp.Input.RED;
                m.ramp.mode = KilaRamp.Mode.MULTIPLY;
            }),
            pair("vertex_alpha", m -> m.vertexColor = KilaMaterial.VertexColor.ALPHA_ONLY),
            pair("vertex_rgb", m -> m.vertexColor = KilaMaterial.VertexColor.RGB_ONLY),
            pair("vertex_ignore", m -> m.vertexColor = KilaMaterial.VertexColor.IGNORE),
            pair("masks", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.mask1.setEnable(true);
                m.mask1.power = 2;
                m.mask1.strength = 0.8f;
                m.mask1.affectsColor = true;
                m.mask2.setEnable(true);
                m.mask2.texture.texture = KilaTextures.NOISE_CELLS;
                m.mask2.texture.channel = KilaTexture.Channel.LUMINANCE;
                m.mask2.invert = true;
                m.mask2.texture.scroll(0.1f, 0);
            }),
            pair("mask_noises", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.mask1.setEnable(true);
                m.mask1.texture.noise(KilaTexture.Noise.GRADIENT, 6);
                m.mask2.setEnable(true);
                m.mask2.texture.noise(KilaTexture.Noise.VORONOI, 4);
                m.mask2.texture.noiseMotion = 2;
            }),
            pair("mask_simple_noise", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.mask1.setEnable(true);
                m.mask1.texture.noise(KilaTexture.Noise.SIMPLE, 12);
                m.mask1.texture.scroll(0, 0.3f);
            }),
            pair("dissolve", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.dissolve.setEnable(true);
                m.dissolve.progress.value = 0.45f;
            }),
            pair("dissolve_u_replace", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.dissolve.setEnable(true);
                m.dissolve.direction = KilaDissolve.Direction.U;
                m.dissolve.directionInvert = true;
                m.dissolve.directionWeight = 0.7f;
                m.dissolve.edge = KilaDissolve.Edge.REPLACE;
                m.dissolve.edgeTint = true;
                m.dissolve.progress.value = 0.5f;
            }),
            pair("dissolve_radial_gradient", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.dissolve.setEnable(true);
                m.dissolve.direction = KilaDissolve.Direction.RADIAL;
                m.dissolve.edgeGradient = true;
                m.dissolve.edgeWidth.value = 0.2f;
                m.dissolve.texture.noise(KilaTexture.Noise.GRADIENT, 5);
                m.dissolve.sharpen = 1.5f;
            }),
            pair("dissolve_texture_direction", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.dissolve.setEnable(true);
                m.dissolve.direction = KilaDissolve.Direction.TEXTURE;
                m.dissolve.directionTexture.tiling(1, 1);
                m.dissolve.directionWeight = 0.8f;
                m.dissolve.progress.value = 0.5f;
            }),
            pair("distortion", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.distortion.setEnable(true);
                m.distortion.strength.value = 0.08f;
                m.distortion.mask = KilaDistortion.Mask.RADIAL;
                m.distortion.mask1 = true;
                m.mask1.setEnable(true);
                m.dissolve.setEnable(true);
                m.dissolve.progress.value = 0.3f;
            }),
            pair("distortion_noise_u", m -> {
                m.main.texture.texture = KilaTextures.RING;
                m.distortion.setEnable(true);
                m.distortion.texture.noise(KilaTexture.Noise.GRADIENT, 4);
                m.distortion.bidirectional = false;
                m.distortion.mask = KilaDistortion.Mask.U;
                m.distortion.amount.set(1, 0.5f);
                m.distortion.strength.value = 0.1f;
            }),
            pair("uv_effects", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.uvEffects.setEnable(true);
                m.uvEffects.twirl.value = 4;
                m.uvEffects.shear.set(0.5f, 0.2f);
                m.uvEffects.waveAmplitude.value = 0.05f;
                m.uvEffects.waveAxis = KilaUvEffects.WaveAxis.V;
                m.uvEffects.rippleAmplitude = 0.02f;
            }),
            pair("pixelate_boil", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CLOUD;
                m.main.texture.scroll(0.3f, 0);
                m.pixelate.setEnable(true);
                m.pixelate.pixels = 12;
                m.frameRate = 8;
                m.boil = 0.05f;
            }),
            new Case("fresnel_rim", true, m -> {
                m.fresnel.setEnable(true);
                m.fresnel.power = 0.5f;
                m.fresnel.offset.set(0, 0.6f, 0);
            }),
            new Case("fresnel_fade_edges", true, m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.fresnel.setEnable(true);
                m.fresnel.mode = KilaFresnel.Mode.FADE_EDGES;
                m.fresnel.power = 0.3f;
                m.fresnel.offset.set(0, -0.5f, 0);
            }),
            pair("color_adjust", m -> {
                m.main.texture.texture = KilaTextures.NOISE_PERLIN;
                m.color = new HDRColor(1f, 0.5f, 0.2f, 1f, 1f);
                m.colorAdjust.setEnable(true);
                m.colorAdjust.hue = 60;
                m.colorAdjust.saturation = 1.5f;
                m.colorAdjust.contrast = 1.2f;
                m.colorAdjust.brightness = 1.1f;
                m.colorAdjust.posterize = 5;
            }),
            pair("emission_clip", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CLOUD;
                m.emission = new HDRColor(0.2f, 0.1f, 0f, 1f, 1f);
                m.intensity.value = 2.5f;
                m.alphaClip = 0.4f;
                m.alphaSharpen = 0.5f;
                m.alpha.value = 0.8f;
            }),
            pair("additive", m -> {
                m.blend = BlendMode.Preset.ADDITIVE;
                m.color = new HDRColor(0.4f, 0.8f, 1f, 1f, 2f);
            }),
            pair("premultiplied", m -> {
                m.blend = BlendMode.Preset.PREMULTIPLIED;
                m.additive = 0.4f;
                m.color = new HDRColor(1f, 0.4f, 0.2f, 1f, 1.5f);
            }),
            pair("multiply_blend", m -> {
                m.blend = BlendMode.Preset.MULTIPLY;
                m.color = new HDRColor(0.2f, 0.5f, 1f, 1f, 1f);
            }),
            pair("opaque", m -> {
                m.blend = BlendMode.Preset.OPAQUE;
                m.main.texture.texture = KilaTextures.NOISE_PERLIN;
                m.doubleSided = true;
                m.backColor = new HDRColor(1f, 0.2f, 0.2f, 1f, 1f);
            }),
            pair("no_world_light_no_fog", m -> {
                m.worldLight = false;
                m.fog = false;
                m.main.texture.texture = KilaTextures.GLOW;
            }),
            pair("soft", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.depth.setEnable(true);
                m.depth.distance = 4;
                m.depth.power = 1.5f;
            }),
            new Case("camera_fade", true, m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.cameraFade.setEnable(true);
                m.cameraFade.near = 3;
                m.cameraFade.range = 4;
            }),
            pair("screen_distortion", m -> {
                m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
                m.color = new HDRColor(1f, 0.6f, 0.5f, 1f, 1f);
                m.screenDistortion.setEnable(true);
                m.screenDistortion.strength.value = 0.05f;
                m.screenDistortion.chromatic = 0.5f;
            }),
            pair("screen_noise", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.color = new HDRColor(0.6f, 0.9f, 1f, 1f, 1f);
                m.screenDistortion.setEnable(true);
                m.screenDistortion.keepForeground = false;
                m.screenDistortion.texture.noise(KilaTexture.Noise.SIMPLE, 10);
            }),
            pair("drivers", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.alpha.value = 0.5f;
                m.alpha.bind(KilaDriver.Source.LIFE, 10);
                m.intensity.bind(KilaDriver.Source.VERTEX_G, 0.5f);
                m.main.offsetU.bind(KilaDriver.Source.VERTEX_B, 0.3f);
                m.mask1.setEnable(true);
                m.mask1.offsetV.value = 0.2f;
            }),
            pair("lit", m -> m.lit.setEnable(true)),
            new Case("world_uv", true, m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.main.texture.uvSource = KilaTexture.UvSource.WORLD_XY;
                m.mask1.setEnable(true);
                m.mask1.texture.texture = KilaTextures.NOISE_PERLIN;
                m.mask1.texture.uvSource = KilaTexture.UvSource.WORLD_ZY;
                m.layer.setEnable(true);
                m.layer.texture.texture = KilaTextures.GRADIENT_LINEAR;
                m.layer.texture.uvSource = KilaTexture.UvSource.WORLD_XZ;
            }),
            new Case("screen_uv", true, m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.main.texture.uvSource = KilaTexture.UvSource.SCREEN;
                m.main.texture.tiling(3, 2);
            }),
            pair("slot_state", m -> m.slotRenderState = true),
            pair("flipbook", m -> {
                m.main.texture.texture = KilaTextures.SMOKE_FLIP;
                m.main.flipbook = true;
                m.main.fps = 9;
                m.main.startFrame = 3;
            }),
            pair("flipbook_motion", m -> {
                m.main.texture.texture = KilaTextures.SMOKE_FLIP;
                m.main.flipbook = true;
                m.main.fps = 7;
                m.main.frameBlend = true;
                m.main.motionVectors = true;
                m.main.motionTexture.texture = KilaTextures.SMOKE_FLIP_MV;
                m.main.motionStrength = 1.5f;
            }),
            pair("flipbook_driven_clamped", m -> {
                m.main.texture.texture = KilaTextures.SMOKE_BURST;
                m.main.texture.wrap(KilaTexture.Wrap.CLAMP, KilaTexture.Wrap.MIRROR);
                m.main.texture.tiling(1.3f, 1.3f);
                m.main.flipbook = true;
                m.main.columns = 8;
                m.main.rows = 4;
                m.main.frameSource = KilaMainTexture.FrameSource.DRIVER;
                m.main.frame.value = 0.3f;
                m.main.frame.bind(KilaDriver.Source.LIFE, 5);
                m.main.frameBlend = true;
            }),
            pair("dispersion", m -> {
                m.main.texture.texture = KilaTextures.RING;
                m.main.dispersion = true;
                m.main.dispersionAmount.value = 0.04f;
                m.main.dispersionDirection.set(1, 0.5f);
            }),
            pair("dispersion_radial_flipbook", m -> {
                m.main.texture.texture = KilaTextures.SMOKE_FLIP;
                m.main.flipbook = true;
                m.main.dispersion = true;
                m.main.dispersionRadial = true;
                m.main.dispersionAmount.value = 0.08f;
            }),
            pair("flow_map", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.distortion.setEnable(true);
                m.distortion.mode = KilaDistortion.Mode.FLOW_MAP;
                m.distortion.texture.texture = KilaTextures.FLOW_SWIRL;
                m.distortion.strength.value = 0.3f;
                m.distortion.flowSpeed = 0.7f;
                m.distortion.mask1 = true;
                m.mask1.setEnable(true);
                m.mask1.texture.noise(KilaTexture.Noise.GRADIENT, 5);
            }),
            pair("shading_sphere", m -> {
                m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
                m.shading.setEnable(true);
                m.shading.lightDirection.set(-0.6f, 0.8f, 0.2f);
                m.shading.lightColor = new HDRColor(1f, 0.9f, 0.7f, 1f, 1.5f);
            }),
            pair("shading_height_lit", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.shading.setEnable(true);
                m.shading.normal = KilaShading.Normal.MESH;
                m.shading.bump = KilaShading.Bump.HEIGHT;
                m.shading.bumpStrength = 4;
                m.lit.setEnable(true);
            }),
            pair("shading_normal_map", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.shading.setEnable(true);
                m.shading.bump = KilaShading.Bump.NORMAL_MAP;
                m.shading.flipGreen = true;
                m.shading.bumpStrength = 2;
                m.shading.wrap = 0.6f;
            }),
            pair("matcap", m -> {
                m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
                m.shading.setEnable(true);
                m.matcap.setEnable(true);
                m.matcap.blend = KilaMatcap.Blend.MULTIPLY;
                m.matcap.strength = 0.8f;
            }),
            pair("matcap_flat_add", m -> {
                m.main.texture.texture = KilaTextures.WHITE;
                m.color = new HDRColor(0.2f, 0.2f, 0.3f, 1f, 1f);
                m.matcap.setEnable(true);
            }),
            pair("vertex_offset", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.vertexOffset.setEnable(true);
                m.vertexOffset.direction = KilaVertexOffset.Direction.AXIS;
                m.vertexOffset.axis.set(0, 1, 0);
                m.vertexOffset.strength.value = 0.4f;
                m.vertexOffset.bidirectional = true;
            }),
            pair("vertex_offset_noise", m -> {
                m.main.texture.texture = KilaTextures.NOISE_CELLS;
                m.vertexOffset.setEnable(true);
                m.vertexOffset.direction = KilaVertexOffset.Direction.AXIS;
                m.vertexOffset.axis.set(1, 0.5f, 0);
                m.vertexOffset.texture.noise(KilaTexture.Noise.GRADIENT, 2);
                m.vertexOffset.strength.value = 0.5f;
                m.vertexOffset.edgeFade = 0.3f;
            }),
            pair("screen_normal", m -> {
                m.main.texture.texture = KilaTextures.SOFT_CIRCLE;
                m.color = new HDRColor(1f, 0.9f, 0.8f, 1f, 1f);
                m.shading.setEnable(true);
                m.screenDistortion.setEnable(true);
                m.screenDistortion.source = KilaScreenDistortion.Source.NORMAL;
                m.screenDistortion.strength.value = 0.08f;
            }));

    private static void layer(KilaMaterial m, KilaLayer.Blend blend, boolean color, boolean alpha, float strength) {
        m.main.texture.texture = KilaTextures.GLOW;
        m.layer.setEnable(true);
        m.layer.texture.texture = KilaTextures.NOISE_CELLS;
        m.layer.texture.scroll(0.1f, 0.05f);
        m.layer.blend = blend;
        m.layer.affectsColor = color;
        m.layer.affectsAlpha = alpha;
        m.layer.strength.value = strength;
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render").requiresWorld(true);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("every case and preset exports to a graph that compiles", KilaExportScenario::compiles)
                .step("an export keeps the render state and reports what it leaves out", KilaExportScenario::report)
                .server("pin the sun at noon", sc -> {
                    var level = sc.level();
                    DAY_TIME.set(level.getDayTime());
                    DAYLIGHT.set(level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
                    level.setDayTime(6000);
                })
                .server("a plain wall behind the pair", sc -> {
                    var player = sc.player();
                    var ahead = player.getDirection();
                    var right = ahead.getClockWise();
                    var base = player.blockPosition().relative(ahead, (int) DISTANCE + 3);
                    for (int across = -8; across <= 8; across++) {
                        for (int up = -2; up <= 6; up++) {
                            var pos = base.relative(right, across).above(up);
                            WALL.putIfAbsent(pos, sc.level().getBlockState(pos));
                            sc.level().setBlockAndUpdate(pos, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState());
                        }
                    }
                })
                .ticks(2)
                .waitUntil("noon reached the client", ctx -> ctx.mc().level != null
                        && Math.abs(ctx.mc().level.getDayTime() % 24000 - 6000) < 40)
                .waitUntil("the wall is drawn", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .step("look straight at the wall", ctx -> {
                    var player = ctx.requirePlayer();
                    float yaw = player.getDirection().toYRot();
                    player.setYRot(yaw);
                    player.yRotO = yaw;
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .ticks(10)
                .screenshot("empty")
                .step("an opaque pair, to find the two quads", ctx -> start(ctx, solid(), solid(), false))
                .ticks(6)
                .frames(2)
                .screenshot("reference_pair")
                .step("an opaque quad in the middle", ctx -> start(ctx, solid(), null, true))
                .ticks(6)
                .frames(2)
                .screenshot("reference_middle")
                .step("find the quads", KilaExportScenario::locate);
        for (var c : CASES) {
            if (c.still()) {
                s.step("kila " + c.id(), ctx -> start(ctx, c.create(), null, true))
                        .ticks(6)
                        .frames(2)
                        .screenshot(c.id() + "_kila")
                        .step("graph " + c.id(), ctx -> start(ctx, exported(ctx, c.id(), c.create(), null), null, true))
                        .ticks(6)
                        .frames(2)
                        .screenshot(c.id() + "_graph")
                        .step(c.id() + " draws the same", ctx -> compareStill(ctx, c.id()));
            } else {
                s.step("pair " + c.id(), ctx -> {
                            var kila = c.create();
                            start(ctx, kila, exported(ctx, c.id(), c.create(), null), false);
                        })
                        .ticks(6)
                        .frames(2)
                        .screenshot(c.id())
                        .step(c.id() + " draws the same", ctx -> comparePair(ctx, c.id(), c.id(), true));
            }
        }
        for (var preset : KilaPresets.ALL) {
            // a trail's point data reads 0 on a quad
            if (preset.target() == KilaPresets.Target.TRAIL || !comparable(preset.create())) continue;
            var id = "preset_" + preset.id();
            s.step("pair " + id, ctx -> start(ctx, preset.create(), exported(ctx, id, preset.create(), null), false))
                    .ticks(6)
                    .frames(2)
                    .screenshot(id)
                    .step(id + " draws the same", ctx -> comparePair(ctx, id, id, true));
        }
        s.step("pair the control", ctx -> start(ctx, CASES.getFirst().create(),
                        exported(ctx, "control", CASES.getFirst().create(), new HDRColor(0.3f, 1f, 0.3f, 1f, 1f)), false))
                .ticks(6)
                .frames(2)
                .screenshot("control")
                .step("a changed colour does not pass for the same", ctx -> comparePair(ctx, "control", "control", false))
                .step("stop", KilaExportScenario::stop)
                .openModularUI("the fire preset as a graph", ctx -> {
                    var view = new GraphEditorView(ShaderGraphResource.INSTANCE.getGraphViewFactory());
                    view.layout(layout -> {
                        layout.widthPercent(100);
                        layout.heightPercent(100);
                    });
                    view.loadGraph(KilaGraphExport.export(KilaPresets.FIRE.create()).graph(), tag -> {
                    });
                    ctx.put("graphEditor", view);
                    return new ModularUI(UI.of(view));
                })
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .frames(5)
                .step("no node of it sits on another", KilaExportScenario::checkLayout)
                .step("fit the graph in", ctx -> ctx.<GraphEditorView>get("graphEditor").getCurrentView().fitGraphChildren())
                .frames(5)
                .screenshot("fire_graph")
                .step("close the graph", ctx -> ctx.mc().setScreen(null))
                .teardown("take it down", ctx -> {
                    stop(ctx);
                    for (var file : FILES) {
                        ShaderGraphRuntime.invalidate(new FilePath(file));
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                    FILES.clear();
                })
                .teardownServer("put the world back", sc -> {
                    WALL.forEach((pos, state) -> sc.level().setBlockAndUpdate(pos, state));
                    WALL.clear();
                    if (DAY_TIME.get() == null) return;
                    sc.level().setDayTime(DAY_TIME.get());
                    sc.level().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(DAYLIGHT.get(), sc.level().getServer());
                    DAY_TIME.set(null);
                });
    }

    /** Exported whole, and the same on two bare particles: nothing per-particle random, no custom data. */
    private static boolean comparable(KilaMaterial material) {
        if (!KilaGraphExport.export(material).skipped().isEmpty() || material.usesCustomData()) return false;
        var random = new boolean[]{material.alpha.source == KilaDriver.Source.RANDOM
                || material.intensity.source == KilaDriver.Source.RANDOM};
        for (var module : material.modules()) {
            if (!module.isEnable()) continue;
            module.drivers((param, driver) -> random[0] |= driver.source == KilaDriver.Source.RANDOM);
        }
        return !random[0];
    }

    private static KilaMaterial solid() {
        var material = new KilaMaterial();
        material.main.texture.texture = KilaTextures.WHITE;
        material.blend = BlendMode.Preset.OPAQUE;
        material.color = new HDRColor(1f, 0.1f, 1f, 1f, 1f);
        material.worldLight = false;
        material.fog = false;
        return material;
    }

    // ---- compiling ---------------------------------------------------------------------------------

    private static void compiles(TestContext ctx) {
        var broken = new ArrayList<String>();
        var materials = new LinkedHashMap<String, KilaMaterial>();
        for (var c : CASES) materials.put(c.id(), c.create());
        for (var preset : KilaPresets.ALL) materials.put("preset " + preset.id(), preset.create());
        var contexts = List.of(MaterialContext.NORMAL, MaterialContext.PARTICLE_INSTANCE, MaterialContext.PARTICLE_MODEL_INSTANCE,
                MaterialContext.TRAIL_INSTANCE);
        for (var entry : materials.entrySet()) {
            KilaGraphExport.Result result;
            try {
                result = KilaGraphExport.export(entry.getValue());
            } catch (RuntimeException e) {
                broken.add(entry.getKey() + ": " + e);
                continue;
            }
            var compiled = new PhotonShaderCompiler(result.graph()).compile();
            if (compiled.hasStageErrors()) {
                broken.add(entry.getKey() + ": " + compiled.stageErrors().getFirst().message());
                continue;
            }
            var format = KGVertexFormat.of(compiled.settings().vertexFormatElements());
            for (var context : contexts) {
                var shader = ShaderGraphRuntime.createShaderInstance(compiled, format, context.getShaderDefines());
                if (shader == null) {
                    broken.add(entry.getKey() + " / " + (context.getVariantKey().isEmpty() ? "cpu" : context.getVariantKey()));
                } else {
                    shader.close();
                }
            }
            // what is saved is what compiles
            var reloaded = (ShaderGraph) ShaderGraphResource.INSTANCE.deserializeGraph(
                    ShaderGraphResource.INSTANCE.serializeGraph(result.graph()));
            var again = new PhotonShaderCompiler(reloaded).compile();
            if (again.hasStageErrors() || !again.fragmentSource().equals(compiled.fragmentSource())) {
                broken.add(entry.getKey() + ": compiles differently once saved and loaded");
            }
        }
        ctx.check("every export compiles and links", broken.isEmpty(), "none broken", broken);
    }

    private static void report(TestContext ctx) {
        var plain = KilaGraphExport.export(CASES.getFirst().create());
        ctx.check("a plain material leaves nothing out", plain.skipped().isEmpty(), "nothing", plain.skipped());
        ctx.check("and asks for its own blend", plain.renderState() != null
                && plain.renderState().blend() == BlendMode.Preset.ALPHA, "ALPHA", plain.renderState());
        var slot = KilaGraphExport.export(CASES.stream().filter(c -> c.id().equals("slot_state")).findFirst().orElseThrow().create());
        ctx.check("one that follows its slot asks for nothing", slot.renderState() == null, "null", slot.renderState());

        var full = new KilaMaterial();
        full.projection.setEnable(true);
        full.volume.setEnable(true);
        full.shading.setEnable(true);
        full.shading.model = KilaShading.Model.SIX_WAY;
        full.depth.setEnable(true);
        full.depth.contactWidth.value = 0.2f;
        full.cameraOffset = 1;
        var skipped = KilaGraphExport.export(full).skipped();
        var expected = List.of(full.projection.langKey(), full.volume.langKey(), "kila.export.skipped.six_way",
                "kila.export.skipped.contact_glow", "kila.export.skipped.camera_offset");
        ctx.check("what it can't draw is listed", skipped.containsAll(expected) && skipped.size() == expected.size(),
                expected, skipped);
        var crowded = new KilaMaterial();
        crowded.ramp.setEnable(true);
        for (int i = 0; i < 10; i++) crowded.ramp.gradient.addRGB(0.05f + i * 0.09f, i * 0.1f, 0.5f, 1f);
        ctx.check("and a gradient with more keys than a graph's", KilaGraphExport.export(crowded).skipped()
                .contains("kila.export.skipped.gradient_keys"), "listed", "missing");

        var exported = KilaGraphExport.export(CASES.get(1).create());
        var names = exported.graph().graphModel.getGraphVariableModels().stream()
                .map(declaration -> declaration.getName()).toList();
        ctx.check("the parameters are exposed variables", names.containsAll(List.of("Main Texture", "Main Tiling",
                "Main Rotation", "Color", "Intensity", "Alpha")), "main texture, tiling, rotation, colour ...", names);
    }

    // ---- drawing -----------------------------------------------------------------------------------

    /** The exported graph of {@code kila} written as a resource, as a material; {@code color} overrides its colour. */
    private static IMaterial exported(TestContext ctx, String id, KilaMaterial kila, @Nullable HDRColor color) {
        var result = KilaGraphExport.export(kila);
        var dir = new File(LDLib2.getAssetsDir(), "ldlib2/resources/global");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        var file = new File(dir, "uitest_kila_export_" + id + ShaderGraphResource.INSTANCE.getFileExtension());
        var tag = new CompoundTag();
        tag.put("data", ShaderGraphResource.INSTANCE.serializeGraph(result.graph()));
        tag.putString("type", ShaderGraphResource.INSTANCE.getName());
        try {
            NbtIo.write(tag, file.toPath());
        } catch (Exception e) {
            ctx.require("wrote " + file.getName(), false);
        }
        FILES.add(file);
        ShaderGraphRuntime.invalidate(new FilePath(file));
        var material = new ShaderGraphMaterial(new FilePath(file));
        material.setRenderState(result.renderState());
        if (color != null) {
            var values = new CompoundTag();
            var overrides = new CompoundTag();
            var typed = new CompoundTag();
            typed.putString("type", "hdr_color");
            typed.put("data", HDRColor.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, color).getOrThrow());
            overrides.put("Color", typed);
            values.put("overrides", overrides);
            var state = (CompoundTag) material.serializeAdditionalNBT(com.lowdragmc.lowdraglib2.Platform.getFrozenRegistry());
            if (state.contains("render_state")) values.put("render_state", state.get("render_state"));
            material.deserializeAdditionalNBT(values, com.lowdragmc.lowdraglib2.Platform.getFrozenRegistry());
        }
        return material;
    }

    /** {@code right} null draws only {@code left}, in the middle. */
    private static void start(TestContext ctx, IMaterial left, @Nullable IMaterial right, boolean middle) {
        stop(ctx);
        var player = ctx.requirePlayer();
        var ahead = player.getDirection();
        var side = ahead.getClockWise();
        var executors = new ArrayList<BlockEffectExecutor>();
        var materials = right == null ? List.of(left) : List.of(left, right);
        for (int i = 0; i < materials.size(); i++) {
            double across = middle ? 0 : (i == 0 ? -APART : APART);
            var fx = new FX();
            fx.getFxData().objects().add(quad(materials.get(i)));
            var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition());
            // at eye height; the executor centres on the block
            double up = player.getEyeY() - player.blockPosition().getY() - 0.5;
            executor.setOffset(ahead.getStepX() * DISTANCE + side.getStepX() * across, up,
                    ahead.getStepZ() * DISTANCE + side.getStepZ() * across);
            executor.setAllowMulti(true);
            executor.start();
            executors.add(executor);
        }
        ctx.put("executors", executors);
    }

    private static ParticleEmitter quad(IMaterial material) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setStartColor(NumberFunction.color(START_COLOR));
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
        return emitter;
    }

    private static void stop(TestContext ctx) {
        List<BlockEffectExecutor> executors = ctx.get("executors");
        if (executors != null) {
            for (var executor : executors) {
                if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
            }
        }
        ctx.state().remove("executors");
    }

    /** The editor's own sizes, at the positions the export laid out. */
    private static void checkLayout(TestContext ctx) {
        var boxes = new ArrayList<float[]>();
        var names = new ArrayList<String>();
        for (var ref : ctx.query().type(NodeElement.class).list()) {
            var element = (NodeElement) ref.element();
            if (element instanceof BlockNodeElement) continue;
            var at = element.getModel().getPosition();
            boxes.add(new float[]{at.x, at.y, at.x + element.getSizeWidth(), at.y + element.getSizeHeight()});
            names.add(element.getModel().getTitle().getString());
        }
        ctx.require("the graph's nodes are drawn", boxes.size() > 20);
        var overlaps = new ArrayList<String>();
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                var a = boxes.get(i);
                var b = boxes.get(j);
                if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]) overlaps.add(names.get(i) + " / " + names.get(j));
            }
        }
        ctx.check("no two nodes overlap", overlaps.isEmpty(), "none", overlaps);
    }

    // ---- comparing ---------------------------------------------------------------------------------

    private static ScreenshotCompare capture(TestContext ctx, String name) {
        var capture = ScreenshotCompare.load(ctx, name);
        ctx.require("the " + name + " capture exists", capture != null);
        return capture;
    }

    private static void locate(TestContext ctx) {
        var empty = capture(ctx, "empty");
        var pair = capture(ctx, "reference_pair");
        var middle = capture(ctx, "reference_middle");
        var whole = empty.whole();
        int half = whole.right() / 2;
        var left = pair.diff(empty, 40, new ScreenshotCompare.Region(0, 0, half, whole.bottom())).changed();
        var right = pair.diff(empty, 40, new ScreenshotCompare.Region(half, 0, whole.right(), whole.bottom())).changed();
        var alone = middle.diff(empty, 40, whole).changed();
        ctx.require("both quads of the pair are on screen", left != null && right != null);
        ctx.require("the middle quad is on screen", alone != null);
        ctx.check("the two quads are the same size", Math.abs(width(left) - width(right)) <= 2
                && Math.abs(height(left) - height(right)) <= 2, width(left) + "x" + height(left),
                width(right) + "x" + height(right));
        ctx.check("and big enough to compare", width(left) > GRID * 8 && height(left) > GRID * 8, "> " + GRID * 8 + " px",
                width(left) + "x" + height(left));
        ctx.put("left", left);
        ctx.put("right", right);
        ctx.put("middle", alone);
    }

    private static int width(ScreenshotCompare.Region region) {
        return region.right() - region.left();
    }

    private static int height(ScreenshotCompare.Region region) {
        return region.bottom() - region.top();
    }

    private static void comparePair(TestContext ctx, String shot, String label, boolean same) {
        var picture = capture(ctx, shot);
        ScreenshotCompare.Region left = ctx.get("left");
        ScreenshotCompare.Region right = ctx.get("right");
        compare(ctx, label, capture(ctx, "empty"), picture, left, picture, right, same);
    }

    private static void compareStill(TestContext ctx, String id) {
        ScreenshotCompare.Region middle = ctx.get("middle");
        compare(ctx, id, capture(ctx, "empty"), capture(ctx, id + "_kila"), middle, capture(ctx, id + "_graph"), middle, true);
    }

    private static void compare(TestContext ctx, String label, ScreenshotCompare empty, ScreenshotCompare kila,
                                ScreenshotCompare.Region kilaBox, ScreenshotCompare graph, ScreenshotCompare.Region graphBox,
                                boolean same) {
        var drawn = kila.diff(empty, 8, kilaBox);
        ctx.check(label + ": Kila draws something", drawn.count() > width(kilaBox) * height(kilaBox) / 20,
                "> 5% of the quad", drawn.count() + " px");
        double worst = 0;
        String where = "";
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                var a = kila.mean(cell(kilaBox, column, row));
                var b = graph.mean(cell(graphBox, column, row));
                for (int channel = 0; channel < 3; channel++) {
                    double difference = Math.abs(a[channel] - b[channel]);
                    if (difference > worst) {
                        worst = difference;
                        where = "cell " + column + "," + row + " kila " + rgb(a) + " graph " + rgb(b);
                    }
                }
            }
        }
        if (same) {
            ctx.check(label + ": the graph draws the same", worst <= TOLERANCE, "<= " + TOLERANCE,
                    "%.1f at %s".formatted(worst, where));
        } else {
            ctx.check(label + ": the graph does not draw the same", worst > TOLERANCE * 4, "> " + TOLERANCE * 4,
                    "%.1f at %s".formatted(worst, where));
        }
    }

    /** One cell of the grid, a pixel in from its edges. */
    private static ScreenshotCompare.Region cell(ScreenshotCompare.Region box, int column, int row) {
        int w = width(box);
        int h = height(box);
        return new ScreenshotCompare.Region(box.left() + w * column / GRID + 1, box.top() + h * row / GRID + 1,
                box.left() + w * (column + 1) / GRID - 1, box.top() + h * (row + 1) / GRID - 1);
    }

    private static String rgb(double[] c) {
        return "(%.0f, %.0f, %.0f)".formatted(c[0], c[1], c[2]);
    }
}
