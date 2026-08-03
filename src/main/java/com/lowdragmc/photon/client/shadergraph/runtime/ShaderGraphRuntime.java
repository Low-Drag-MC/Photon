package com.lowdragmc.photon.client.shadergraph.runtime;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResource;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.IGraphReferenceResolver;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The shared compile cache for {@link ShaderGraph} resources: one {@link Entry} per graph resource path,
 * holding the compiled GLSL. Every {@code ShaderGraphMaterial} referencing the same graph shares one entry;
 * each material stages its own uniform values before its draw. The pipelines built from an entry are cached
 * separately in {@code PhotonPipelines.graphShader}, keyed by the compile's {@code contentHash} plus the
 * instancing variant and MaterialSetting state — so the GL programs also exist once per (source, variant).
 *
 * <p>Staleness is detected by tag identity: {@code ResourceInstance.getResource} returns the cached
 * {@link CompoundTag} instance, which is replaced when the resource is saved in the editor (or reloaded
 * from a pack) — so a per-frame identity compare is enough to catch edits without hashing. A same-source
 * recompile whose {@code contentHash} is unchanged still refreshes baked defaults (materials re-bake their
 * value stores off the new entry). Render thread only.</p>
 */
public final class ShaderGraphRuntime {

    private static final Map<IResourcePath, Entry> CACHE = new HashMap<>();

    private ShaderGraphRuntime() {}

    /** One compiled graph's sources. Dropped when the resource changes; the pipelines compiled from it
     *  live in {@code PhotonPipelines}, keyed by content hash, and are freed with the device's pipeline
     *  cache on a resource reload (26.1 has no per-pipeline release). */
    public static final class Entry {
        /** The source tag this entry was compiled from — identity marker for staleness detection. */
        final CompoundTag sourceTag;
        @Getter
        @Nullable
        private final ShaderGraph graph;
        @Getter
        @Nullable
        private final CompiledShaderGraph compiled;
        @Getter
        private final String errorMessage;
        /** {@code PhotonGpuChannels} bits of the additional-data channels the graph reads. */
        @Getter
        private final long usedChannelMask;
        /** Whether the graph reads any user custom-data stream (a {@code CustomDataNode}). */
        @Getter
        private final boolean usesCustomData;

        private Entry(CompoundTag sourceTag, @Nullable ShaderGraph graph,
                      @Nullable CompiledShaderGraph compiled, String errorMessage) {
            this(sourceTag, graph, compiled, errorMessage, 0, false);
        }

        private Entry(CompoundTag sourceTag, @Nullable ShaderGraph graph,
                      @Nullable CompiledShaderGraph compiled, String errorMessage, long usedChannelMask,
                      boolean usesCustomData) {
            this.sourceTag = sourceTag;
            this.graph = graph;
            this.compiled = compiled;
            this.errorMessage = errorMessage;
            this.usedChannelMask = usedChannelMask;
            this.usesCustomData = usesCustomData;
        }

        public boolean isValid() {
            return compiled != null;
        }

        private void close() {
            // nothing GL-side is owned here — see the class note on pipeline lifetime
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
        var tag = ShaderGraphResource.INSTANCE.getResourceInstance().getResource(path);
        var entry = CACHE.get(path);
        if (entry != null && entry.sourceTag == tag) return entry;
        if (entry != null) entry.close();
        entry = compile(tag);
        CACHE.put(path, entry);
        return entry;
    }

    /** Force-recompile {@code path} on next {@link #get} (the inspector's reload button). */
    public static void invalidate(@Nullable IResourcePath path) {
        if (path == null) return;
        var entry = CACHE.remove(path);
        if (entry != null) entry.close();
    }

    private static Entry compile(@Nullable CompoundTag tag) {
        if (tag == null) {
            return new Entry(null, null, null, "shader graph resource not found");
        }
        try {
            var graph = (ShaderGraph) ShaderGraphResource.INSTANCE.deserializeGraph(tag, RESOLVER);
            var compiler = (PhotonShaderCompiler) graph.createCompiler();
            var compiled = compiler.compile();
            if (compiled.hasStageErrors()) {
                return new Entry(tag, graph, null, compiled.stageErrors().getFirst().message());
            }
            return new Entry(tag, graph, compiled, "", compiler.getUsedChannelMask(), compiler.isUsesCustomData());
        } catch (Throwable e) {
            Photon.LOGGER.error("Failed to compile shader graph", e);
            return new Entry(tag, null, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Resolves EXTERNAL subgraph references (dragged-in {@code ShaderFunctionGraph}s / nested shader
     * graphs) outside the editor, by looking the referenced path up across the shader-graph and
     * shader-function resource libraries — the runtime twin of the editor container's resolver.
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
            var sgTag = ShaderGraphResource.INSTANCE.getResourceInstance().getResource(refPath);
            if (sgTag != null) {
                return ShaderGraphResource.INSTANCE.deserializeGraph(sgTag, this);
            }
            return null;
        }

        @Override
        public void save(IResourcePath refPath, CompoundTag refTag) {
            // runtime resolution never writes back
        }

        @Override
        public GraphResource<?> getSourceResource() {
            return ShaderGraphResource.INSTANCE;
        }
    };
}
