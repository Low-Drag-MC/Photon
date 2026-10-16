package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntSupplier;

/**
 * What one KilaMaterial configuration compiles to: the defines selecting its program variant, and exactly the
 * uniforms and samplers it uses (Minecraft warns about, and renumbers samplers after, every unused one).
 */
@OnlyIn(Dist.CLIENT)
public final class KilaLayout {
    /** Units 0..11: 12..15 are Photon's buffer textures (see {@code InstancedRenderBackend}). */
    public static final int SAMPLER_BUDGET = 12;
    /** ⚠️ MIRRORED IN {@code photon:kila_lib.glsl} — KilaTex0..KilaTex8 are declared there. */
    public static final int MAX_TEXTURES = 9;
    public static final String PROGRAM = "photon:kila";
    public static final String BASE_DEFINE = "KILA";

    public record Uniform(String name, String type, int count, float[] values) {
    }

    /** A physical sampler {@code KilaTex<index>}: a file texture, or a texture made at runtime. */
    public record Texture(int index, @Nullable KilaTexture texture, @Nullable IntSupplier dynamic) {
        public String name() {
            return "KilaTex" + index;
        }
    }

    /** The variant's identity: every define, sorted. */
    public final String key;
    public final Set<String> defines;
    public final List<Uniform> uniforms;
    public final List<String> samplers;
    public final List<Texture> textures;
    /** Modules left out of this variant because they did not fit the sampler budget. */
    public final List<KilaModule> dropped;
    /** Which {@code KILA_P<group>} parameter groups the variant carries. */
    public final boolean[] groups;
    /** Whether a slot maps in world space, which reads {@code KilaCameraPos}. */
    public final boolean world;
    // per render path, so a draw does not build the cache key string
    private final Map<MaterialContext, KilaShaders.Variant> variants = new HashMap<>();
    private int variantsGeneration = -1;

    private KilaLayout(Builder builder, List<KilaModule> dropped) {
        var defines = new TreeSet<>(builder.defines);
        defines.add(BASE_DEFINE);
        var uniforms = new ArrayList<>(builder.uniforms.values());
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            if (!builder.groups[group]) continue;
            defines.add("KILA_P" + group);
            uniforms.add(new Uniform("KilaBase" + group, "float", 4, new float[4]));
            uniforms.add(new Uniform("KilaScale" + group, "float", 4, new float[4]));
            uniforms.add(new Uniform("KilaSrc" + group, "int", 4, new float[4]));
        }
        var samplers = new ArrayList<String>();
        samplers.add("Sampler2");
        for (var texture : builder.textures) {
            defines.add("KILA_TEX" + texture.index());
            samplers.add(texture.name());
        }
        defines.addAll(builder.aliases);
        samplers.addAll(builder.systemSamplers);
        this.defines = Collections.unmodifiableSet(defines);
        this.key = String.join("|", defines);
        this.uniforms = List.copyOf(uniforms);
        this.samplers = List.copyOf(samplers);
        this.textures = List.copyOf(builder.textures);
        this.dropped = List.copyOf(dropped);
        this.groups = builder.groups.clone();
        this.world = defines.contains("KILA_WORLD_UV");
    }

    @Nullable
    public KilaShaders.Variant variant(MaterialContext context) {
        if (variantsGeneration != KilaShaders.generation()) {
            variants.clear();
            variantsGeneration = KilaShaders.generation();
        }
        var variant = variants.get(context);
        if (variant == null) {
            variant = KilaShaders.get(this, context);
            if (variant != null) variants.put(context, variant);
        }
        return variant;
    }

    /** A builder already holding what every variant declares: the vanilla uniforms and the surface. */
    public static Builder surface() {
        return new Builder().mat4("ModelViewMat").mat4("ProjMat").vec4("ColorModulator", 1, 1, 1, 1)
                .float1("FogStart", 0).float1("FogEnd", 1).vec4("FogColor").int1("FogShape", 0)
                .float1("KilaPreview", -1)
                .vec4("KilaTint", 1, 1, 1, 1).vec4("KilaBackTint", 1, 1, 1, 1).vec4("KilaEmission")
                .vec4("KilaSurface", 0.01f, 1, 1, 1).ivec4("KilaSurfaceModes").vec4("KilaOutput").ivec4("KilaDebug")
                .params(KilaParam.ALPHA, KilaParam.INTENSITY);
    }

    public String json() {
        var root = new JsonObject();
        root.addProperty("vertex", PROGRAM);
        root.addProperty("fragment", PROGRAM);
        var samplerArray = new JsonArray();
        for (var sampler : samplers) {
            var entry = new JsonObject();
            entry.addProperty("name", sampler);
            samplerArray.add(entry);
        }
        root.add("samplers", samplerArray);
        var uniformArray = new JsonArray();
        for (var uniform : uniforms) {
            var entry = new JsonObject();
            entry.addProperty("name", uniform.name());
            entry.addProperty("type", uniform.type());
            entry.addProperty("count", uniform.count());
            var values = new JsonArray();
            for (var value : uniform.values()) values.add(value);
            entry.add("values", values);
            uniformArray.add(entry);
        }
        root.add("uniforms", uniformArray);
        return root.toString();
    }

    public static final class Builder {
        private final TreeSet<String> defines = new TreeSet<>();
        private final Map<String, Uniform> uniforms = new LinkedHashMap<>();
        private final List<String> systemSamplers = new ArrayList<>();
        private final List<Texture> textures = new ArrayList<>();
        private final List<String> aliases = new ArrayList<>();
        private final boolean[] groups = new boolean[KilaParam.GROUPS];

        public Builder define(String define) {
            defines.add(define);
            return this;
        }

        public Builder vec4(String name, float x, float y, float z, float w) {
            uniforms.putIfAbsent(name, new Uniform(name, "float", 4, new float[]{x, y, z, w}));
            return this;
        }

        public Builder vec4(String name) {
            return vec4(name, 0, 0, 0, 0);
        }

        public Builder vec3(String name) {
            uniforms.putIfAbsent(name, new Uniform(name, "float", 3, new float[3]));
            return this;
        }

        public Builder vec2(String name) {
            uniforms.putIfAbsent(name, new Uniform(name, "float", 2, new float[2]));
            return this;
        }

        public Builder ivec4(String name) {
            uniforms.putIfAbsent(name, new Uniform(name, "int", 4, new float[4]));
            return this;
        }

        public Builder float1(String name, float value) {
            uniforms.putIfAbsent(name, new Uniform(name, "float", 1, new float[]{value}));
            return this;
        }

        public Builder int1(String name, int value) {
            uniforms.putIfAbsent(name, new Uniform(name, "int", 1, new float[]{value}));
            return this;
        }

        public Builder mat4(String name) {
            var identity = new float[16];
            identity[0] = identity[5] = identity[10] = identity[15] = 1;
            uniforms.putIfAbsent(name, new Uniform(name, "matrix4x4", 16, identity));
            return this;
        }

        /** The {@code <prefix>ST}, {@code Anim}, {@code Polar} and {@code Opts} a slot's uv chain reads. */
        public Builder uvChain(String prefix) {
            float1("GameTime", 0);
            vec4(prefix + "ST", 1, 1, 0, 0);
            vec4(prefix + "Anim");
            vec4(prefix + "Polar", 0.5f, 0.5f, 1, 1);
            ivec4(prefix + "Opts");
            return this;
        }

        /** A fragment-stage slot: it can map in screen space, which reads {@code ScreenSize}. */
        public Builder fragmentUvChain(String prefix) {
            vec2("ScreenSize");
            return uvChain(prefix);
        }

        public Builder sampler(String name) {
            if (!systemSamplers.contains(name)) systemSamplers.add(name);
            return this;
        }

        public Builder params(KilaParam... params) {
            for (var param : params) groups[param.group] = true;
            return this;
        }

        /** The camera position a world-space slot adds back to the camera-relative vertex. */
        public Builder world() {
            return define("KILA_WORLD_UV").vec3("KilaCameraPos");
        }

        /**
         * A slot read as one value (or two): procedural is {@code KILA_<slot>_NOISE} + {@code <prefix>Noise} and
         * no sampler; a texture adds {@code <prefix>Channel} when {@code channel} is set.
         */
        public Builder source(String slot, String prefix, KilaTexture texture, boolean channel) {
            if (texture.isProcedural()) {
                if (texture.usesWorld()) world();
                return define("KILA_" + slot + "_NOISE").vec4(prefix + "Noise", 1, 8, 1, 0);
            }
            if (channel) vec4(prefix + "Channel", 1, 0, 0, 0);
            return texture(slot, texture);
        }

        /** Binds slot {@code KILA_TEX_<slot>} to a physical sampler, sharing one that samples the same way. */
        public Builder texture(String slot, KilaTexture texture) {
            if (texture.usesWorld()) world();
            for (var existing : textures) {
                if (existing.texture() != null && existing.texture().sameSampler(texture)) {
                    aliases.add("KILA_TEX_" + slot + " " + existing.name());
                    return this;
                }
            }
            var physical = new Texture(textures.size(), texture, null);
            textures.add(physical);
            aliases.add("KILA_TEX_" + slot + " " + physical.name());
            return this;
        }

        /** A texture made at runtime (the baked ramp): never shared. */
        public Builder dynamicTexture(String slot, IntSupplier texture) {
            var physical = new Texture(textures.size(), null, texture);
            textures.add(physical);
            aliases.add("KILA_TEX_" + slot + " " + physical.name());
            return this;
        }

        int samplerCount() {
            return 1 + textures.size() + systemSamplers.size();
        }

        private Snapshot mark() {
            return new Snapshot(new TreeSet<>(defines), new LinkedHashMap<>(uniforms), systemSamplers.size(),
                    textures.size(), aliases.size(), groups.clone());
        }

        private void reset(Snapshot snapshot) {
            defines.clear();
            defines.addAll(snapshot.defines());
            uniforms.clear();
            uniforms.putAll(snapshot.uniforms());
            systemSamplers.subList(snapshot.systemSamplers(), systemSamplers.size()).clear();
            textures.subList(snapshot.textures(), textures.size()).clear();
            aliases.subList(snapshot.aliases(), aliases.size()).clear();
            System.arraycopy(snapshot.groups(), 0, groups, 0, groups.length);
        }

        private record Snapshot(TreeSet<String> defines, Map<String, Uniform> uniforms, int systemSamplers,
                                int textures, int aliases, boolean[] groups) {
        }

        /** Declare every module in order; one that would overflow the samplers is left out, and reported. */
        public KilaLayout build(List<KilaModule> modules) {
            var dropped = new ArrayList<KilaModule>();
            for (var module : modules) {
                var snapshot = mark();
                module.declare(this);
                if (samplerCount() > SAMPLER_BUDGET || textures.size() > MAX_TEXTURES) {
                    reset(snapshot);
                    dropped.add(module);
                }
            }
            return new KilaLayout(this, dropped);
        }
    }
}
