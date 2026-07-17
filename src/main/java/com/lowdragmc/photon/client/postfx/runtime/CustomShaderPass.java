package com.lowdragmc.photon.client.postfx.runtime;

import com.google.gson.JsonObject;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hand-written pass source: a vanilla core shader trio (json + vsh + fsh) played as an effect
 * pass. The pass interface is introspected from the shader JSON — every sampler becomes a TEXTURE
 * port, every float/vec uniform (minus the engine-managed names) becomes a value port — so a
 * builtin or resource-pack shader needs zero Java registration. Shaders draw the same fullscreen
 * POSITION quad as graph passes and share the {@code ldlib2:fast_blit} vertex stage
 * ({@code texCoord} = 0..1).
 *
 * <p>Instances and parsed infos are cached per location string; both caches drop on resource
 * reload (hooked from {@code PhotonShaders.registerShaders}). Failures memo-log once. Render
 * thread only.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CustomShaderPass {

    /** Uniforms the executor manages — never exposed as pass ports. */
    private static final List<String> ENGINE_UNIFORMS = List.of(
            "ScreenSize", "GameTime", "ProjMat", "ModelViewMat");

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
            "photon:postfx/dof_composite");

    /** One introspected uniform: {@code count} 1..4 maps to FLOAT/VEC2/VEC3/VEC4 ports. */
    public record UniformSpec(String name, int count, float[] defaults) {}

    /** The pass interface parsed from the shader json. */
    public record Info(List<String> samplers, List<UniformSpec> uniforms) {}

    private record ShaderEntry(@Nullable ShaderInstance shader) {}
    private record InfoEntry(@Nullable Info info) {}

    private static final Map<String, ShaderEntry> SHADERS = new HashMap<>();
    private static final Map<String, InfoEntry> INFOS = new HashMap<>();

    private CustomShaderPass() {}

    /** Drop every cached shader/info — resource reload re-resolves everything lazily. */
    public static void clearAll() {
        SHADERS.values().forEach(entry -> {
            if (entry.shader() != null) entry.shader().close();
        });
        SHADERS.clear();
        INFOS.clear();
    }

    /** The parsed pass interface of {@code location} ("ns:path"), or null when missing/broken. */
    @Nullable
    public static Info getInfo(String location) {
        var cached = INFOS.get(location);
        if (cached != null) return cached.info();
        Info info = null;
        try {
            var rl = ResourceLocation.parse(location);
            var jsonPath = ResourceLocation.fromNamespaceAndPath(rl.getNamespace(),
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

    private static Info parse(JsonObject json) {
        var samplers = new ArrayList<String>();
        for (var element : GsonHelper.getAsJsonArray(json, "samplers", new com.google.gson.JsonArray())) {
            var name = GsonHelper.getAsString(element.getAsJsonObject(), "name");
            samplers.add(name);
        }
        var uniforms = new ArrayList<UniformSpec>();
        for (var element : GsonHelper.getAsJsonArray(json, "uniforms", new com.google.gson.JsonArray())) {
            var uniform = element.getAsJsonObject();
            var name = GsonHelper.getAsString(uniform, "name");
            if (ENGINE_UNIFORMS.contains(name) || name.endsWith("_TexelSize")) continue;
            if (!"float".equals(GsonHelper.getAsString(uniform, "type", "float"))) continue;
            int count = GsonHelper.getAsInt(uniform, "count", 1);
            if (count < 1 || count > 4) continue;
            var defaults = new float[count];
            var values = GsonHelper.getAsJsonArray(uniform, "values", new com.google.gson.JsonArray());
            for (int i = 0; i < count && i < values.size(); i++) {
                defaults[i] = values.get(i).getAsFloat();
            }
            uniforms.add(new UniformSpec(name, count, defaults));
        }
        return new Info(List.copyOf(samplers), List.copyOf(uniforms));
    }

    /** The loaded shader of {@code location}, or null when it failed to compile (logged once). */
    @Nullable
    public static ShaderInstance getShader(String location) {
        var cached = SHADERS.get(location);
        if (cached != null) return cached.shader();
        ShaderInstance shader = null;
        try {
            shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                    ResourceLocation.parse(location), DefaultVertexFormat.POSITION);
        } catch (Exception e) {
            Photon.LOGGER.error("custom pass shader '{}' failed to load: {}", location, e.toString());
        }
        SHADERS.put(location, new ShaderEntry(shader));
        return shader;
    }
}
