package com.lowdragmc.photon.client.postfx.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.shadergraph.PhotonFullscreenCompiler;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The hand-written pass source: a core shader pair (json + fsh) played as an effect pass. The pass
 * interface is introspected from the shader JSON — every sampler becomes a TEXTURE port, every
 * float/vec uniform (minus the engine-managed names) becomes a value port — so a builtin or
 * resource-pack shader needs zero Java registration. Shaders draw the same fullscreen POSITION quad
 * as graph passes and share the {@link PhotonFullscreenPass#VERTEX_SHADER_PATH} vertex stage
 * ({@code texCoord} = 0..1).
 *
 * <p><b>26.1 uniform transport:</b> loose uniforms are gone, so a pass shader declares its values in
 * one {@code layout(std140) uniform PhotonPass} block whose members are the JSON's {@code uniforms}
 * entries <b>in declaration order</b> — including the engine-managed ones, which occupy their slot but
 * are not offered as ports. The JSON stays the single source of truth for both the block layout
 * ({@link Info#layout()}) and the node's ports ({@link Info#uniforms()}); {@code PostFXShaderAssetsTest}
 * fails the build if a shipped {@code .fsh} block and its JSON ever drift.</p>
 *
 * <p>Pipelines, uniform buffers and parsed infos are cached per location string and dropped on
 * resource reload. Failures memo-log once. Render thread only.</p>
 */
public final class CustomShaderPass {

    /** Uniforms the executor manages — they occupy their {@code PhotonPass} slot but are never
     *  exposed as pass ports (the executor writes them, not the author). */
    private static final List<String> ENGINE_UNIFORMS = List.of(
            "ScreenSize", "GameTime",
            // the drawing view's depth range — what turns the raw depth buffer into distances
            "ZNear", "ZFar");

    /** The shipped pass library (shaders/core/postfx/*) — offered by the pass node's shader
     *  selector; any other core-shader location still works via the free-text row. */
    public static final List<String> BUILTIN_SHADERS = List.of(
            "photon:postfx/grayscale",
            "photon:postfx/sepia",
            "photon:postfx/brightness_contrast",
            "photon:postfx/hue_saturation",
            "photon:postfx/vignette",
            "photon:postfx/rgb_shift",
            "photon:postfx/pixelate",
            "photon:postfx/dot_screen",
            "photon:postfx/film",
            "photon:postfx/glitch",
            "photon:postfx/blur_h",
            "photon:postfx/blur_v",
            "photon:postfx/outline",
            "photon:postfx/tint",
            "photon:postfx/sharpen",
            "photon:postfx/posterize",
            "photon:postfx/radial_blur",
            "photon:postfx/lens_distortion",
            "photon:postfx/bright",
            "photon:postfx/add_mix",
            "photon:postfx/dof_composite",
            "photon:postfx/show_mask",
            "photon:postfx/mask_outline");

    /** One introspected uniform: {@code count} 1..4 maps to FLOAT/VEC2/VEC3/VEC4 ports. */
    public record UniformSpec(String name, int count, float[] defaults) {}

    /**
     * The pass interface parsed from the shader json.
     *
     * @param layout   every uniform, in JSON declaration order — the {@code PhotonPass} block layout
     * @param uniforms the subset offered as value ports (engine-managed names and the
     *                 {@code _TexelSize} companions excluded — the executor supplies those)
     */
    public record Info(List<String> samplers, List<UniformSpec> layout, List<UniformSpec> uniforms) {}

    /** A pass shader ready to dispatch: its pipeline plus the value block it binds. */
    public record Pass(RenderPipeline pipeline, Info info, PassUniforms uniforms) {}

    private record InfoEntry(@Nullable Info info) {}

    private static final Map<String, InfoEntry> INFOS = new HashMap<>();
    private static final Map<String, Pass> PASSES = new HashMap<>();
    private static final AtomicInteger PIPELINE_ID =
            new AtomicInteger();

    private CustomShaderPass() {}

    /** Drop every cached info and pass — resource reload re-resolves everything lazily. The GL
     *  pipelines themselves are freed with the device's pipeline cache on reload; only our uniform
     *  buffers need closing. */
    public static void clearAll() {
        INFOS.clear();
        PASSES.values().forEach(pass -> pass.uniforms().close());
        PASSES.clear();
    }

    /** The dispatchable form of {@code location} ("ns:path"), or null when missing/broken. */
    @Nullable
    public static Pass get(String location) {
        var cached = PASSES.get(location);
        if (cached != null) return cached;
        var info = getInfo(location);
        if (info == null) return null;
        var rl = Identifier.parse(location);
        var builder = PhotonFullscreenPass.builder(
                        Identifier.fromNamespaceAndPath(rl.getNamespace(), "core/" + rl.getPath()))
                .withLocation(Photon.id("pipeline/postfx_" + PIPELINE_ID.getAndIncrement()));
        info.samplers().forEach(builder::withSampler);
        if (!info.layout().isEmpty()) {
            builder.withUniform(PassUniforms.BLOCK_NAME, UniformType.UNIFORM_BUFFER);
        }
        var pipeline = builder.build();
        // validate here rather than at setPipeline: a broken shader inside an open pass throws and takes
        // the frame with it, whereas a null Pass just skips the effect (chain passthrough, logged once)
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
            Photon.LOGGER.warn("post-effect pass shader '{}' failed to compile (must be 26.1-format GLSL: "
                    + "values in a std140 PhotonPass block)", location);
            INFOS.put(location, new InfoEntry(null)); // stop re-resolving it every frame
            return null;
        }
        var pass = new Pass(pipeline, info, new PassUniforms(info.layout()));
        PASSES.put(location, pass);
        return pass;
    }

    /** The parsed pass interface of {@code location} ("ns:path"), or null when missing/broken. */
    @Nullable
    public static Info getInfo(String location) {
        var cached = INFOS.get(location);
        if (cached != null) return cached.info();
        Info info = null;
        try {
            var rl = Identifier.parse(location);
            var jsonPath = Identifier.fromNamespaceAndPath(rl.getNamespace(),
                    "shaders/core/" + rl.getPath() + ".json");
            var resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(jsonPath);
            try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                info = parse(GsonHelper.parse(reader));
            }
        } catch (Exception e) {
            Photon.LOGGER.warn("custom pass shader '{}' has no readable definition: {}", location, e.toString());
        }
        INFOS.put(location, new InfoEntry(info));
        return info;
    }

    /** Whether {@code name} is offered as an authorable port (vs. written by the executor). */
    private static boolean isPort(String name) {
        return !ENGINE_UNIFORMS.contains(name) && !name.endsWith(PhotonFullscreenCompiler.TEXEL_SIZE_SUFFIX);
    }

    static Info parse(JsonObject json) {
        var samplers = new ArrayList<String>();
        for (var element : GsonHelper.getAsJsonArray(json, "samplers", new JsonArray())) {
            var name = GsonHelper.getAsString(element.getAsJsonObject(), "name");
            samplers.add(name);
        }
        var layout = new ArrayList<UniformSpec>();
        var ports = new ArrayList<UniformSpec>();
        for (var element : GsonHelper.getAsJsonArray(json, "uniforms", new JsonArray())) {
            var uniform = element.getAsJsonObject();
            var name = GsonHelper.getAsString(uniform, "name");
            // only the std140 scalar/vector subset can live in the PhotonPass block; anything else
            // would silently shift every following member's offset, so it is dropped from BOTH lists
            if (!"float".equals(GsonHelper.getAsString(uniform, "type", "float"))) continue;
            int count = GsonHelper.getAsInt(uniform, "count", 1);
            if (count < 1 || count > 4) continue;
            var defaults = new float[count];
            var values = GsonHelper.getAsJsonArray(uniform, "values", new JsonArray());
            for (int i = 0; i < count && i < values.size(); i++) {
                defaults[i] = values.get(i).getAsFloat();
            }
            var spec = new UniformSpec(name, count, defaults);
            layout.add(spec);
            if (isPort(name)) ports.add(spec);
        }
        return new Info(List.copyOf(samplers), List.copyOf(layout), List.copyOf(ports));
    }
}
