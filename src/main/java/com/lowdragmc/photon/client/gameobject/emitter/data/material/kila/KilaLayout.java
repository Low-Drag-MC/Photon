package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one KilaMaterial configuration compiles to: the defines selecting its program variant, the textures it
 * samples, and the values its modules read — which make up the variant's {@link KilaBlock}, with what they start
 * from.
 */
public final class KilaLayout {
    /**
     * Samplers a variant may use, the lightmap included: 1.21's limit (12..15 were Photon's buffer textures), so what
     * fitted then fits now.
     */
    public static final int SAMPLER_BUDGET = 12;
    /** ⚠️ MIRRORED IN {@code photon:kila_lib.glsl} — KilaTex0..KilaTex8 are declared there. */
    public static final int MAX_TEXTURES = 9;
    public static final String BASE_DEFINE = "KILA";
    /** The light list, cluster lists and visibility maps a lit variant reads — see {@link PhotonPipelines#declareLights}. */
    private static final List<String> LIGHT_SAMPLERS = List.of(PhotonPipelines.LIGHT_DATA, PhotonPipelines.CLUSTER_GRID,
            PhotonPipelines.CLUSTER_INDICES, PhotonPipelines.VISIBILITY_ATLAS);

    /** A value a module reads: a member of the variant's {@link KilaBlock}, and what it starts from. */
    public record Uniform(String name, KilaBlock.Type type, float[] values) {
    }

    /** A physical sampler {@code KilaTex<index>}: a file texture, or a texture made at runtime. */
    public record Texture(int index, @Nullable KilaTexture texture, @Nullable KilaBakedGradient dynamic) {
        public String name() {
            return "KilaTex" + index;
        }
    }

    /** The variant's identity: every define, sorted. */
    public final String key;
    public final Set<String> defines;
    /** Every value the variant reads, the drivable parameter groups' included. */
    public final List<Uniform> uniforms;
    /** {@link #uniforms} as the program declares them. */
    public final KilaBlock block;
    /** Every sampler the variant counts against {@link #SAMPLER_BUDGET}, by name. */
    public final List<String> samplers;
    public final List<Texture> textures;
    /** The scene captures it reads ({@code SamplerSceneColor}/{@code SamplerSceneDepth}), which the drain binds. */
    public final List<String> sceneSamplers;
    /** Modules left out of this variant because they did not fit the sampler budget. */
    public final List<KilaModule> dropped;
    /** Which {@code KILA_P<group>} parameter groups the variant carries. */
    public final boolean[] groups;
    /** Whether a slot maps in world space. */
    public final boolean world;
    /** Whether the dynamic lights reach it. */
    public final boolean lit;

    private KilaLayout(Builder builder, List<KilaModule> dropped) {
        var defines = new TreeSet<>(builder.defines);
        defines.add(BASE_DEFINE);
        var uniforms = new ArrayList<>(builder.uniforms.values());
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            if (!builder.groups[group]) continue;
            // a group is base + scale * source, resolved per vertex — kila.vsh reads these under KILA_P<group>
            defines.add("KILA_P" + group);
            uniforms.add(new Uniform("KilaBase" + group, KilaBlock.Type.VEC4, new float[4]));
            uniforms.add(new Uniform("KilaScale" + group, KilaBlock.Type.VEC4, new float[4]));
            uniforms.add(new Uniform("KilaSrc" + group, KilaBlock.Type.IVEC4, new float[4]));
        }
        this.block = new KilaBlock(uniforms.stream().map(u -> new KilaBlock.Member(u.name(), u.type())).toList());
        // part of the variant's identity like any define: two variants reading different values are different programs
        defines.add(block.define());
        var samplers = new ArrayList<String>();
        samplers.add("Sampler2");
        for (var texture : builder.textures) {
            defines.add("KILA_TEX" + texture.index());
            samplers.add(texture.name());
        }
        defines.addAll(builder.aliases);
        samplers.addAll(builder.sceneSamplers);
        if (builder.lights) samplers.addAll(LIGHT_SAMPLERS);
        this.defines = Collections.unmodifiableSet(defines);
        this.key = String.join("|", defines);
        this.uniforms = List.copyOf(uniforms);
        this.samplers = List.copyOf(samplers);
        this.textures = List.copyOf(builder.textures);
        this.sceneSamplers = List.copyOf(builder.sceneSamplers);
        this.dropped = List.copyOf(dropped);
        this.groups = builder.groups.clone();
        this.world = defines.contains("KILA_WORLD_UV");
        this.lit = builder.lights;
    }

    /** Whether the variant reads the scene depth, which is what the fade's {@code PHOTON_SOFT} half declares. */
    public boolean readsSceneDepth() {
        return sceneSamplers.contains(PhotonShaderCompiler.SCENE_DEPTH);
    }

    /** Write every default of this layout into {@code values}. */
    public void seed(KilaValues values) {
        for (var uniform : uniforms) values.set(uniform.name(), uniform.values());
    }

    /** A builder already holding what every variant declares: the surface. */
    public static Builder surface() {
        return new Builder()
                // below 0 normally; the thumbnail feeds it to every particle-data source, so it animates
                .float1("KilaPreview", -1)
                // rgb = HDR colour (intensity premultiplied), a = alpha
                .vec4("KilaTint", 1, 1, 1, 1)
                .vec4("KilaBackTint", 1, 1, 1, 1)
                .vec4("KilaEmission")
                // x = alpha clip, y = alpha sharpen, z = world light, w = fog
                .vec4("KilaSurface", 0.01f, 1, 1, 1)
                // x = vertex colour mode, y = BlendMode.Preset ordinal — ⚠️ both MIRRORED IN kila.fsh
                .ivec4("KilaSurfaceModes")
                // x = how additive the premultiplied preset is
                .vec4("KilaOutput")
                // x = what to show instead of the result — ⚠️ DebugView ordinals MIRRORED IN kila.fsh (0 = the result)
                .ivec4("KilaDebug")
                .params(KilaParam.ALPHA, KilaParam.INTENSITY);
    }

    public static final class Builder {
        private final TreeSet<String> defines = new TreeSet<>();
        private final Map<String, Uniform> uniforms = new LinkedHashMap<>();
        private final List<String> sceneSamplers = new ArrayList<>();
        private final List<Texture> textures = new ArrayList<>();
        private final List<String> aliases = new ArrayList<>();
        private final boolean[] groups = new boolean[KilaParam.GROUPS];
        private boolean lights;

        public Builder define(String define) {
            defines.add(define);
            return this;
        }

        public Builder vec4(String name, float x, float y, float z, float w) {
            return uniform(name, KilaBlock.Type.VEC4, new float[]{x, y, z, w});
        }

        public Builder vec4(String name) {
            return vec4(name, 0, 0, 0, 0);
        }

        public Builder ivec4(String name) {
            return uniform(name, KilaBlock.Type.IVEC4, new float[4]);
        }

        public Builder float1(String name, float value) {
            return uniform(name, KilaBlock.Type.FLOAT, new float[]{value});
        }

        /** The first declaration wins; modules sharing a value (a slot's uv chain, say) must agree on its type. */
        private Builder uniform(String name, KilaBlock.Type type, float[] values) {
            var existing = uniforms.putIfAbsent(name, new Uniform(name, type, values));
            if (existing != null && existing.type() != type) {
                throw new IllegalStateException(name + " declared as both " + existing.type() + " and " + type);
            }
            return this;
        }

        /** The {@code <prefix>ST}, {@code Anim}, {@code Polar} and {@code Opts} a slot's uv chain reads. */
        public Builder uvChain(String prefix) {
            vec4(prefix + "ST", 1, 1, 0, 0);
            vec4(prefix + "Anim");
            vec4(prefix + "Polar", 0.5f, 0.5f, 1, 1);
            ivec4(prefix + "Opts");
            return this;
        }

        /** A scene capture the drain binds. */
        public Builder sampler(String name) {
            if (!sceneSamplers.contains(name)) sceneSamplers.add(name);
            return this;
        }

        /** The dynamic lights: {@code KILA_LIT}, and the four samplers they take. */
        public Builder lights() {
            lights = true;
            return define("KILA_LIT");
        }

        public Builder params(KilaParam... params) {
            for (var param : params) groups[param.group] = true;
            return this;
        }

        /** A slot that maps in world space. */
        public Builder world() {
            return define("KILA_WORLD_UV");
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
                    aliases.add(alias(slot, existing));
                    return this;
                }
            }
            var physical = new Texture(textures.size(), texture, null);
            textures.add(physical);
            aliases.add(alias(slot, physical));
            return this;
        }

        /** A texture made at runtime (a baked gradient): never shared. */
        public Builder dynamicTexture(String slot, KilaBakedGradient texture) {
            var physical = new Texture(textures.size(), null, texture);
            textures.add(physical);
            aliases.add(alias(slot, physical));
            return this;
        }

        /**
         * {@code #define KILA_TEX_<slot> KilaTex<n>}. 26.1's pipeline builder takes flags and numbers, not names,
         * but writes a flag out as {@code #define <flag>} — so a flag holding a space defines a name.
         */
        private static String alias(String slot, Texture texture) {
            return "KILA_TEX_" + slot + " " + texture.name();
        }

        int samplerCount() {
            return 1 + textures.size() + sceneSamplers.size() + (lights ? LIGHT_SAMPLERS.size() : 0);
        }

        private Snapshot mark() {
            return new Snapshot(new TreeSet<>(defines), new LinkedHashMap<>(uniforms), sceneSamplers.size(),
                    textures.size(), aliases.size(), groups.clone(), lights);
        }

        private void reset(Snapshot snapshot) {
            defines.clear();
            defines.addAll(snapshot.defines());
            uniforms.clear();
            uniforms.putAll(snapshot.uniforms());
            sceneSamplers.subList(snapshot.sceneSamplers(), sceneSamplers.size()).clear();
            textures.subList(snapshot.textures(), textures.size()).clear();
            aliases.subList(snapshot.aliases(), aliases.size()).clear();
            System.arraycopy(snapshot.groups(), 0, groups, 0, groups.length);
            lights = snapshot.lights();
        }

        private record Snapshot(TreeSet<String> defines, Map<String, Uniform> uniforms, int sceneSamplers,
                                int textures, int aliases, boolean[] groups, boolean lights) {
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
