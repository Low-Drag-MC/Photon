package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;

import java.util.Optional;

/**
 * The dynamic-light passes as 26.1 pipelines, each declaring exactly what its shader reads — 26.1 fails a draw that leaves a declared uniform
 * unbound, and warns about a declared sampler the program does not use. The two blocks every pass imports
 * ({@code photon:dynamic_light_frame.glsl}) are declared on all of them: a block the program does not use is
 * ignored without a word.
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

    static final RenderPipeline VISIBILITY = base("visibility", ColorTargetState.DEFAULT)
            .withUniform(VOXEL_PAGES, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
            .withSampler(VOXEL_BRICKS)
            .withUniform(VISIBILITY_LIGHTS, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
            .build();

    static final RenderPipeline PASS = lights(base("pass", ColorTargetState.DEFAULT))
            .withSampler(SCENE_DEPTH)
            .withSampler(LIGHTMAP)
            .withUniform(VOXEL_PAGES, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
            .withSampler(VOXEL_BRICKS)
            .build();

    static final RenderPipeline BLUR = base("blur", ColorTargetState.DEFAULT)
            .withSampler(IRRADIANCE)
            .withSampler(SCENE_DEPTH)
            .build();

    static final RenderPipeline VOLUME_PASS = base("volume", ColorTargetState.DEFAULT)
            .withUniform(PhotonPipelines.LIGHTS_BLOCK, UniformType.UNIFORM_BUFFER)
            .withSampler(SCENE_DEPTH)
            .withUniform(VOLUME_LIGHT_DATA, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
            .withUniform(VOLUME_TILE_GRID, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
            .withUniform(VOLUME_TILE_INDICES, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
            .withSampler(PhotonPipelines.VISIBILITY_ATLAS)
            .build();

    /** rgb adds to the haze, alpha multiplies into the transmittance */
    static final RenderPipeline FOG = lights(base("fog", new ColorTargetState(new BlendFunction(
            SourceFactor.ONE, DestFactor.ONE, SourceFactor.ZERO, DestFactor.SRC_ALPHA))))
            .withSampler(PhotonPipelines.VISIBILITY_ATLAS)
            .withSampler(SCENE_DEPTH)
            .withUniform(FOG_DATA, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
            .build();

    /** Over the frame's own colour, which keeps its alpha. */
    static final RenderPipeline COMPOSITE = base("composite",
            new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_COLOR))
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withSampler(SCENE_COLOR)
            .withSampler(SCENE_DEPTH)
            .withSampler(IRRADIANCE)
            .withSampler(LIGHTMAP)
            .build();

    /** The soft add {@code 1 - (1 - frame) * exp(-haze)}, without reading the frame back. */
    static final RenderPipeline HAZE_ADD = base("haze_add", new ColorTargetState(Optional.of(
            new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_COLOR, SourceFactor.ZERO, DestFactor.ONE)),
            ColorTargetState.WRITE_COLOR), "dynamic_light_haze")
            .withSampler(SCENE_DEPTH)
            .withSampler(VOLUME)
            .build();

    /** The frame times the fog's transmittance. */
    static final RenderPipeline HAZE_ABSORB = base("haze_absorb", new ColorTargetState(Optional.of(
            new BlendFunction(SourceFactor.ZERO, DestFactor.SRC_COLOR, SourceFactor.ZERO, DestFactor.ONE)),
            ColorTargetState.WRITE_COLOR), "dynamic_light_haze")
            .withSampler(SCENE_DEPTH)
            .withSampler(VOLUME)
            .build();

    /** Depth only, always written: the colour attachment is there because a pass needs one. */
    static final RenderPipeline DEPTH_SCALE = base("depth_scale",
            new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
            .withSampler(DEPTH)
            .build();

    private LightPipelines() {
    }

    private static RenderPipeline.Builder base(String name, ColorTargetState color) {
        return base(name, color, "dynamic_light_" + name);
    }

    private static RenderPipeline.Builder base(String name, ColorTargetState color, String shader) {
        return PhotonFullscreenPass.builder(Photon.id("core/" + shader))
                .withLocation(Photon.id("pipeline/dynamic_light_" + name))
                .withColorTargetState(color)
                .withUniform(VIEW_BLOCK, UniformType.UNIFORM_BUFFER)
                .withUniform(STEP_BLOCK, UniformType.UNIFORM_BUFFER);
    }

    /** What {@code photon:dynamic_light.glsl}'s light list and clusters read; the visibility atlas is added by the
     *  passes that sample it, so the light pass, which does not, does not declare it. */
    private static RenderPipeline.Builder lights(RenderPipeline.Builder builder) {
        return builder.withUniform(PhotonPipelines.LIGHT_DATA, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
                .withUniform(PhotonPipelines.CLUSTER_GRID, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
                .withUniform(PhotonPipelines.CLUSTER_INDICES, UniformType.TEXEL_BUFFER, TextureFormat.RGBA8)
                .withUniform(PhotonPipelines.LIGHTS_BLOCK, UniformType.UNIFORM_BUFFER);
    }
}
