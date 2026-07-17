package com.lowdragmc.photon.client.postfx.shadergraph.runtime;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexFormat;
import com.lowdragmc.kilagraph.rendertype.runtime.KGMaterialValues;
import com.lowdragmc.kilagraph.rendertype.runtime.KGShaderResourceProvider;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.shader.LDShaderInstance;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResource;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.IGraphReferenceResolver;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.postfx.shadergraph.PhotonFullscreenCompiler;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The shared compile cache for {@link FullscreenShaderGraph} resources — the fullscreen twin of
 * {@code ShaderGraphRuntime}: one {@link Entry} per graph resource path holding the compiled GLSL and
 * the single lazily-built GL program (fullscreen passes have no instancing variants). Every effect pass
 * referencing the same graph shares one entry; the executor stages its own values before each dispatch.
 *
 * <p>Staleness is detected by tag identity — the stored {@link CompoundTag} instance is replaced when
 * the resource is saved in the editor or reloaded from a pack. Render thread only.</p>
 */
@OnlyIn(Dist.CLIENT)
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
        @Nullable
        private LDShaderInstance shader;
        /** Remembered GL build failure so a broken graph doesn't retry every frame. */
        private boolean shaderFailed;
        /** The executor's reusable value store — defaults re-baked before every dispatch. */
        @Nullable
        private KGMaterialValues values;

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

        /** The single GL program, built lazily on the render thread (defines-qualified cache key —
         *  see {@code ShaderGraphRuntime.BASE_VARIANT_DEFINE}). Null when the graph or GL build failed. */
        @Nullable
        public LDShaderInstance shader() {
            if (compiled == null || shaderFailed) return null;
            if (shader != null) return shader;
            var format = KGVertexFormat.of(compiled.settings().vertexFormatElements());
            shader = KGShaderResourceProvider.createShaderInstance(compiled, format,
                    Set.of(PhotonFullscreenCompiler.DEFINE));
            if (shader == null) shaderFailed = true;
            return shader;
        }

        /** The shared value store for dispatch staging. Callers MUST {@code bakeDefaults(getCompiled())}
         *  before staging their own values — dispatches reuse this instance. */
        @Nullable
        public KGMaterialValues values() {
            if (compiled == null) return null;
            if (values == null) values = new KGMaterialValues(compiled);
            return values;
        }

        private void close() {
            if (shader != null) {
                shader.close();
                shader = null;
            }
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
                fn.graphModel.deserializeNBT(Platform.getFrozenRegistry(), fnTag);
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
