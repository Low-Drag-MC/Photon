package com.lowdragmc.photon.client.postfx.shadergraph.runtime;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeFactory;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResource;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.IGraphReferenceResolver;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The shared compile cache for {@link FullscreenShaderGraph} resources — the fullscreen twin of
 * {@code ShaderGraphRuntime}: one {@link Entry} per graph resource path holding the compiled GLSL and
 * the single lazily-built GL program (fullscreen passes have no instancing variants). Every effect pass
 * referencing the same graph shares one entry; the executor stages its own values before each dispatch.
 *
 * <p>Staleness is detected by tag identity — the stored {@link CompoundTag} instance is replaced when
 * the resource is saved in the editor or reloaded from a pack. Render thread only.</p>
 */
public final class FullscreenGraphRuntime {

    private static final Map<IResourcePath, Entry> CACHE = new HashMap<>();

    private FullscreenGraphRuntime() {}

    /** One compiled fullscreen graph + its GL program. Closed (program freed) when its source changes. */
    public static final class Entry {
        /** The source tag this entry was compiled from — identity marker for staleness detection. */
        final CompoundTag sourceTag;
        @Getter
        @Nullable
        private final FullscreenShaderGraph graph;
        @Getter
        @Nullable
        private final CompiledShaderGraph compiled;
        @Getter
        private final String errorMessage;

        /** The KilaGraph material: this graph's uniform UBO + sampler bindings, and — as a side effect of
         *  building it — the registration of the generated GLSL that our pipeline compiles from. Built on
         *  first dispatch; null when KilaGraph rejected the pipeline (logged there). */
        @Nullable
        private RenderTypeGraphMaterial material;
        @Nullable
        private RenderPipeline pipeline;
        private boolean materialFailed;

        private Entry(CompoundTag sourceTag, @Nullable FullscreenShaderGraph graph,
                      @Nullable CompiledShaderGraph compiled, String errorMessage) {
            this.sourceTag = sourceTag;
            this.graph = graph;
            this.compiled = compiled;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() {
            return compiled != null;
        }

        /**
         * The value store this pass stages into, or null when the graph is broken / its pipeline failed.
         * The 1.21 {@code KGMaterialValues} equivalent — same role, but the values now live in a std140
         * UBO the material owns.
         */
        @Nullable
        public RenderTypeGraphMaterial material() {
            if (material == null && !materialFailed && compiled != null) {
                material = RenderTypeFactory.createMaterial(compiled);
                if (material == null) {
                    materialFailed = true; // KilaGraph logged why; don't retry every frame
                } else {
                    pipeline = PhotonPipelines.fullscreenGraph(compiled);
                }
            }
            return material;
        }

        /** The pipeline this pass draws with; null until (and unless) {@link #material()} succeeds. */
        @Nullable
        public RenderPipeline pipeline() {
            return material() == null ? null : pipeline;
        }

        private void close() {
            if (material != null) {
                material.close(); // releases KilaGraph's refcount on the generated pipeline + GLSL
                material = null;
            }
            pipeline = null;
        }
    }

    /**
     * Resolve + compile the graph at {@code path} (cached; recompiles only when the stored resource tag
     * changed). Returns an error entry (non-{@link Entry#isValid()}) when the resource is missing or the
     * graph fails to compile. Render thread only.
     */
    @Nullable
    public static Entry get(@Nullable IResourcePath path) {
        if (path == null) return null;
        var tag = FullscreenShaderGraphResource.INSTANCE.getResourceInstance().getResource(path);
        var entry = CACHE.get(path);
        if (entry != null && entry.sourceTag == tag) return entry;
        if (entry != null) entry.close();
        entry = compile(tag);
        CACHE.put(path, entry);
        return entry;
    }

    /** Force-recompile {@code path} on next {@link #get}. */
    public static void invalidate(@Nullable IResourcePath path) {
        if (path == null) return;
        var entry = CACHE.remove(path);
        if (entry != null) entry.close();
    }

    private static Entry compile(@Nullable CompoundTag tag) {
        if (tag == null) {
            return new Entry(null, null, null, "fullscreen graph resource not found");
        }
        try {
            var graph = (FullscreenShaderGraph) FullscreenShaderGraphResource.INSTANCE
                    .deserializeGraph(tag, RESOLVER);
            var compiled = graph.createCompiler().compile();
            if (compiled.hasStageErrors()) {
                return new Entry(tag, graph, null, compiled.stageErrors().getFirst().message());
            }
            return new Entry(tag, graph, compiled, "");
        } catch (Throwable e) {
            Photon.LOGGER.error("Failed to compile fullscreen shader graph", e);
            return new Entry(tag, null, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Resolves EXTERNAL subgraph references (dragged-in shader function graphs) outside the editor —
     * the runtime twin of the editor container's resolver, looking paths up across the function-graph
     * and fullscreen-graph libraries.
     */
    private static final IGraphReferenceResolver RESOLVER = new IGraphReferenceResolver() {
        @Override
        public Graph resolve(IResourcePath refPath) {
            if (refPath == null) return null;
            var fnTag = PhotonShaderFunctionGraphResource.INSTANCE.getResourceInstance().getResource(refPath);
            if (fnTag != null) {
                var fn = PhotonShaderFunctionGraphResource.INSTANCE.createGraph();
                fn.graphModel.setReferenceResolver(this);
                PersistedParser.deserializeNBT(fnTag, fn.graphModel, Platform.getFrozenRegistry());
                fn.graphModel.setReferenceResolver(this);
                return fn;
            }
            var fsTag = FullscreenShaderGraphResource.INSTANCE.getResourceInstance().getResource(refPath);
            if (fsTag != null) {
                return FullscreenShaderGraphResource.INSTANCE.deserializeGraph(fsTag, this);
            }
            return null;
        }

        @Override
        public void save(IResourcePath refPath, CompoundTag refTag) {
            // runtime resolution never writes back
        }

        @Override
        public GraphResource<?> getSourceResource() {
            return FullscreenShaderGraphResource.INSTANCE;
        }
    };
}
