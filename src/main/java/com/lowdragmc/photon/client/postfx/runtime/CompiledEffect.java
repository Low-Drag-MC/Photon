package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.client.postfx.graph.SizeSpec;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One effect, compiled to a flat execution plan: topologically ordered fullscreen passes over
 * transient resources with first/last-use lifetimes (the pool aliases inside those windows), plus the
 * effect's parameter schema (blackboard defaults + lerpability) for weight blending. Phase 2's render
 * graph compiler produces these; until then {@link #singlePass} adapts a bare fullscreen graph into a
 * one-pass effect.
 *
 * <p>Pass values bind explicitly: {@link ValueBinding.Constant} (an inline value authored on the
 * pass node), {@link ValueBinding.ParamRef} (a blackboard parameter of the effect, blended across
 * requests by weight), or {@link ValueBinding.EffectWeight} (the blended request weight itself,
 * wired from the render graph's Effect Weight node). The executor's auto final mix uses the same
 * weight regardless of whether any pass reads it.</p>
 */
@OnlyIn(Dist.CLIENT)
public record CompiledEffect(
        IResourcePath source,
        int priority,
        boolean autoBlend,
        List<ParamSpec> schema,
        List<ResourceDesc> resources,
        List<CompiledPass> passes,
        int outputResource) {

    /** In a BARE fullscreen graph played directly (the {@link #singlePass} adapter — there is no
     *  render graph to wire the Effect Weight node in), a float variable with this exact name
     *  receives the blended request weight. Render-graph effects wire the node explicitly instead;
     *  their pass graphs treat "Weight" as an ordinary variable. */
    public static final String WEIGHT_PARAM = "Weight";

    /** The default priority of a bare fullscreen graph played as an effect (after builtin bloom = 0). */
    public static final int DEFAULT_PRIORITY = 100;

    /** One blendable effect parameter: the graph variable's display name, its declared default, and
     *  whether requests lerp it by weight (floats/vectors/colors) or take the highest-weight value. */
    public record ParamSpec(String name, @Nullable Object defaultValue, boolean lerpable) {}

    /** One transient render target: sizing rule + format + the pass-index window it must live for. */
    public record ResourceDesc(SizeSpec size, TargetFormat format, int firstUsePass, int lastUsePass,
                               String debugName) {}

    /** What a pass's texture input binds to. */
    public record ResourceRef(Source source, int resource) {
        public enum Source { SCENE_COLOR, SCENE_DEPTH, RESOURCE }

        public static final ResourceRef SCENE_COLOR_REF = new ResourceRef(Source.SCENE_COLOR, -1);
        public static final ResourceRef SCENE_DEPTH_REF = new ResourceRef(Source.SCENE_DEPTH, -1);

        public static ResourceRef of(int resource) {
            return new ResourceRef(Source.RESOURCE, resource);
        }
    }

    /** How a pass's exposed-variable value is sourced at execution. */
    public sealed interface ValueBinding {
        /** An inline value authored on the pass node. */
        record Constant(Object value) implements ValueBinding {}

        /** A blackboard parameter of the effect — blended across requests by weight. */
        record ParamRef(String schemaParam) implements ValueBinding {}

        /** The effect's blended request weight (0..1) itself — the Effect Weight node's output. */
        record EffectWeight() implements ValueBinding {
            public static final EffectWeight INSTANCE = new EffectWeight();
        }
    }

    /**
     * One fullscreen dispatch: its source is EITHER a pass graph (resolved through
     * {@code FullscreenGraphRuntime} at execution, so edits stay live) OR a hand-written core
     * shader (resolved through {@code CustomShaderPass}); texture bindings are keyed by
     * <b>sampler uniform name</b>, value bindings by <b>variable/uniform display name</b>.
     */
    public record CompiledPass(
            @Nullable IResourcePath graphPath,
            @Nullable String customShader,
            Map<String, ResourceRef> textures,
            Map<String, ValueBinding> params,
            int outputResource) {}

    /**
     * Adapt one fullscreen graph into a single-pass effect: scene color feeds the graph's
     * {@code Input} sampler variable (or its alphabetically-first one), the pass writes a full-screen
     * target that becomes the chain output. Returns null when the graph is broken.
     */
    @Nullable
    public static CompiledEffect singlePass(IResourcePath graphPath, FullscreenGraphRuntime.Entry entry) {
        var compiled = entry.getCompiled();
        if (compiled == null) return null;

        var textures = new LinkedHashMap<String, ResourceRef>();
        // display name -> sampler uniform; prefer the starter graph's "Input", else deterministic first
        var samplers = new TreeMap<>(compiled.variableSamplers());
        String inputName = samplers.containsKey(FullscreenShaderGraph.DEFAULT_INPUT)
                ? FullscreenShaderGraph.DEFAULT_INPUT
                : (samplers.isEmpty() ? null : samplers.firstKey());
        if (inputName != null) {
            textures.put(samplers.get(inputName), ResourceRef.SCENE_COLOR_REF);
        }

        // every exposed uniform IS an effect parameter here (schema names == variable names),
        // except WEIGHT_PARAM: with no render graph to wire the Effect Weight node in, a variable
        // by that name binds the request weight directly (and stays out of the blendable schema)
        var schema = new ArrayList<ParamSpec>();
        var params = new LinkedHashMap<String, ValueBinding>();
        for (var spec : buildSchema(entry)) {
            if (WEIGHT_PARAM.equals(spec.name())) {
                params.put(spec.name(), ValueBinding.EffectWeight.INSTANCE);
            } else {
                schema.add(spec);
                params.put(spec.name(), new ValueBinding.ParamRef(spec.name()));
            }
        }

        return new CompiledEffect(
                graphPath,
                DEFAULT_PRIORITY,
                true,
                List.copyOf(schema),
                List.of(new ResourceDesc(SizeSpec.screen(1f), TargetFormat.RGBA16F, 0, 0, "output")),
                List.of(new CompiledPass(graphPath, null, textures, params, 0)),
                0);
    }

    /** The schema defaults as an executor-ready params map (what an unblended invocation uses —
     *  e.g. the editor preview, which runs at weight 1 with no requests). */
    public Map<String, Object> defaultParams() {
        if (schema.isEmpty()) return Map.of();
        var params = new LinkedHashMap<String, Object>();
        for (var spec : schema) {
            if (spec.defaultValue() != null) params.put(spec.name(), spec.defaultValue());
        }
        return params;
    }

    /** The blendable parameter schema: every graph variable that compiled to a uniform field,
     *  lerpable when its GLSL type interpolates componentwise. */
    static List<ParamSpec> buildSchema(FullscreenGraphRuntime.Entry entry) {
        var graph = entry.getGraph();
        var compiled = entry.getCompiled();
        if (graph == null || compiled == null) return List.of();
        var schema = new ArrayList<ParamSpec>();
        for (var declaration : graph.graphModel.getGraphVariableModels()) {
            if (declaration == null) continue;
            var name = declaration.getName();
            var field = compiled.uniformFields().get(name);
            if (field == null) continue; // samplers / inlined constants aren't blendable params
            var defaultValue = declaration.tryGetDefaultValue(declaration.getDataType()).result().orElse(null);
            var lerpable = field.type() == GlslType.FLOAT || field.type() == GlslType.VEC2
                    || field.type() == GlslType.VEC3 || field.type() == GlslType.VEC4;
            schema.add(new ParamSpec(name, defaultValue, lerpable));
        }
        return List.copyOf(schema);
    }
}
