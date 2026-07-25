package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One post-processing window: while active, the referenced effect (a render graph, or a bare
 * fullscreen graph via the single-pass adapter) is requested every frame. The request weight and
 * every parameter override are {@link NumberFunction}s sampled at the normalized clip progress
 * {@code t = localTime / duration} (0..1) — a curve over the clip IS the fade envelope, the
 * parameter animation, everything.
 */
public class PostProcessClip extends Clip {

    /** How the sampled channel Numbers convert to the schema parameter's Java type at submit.
     *  {@code SAMPLER} is the odd one out: it carries no functions (a texture can't interpolate),
     *  just a static {@link RenderTypeGraphTypes.Sampler2DValue}. */
    public enum ParamKind {
        FLOAT, INT, BOOL, COLOR, VEC2, VEC3, VEC4, SAMPLER;

        /** CustomData-style: vectors are one scalar function per component, the rest are single;
         *  a sampler has no sampling channels. */
        public int channelCount() {
            return switch (this) {
                case VEC2 -> 2;
                case VEC3 -> 3;
                case VEC4 -> 4;
                case SAMPLER -> 0;
                default -> 1;
            };
        }
    }

    /** One parameter override: {@code channelCount} sampling functions plus the conversion kind
     *  (fixed by the effect schema when the editor creates the row). For {@code SAMPLER} the
     *  functions are empty and {@link #sampler} holds the chosen texture. */
    public record ParamOverride(ParamKind kind, List<NumberFunction> channels,
                                @Nullable RenderTypeGraphTypes.Sampler2DValue sampler) {
        /** The scalar/vector/color kinds (no sampler payload). */
        public ParamOverride(ParamKind kind, List<NumberFunction> channels) {
            this(kind, channels, null);
        }

        /** A SAMPLER override — static, no sampling functions. */
        public static ParamOverride sampler(RenderTypeGraphTypes.Sampler2DValue value) {
            return new ParamOverride(ParamKind.SAMPLER, List.of(), value);
        }

        public ParamOverride copy() {
            var copied = new ArrayList<NumberFunction>(channels.size());
            for (var fn : channels) copied.add(fn.copy());
            return new ParamOverride(kind, copied, sampler);
        }
    }

    /** The effect's resource path in {@code type(path)} string form ("" = none selected). */
    private String effect = "";
    /** Request weight over the clip, sampled at normalized clip progress (clamped to 0..1). */
    private NumberFunction weight = NumberFunction.constant(1);
    /** Parameter overrides keyed by the effect's schema param name, sampled like {@link #weight}. */
    private final Map<String, ParamOverride> params = new LinkedHashMap<>();
    /** CustomMask culling: off = fullscreen; on = apply only where the named mask group is set
     *  ("" = any group). Submitted as the reserved MaskFilter param (a group-name string). */
    private boolean maskCulling = false;
    private String maskGroup = "";
    /** Run this clip's request as its OWN fullscreen execution instead of merging with other
     *  requests of the same effect (the reserved Independent param) — keeps this clip's params,
     *  weight and mask filter fully separate, at the cost of an extra execution. */
    private boolean independent = false;

    // runtime
    @Nullable
    private transient IResourcePath parsedPath;
    @Nullable
    private transient String parsedFrom;
    public PostProcessClip() {
        super();
    }

    public PostProcessClip(double start, double duration, float speed) {
        super(start, duration, speed);
    }

    public String effect() {
        return effect;
    }

    public PostProcessClip effect(String effect) {
        this.effect = effect == null ? "" : effect;
        return this;
    }

    public NumberFunction weight() {
        return weight;
    }

    public PostProcessClip weight(NumberFunction weight) {
        this.weight = weight == null ? NumberFunction.constant(1) : weight;
        return this;
    }

    /** The live override map — the clip editor adds/removes entries directly. */
    public Map<String, ParamOverride> params() {
        return params;
    }

    public boolean maskCulling() {
        return maskCulling;
    }

    public PostProcessClip maskCulling(boolean maskCulling) {
        this.maskCulling = maskCulling;
        return this;
    }

    public String maskGroup() {
        return maskGroup;
    }

    public PostProcessClip maskGroup(String maskGroup) {
        this.maskGroup = maskGroup == null ? "" : maskGroup;
        return this;
    }

    public boolean independent() {
        return independent;
    }

    public PostProcessClip independent(boolean independent) {
        this.independent = independent;
        return this;
    }

    /** The parsed effect path (cached until the string changes), or null when unset/invalid. */
    @Nullable
    public IResourcePath effectPath() {
        if (effect.isEmpty()) return null;
        if (!effect.equals(parsedFrom)) {
            parsedFrom = effect;
            parsedPath = IResourcePath.parse(effect);
        }
        return parsedPath;
    }

    /** The request weight at clip-local time: the weight function sampled at clip progress. */
    public float weightAt(double localTime) {
        return Math.clamp(weight.get(progress(localTime), this::lerpValue).floatValue(), 0f, 1f);
    }

    /** Sample every parameter override at clip progress into schema-typed values (FLOAT→Float,
     *  INT→rounded Integer, BOOL→sample≥0.5, COLOR→ARGB Integer, VEC2/3/4→per-channel Vector),
     *  plus the reserved MaskFilter param when mask culling is on. */
    public Map<String, Object> sampleParams(double localTime) {
        if (params.isEmpty() && !maskCulling && !independent) return Map.of();
        float t = progress(localTime);
        var result = new LinkedHashMap<String, Object>();
        if (maskCulling) {
            result.put(com.lowdragmc.photon.client.postfx.runtime.CompiledEffect.MASK_FILTER_PARAM,
                    maskGroup);
        }
        if (independent) {
            result.put(com.lowdragmc.photon.client.postfx.runtime.CompiledEffect.INDEPENDENT_PARAM,
                    Boolean.TRUE);
        }
        params.forEach((name, override) -> result.put(name, switch (override.kind()) {
            case FLOAT -> channel(override, 0, t).floatValue();
            case INT -> Math.round(channel(override, 0, t).floatValue());
            case BOOL -> channel(override, 0, t).floatValue() >= 0.5f;
            case COLOR -> channel(override, 0, t).intValue();
            case VEC2 -> new Vector2f(
                    channel(override, 0, t).floatValue(), channel(override, 1, t).floatValue());
            case VEC3 -> new Vector3f(
                    channel(override, 0, t).floatValue(), channel(override, 1, t).floatValue(),
                    channel(override, 2, t).floatValue());
            case VEC4 -> new Vector4f(
                    channel(override, 0, t).floatValue(), channel(override, 1, t).floatValue(),
                    channel(override, 2, t).floatValue(), channel(override, 3, t).floatValue());
            case SAMPLER -> override.sampler() != null ? override.sampler()
                    : RenderTypeGraphTypes.Sampler2DValue.defaultValue();
        }));
        return result;
    }

    private Number channel(ParamOverride override, int index, float t) {
        if (index >= override.channels().size()) return 0f;
        return override.channels().get(index).get(t, this::lerpValue);
    }

    @Override
    public PostProcessClip copy() {
        var clip = new PostProcessClip(start(), duration(), speed());
        clip.targetId(targetId()).seed(seed()).randomSeed(randomSeed());
        clip.effect = effect;
        clip.weight = weight.copy();
        clip.maskCulling = maskCulling;
        clip.maskGroup = maskGroup;
        clip.independent = independent;
        params.forEach((name, override) -> clip.params.put(name, override.copy()));
        return clip;
    }
}
