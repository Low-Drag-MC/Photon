package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The dynamic-light passes, each declaring exactly what its shader declares: Vulkan keeps a declaration the program
 * never reads, so a pass importing {@code photon:dynamic_light.glsl} declares — and binds — the whole light list even
 * where it only reads the block. The passes into Photon's own targets have their formats; the composite and the haze
 * draw onto whatever frame is being lit, so they are built per format.
 */
final class LightPipelines {
    static final String VIEW_BLOCK = "PhotonLightView";
    static final String STEP_BLOCK = "PhotonLightStep";
    static final String SCENE_DEPTH = "SceneDepthSampler";
    static final String SCENE_COLOR = "SceneColorSampler";
    static final String LIGHTMAP = "LightmapSampler";
    static final String IRRADIANCE = "IrradianceSampler";
    static final String VOLUME = "VolumeSampler";
    static final String VOXEL_PAGES = "VoxelPages";
    static final String VOXEL_BRICKS = "VoxelBricks";
    static final String VISIBILITY_LIGHTS = "VisibilityLights";
    static final String VOLUME_LIGHT_DATA = "VolumeLightData";
    static final String VOLUME_TILE_GRID = "VolumeTileGrid";
    static final String VOLUME_TILE_INDICES = "VolumeTileIndices";
    static final String FOG_DATA = "FogData";
    static final String DEPTH = "DepthSampler";

    /** Light, blur and volume buffers. */
    static final GpuFormat BUFFER_FORMAT = PhotonPipelines.HDR_FORMAT;
    /** A visibility map texel is how far the light gets, in blocks. */
    static final GpuFormat VISIBILITY_FORMAT = GpuFormat.R16_FLOAT;

    static final RenderPipeline VISIBILITY = build("visibility", opaque(VISIBILITY_FORMAT), layout -> layout
            .texel(VOXEL_PAGES).sampler(VOXEL_BRICKS).texel(VISIBILITY_LIGHTS));

    static final RenderPipeline PASS = build("pass", opaque(BUFFER_FORMAT), layout -> lights(layout)
            .sampler(SCENE_DEPTH).sampler(LIGHTMAP).texel(VOXEL_PAGES).sampler(VOXEL_BRICKS));

    static final RenderPipeline BLUR = build("blur", opaque(BUFFER_FORMAT), layout -> layout
            .sampler(IRRADIANCE).sampler(SCENE_DEPTH));

    static final RenderPipeline VOLUME_PASS = build("volume", opaque(BUFFER_FORMAT), layout -> lights(layout)
            .sampler(SCENE_DEPTH).texel(VOLUME_LIGHT_DATA).texel(VOLUME_TILE_GRID).texel(VOLUME_TILE_INDICES));

    /** rgb adds to the haze, alpha multiplies into the transmittance. */
    static final RenderPipeline FOG = build("fog", new ColorTargetState(Optional.of(new BlendFunction(
                    new BlendEquation(BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD),
                    new BlendEquation(BlendFactor.ZERO, BlendFactor.SRC_ALPHA, BlendOp.ADD))),
                    BUFFER_FORMAT, ColorTargetState.WRITE_ALL),
            layout -> lights(layout).sampler(SCENE_DEPTH).texel(FOG_DATA));

    /** Depth only, always written: the colour attachment is there because a pass needs one. */
    private static final Map<GpuFormat, RenderPipeline> DEPTH_SCALE = new ConcurrentHashMap<>();
    private static final Map<GpuFormat, RenderPipeline> COMPOSITE = new ConcurrentHashMap<>();
    private static final Map<GpuFormat, RenderPipeline> HAZE_ADD = new ConcurrentHashMap<>();
    private static final Map<GpuFormat, RenderPipeline> HAZE_ABSORB = new ConcurrentHashMap<>();

    private LightPipelines() {
    }

    /** Over the frame's own colour, which keeps its alpha. */
    static RenderPipeline composite(GpuFormat format) {
        return COMPOSITE.computeIfAbsent(format, f -> build("composite_" + name(f),
                new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_COLOR), "dynamic_light_composite",
                layout -> layout.uniform("Fog").sampler(SCENE_COLOR).sampler(SCENE_DEPTH).sampler(IRRADIANCE)
                        .sampler(LIGHTMAP)));
    }

    /** The soft add {@code 1 - (1 - frame) * exp(-haze)}, without reading the frame back. */
    static RenderPipeline hazeAdd(GpuFormat format) {
        return HAZE_ADD.computeIfAbsent(format, f -> build("haze_add_" + name(f), new ColorTargetState(Optional.of(
                        new BlendFunction(new BlendEquation(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_COLOR, BlendOp.ADD),
                                new BlendEquation(BlendFactor.ZERO, BlendFactor.ONE, BlendOp.ADD))),
                        f, ColorTargetState.WRITE_COLOR), "dynamic_light_haze",
                layout -> layout.sampler(SCENE_DEPTH).sampler(VOLUME)));
    }

    /** The frame times the fog's transmittance. */
    static RenderPipeline hazeAbsorb(GpuFormat format) {
        return HAZE_ABSORB.computeIfAbsent(format, f -> build("haze_absorb_" + name(f), new ColorTargetState(Optional.of(
                        new BlendFunction(new BlendEquation(BlendFactor.ZERO, BlendFactor.SRC_COLOR, BlendOp.ADD),
                                new BlendEquation(BlendFactor.ZERO, BlendFactor.ONE, BlendOp.ADD))),
                        f, ColorTargetState.WRITE_COLOR), "dynamic_light_haze",
                layout -> layout.sampler(SCENE_DEPTH).sampler(VOLUME)));
    }

    /** Writes depth only, into a target paired with a colour attachment of {@code colorFormat}. */
    static RenderPipeline depthScale(GpuFormat colorFormat) {
        return DEPTH_SCALE.computeIfAbsent(colorFormat, f -> {
            var builder = base("depth_scale_" + name(f), new ColorTargetState(Optional.empty(), f,
                    ColorTargetState.WRITE_NONE), "dynamic_light_depth_scale")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true));
            var layout = viewLayout().sampler(DEPTH);
            layout.applyTo(builder);
            return builder.build();
        });
    }

    private static String name(GpuFormat format) {
        return format.name().toLowerCase(Locale.ROOT);
    }

    private static ColorTargetState opaque(GpuFormat format) {
        return new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL);
    }

    private static RenderPipeline build(String name, ColorTargetState color,
                                        Function<PhotonPipelines.LayoutBuilder, PhotonPipelines.LayoutBuilder> resources) {
        return build(name, color, "dynamic_light_" + name, resources);
    }

    private static RenderPipeline build(String name, ColorTargetState color, String shader,
                                        Function<PhotonPipelines.LayoutBuilder, PhotonPipelines.LayoutBuilder> resources) {
        var builder = base(name, color, shader);
        resources.apply(viewLayout()).applyTo(builder);
        return builder.build();
    }

    private static RenderPipeline.Builder base(String name, ColorTargetState color, String shader) {
        return PhotonFullscreenPass.builder(Photon.id("core/" + shader))
                .withLocation(Photon.id("pipeline/dynamic_light_" + name))
                .withColorTargetState(color);
    }

    /** The two blocks of {@code photon:dynamic_light_frame.glsl}, which every pass imports. */
    private static PhotonPipelines.LayoutBuilder viewLayout() {
        return PhotonPipelines.LayoutBuilder.standalone().uniform(VIEW_BLOCK).uniform(STEP_BLOCK);
    }

    /** What {@code photon:dynamic_light.glsl} declares; see {@code DynamicLightRenderer.bindLightList}. */
    private static PhotonPipelines.LayoutBuilder lights(PhotonPipelines.LayoutBuilder layout) {
        return layout.texel(PhotonPipelines.LIGHT_DATA).texel(PhotonPipelines.CLUSTER_GRID)
                .texel(PhotonPipelines.CLUSTER_INDICES).sampler(PhotonPipelines.VISIBILITY_ATLAS)
                .uniform(PhotonPipelines.LIGHTS_BLOCK);
    }
}
