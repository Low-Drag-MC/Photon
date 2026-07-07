package com.lowdragmc.photon.client.shadergraph.runtime;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexFormat;
import com.lowdragmc.kilagraph.rendertype.runtime.KGShaderResourceProvider;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.shader.LDShaderInstance;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResource;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.IGraphReferenceResolver;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The shared compile cache for {@link ShaderGraph} resources: one {@link Entry} per graph resource path,
 * holding the compiled GLSL plus the lazily-built {@code #define} shader variants ({@code ""} for the CPU
 * quad/trail/beam paths, {@code PARTICLE_INSTANCE} / {@code PARTICLE_MODEL_INSTANCE} for the GPU-instanced
 * particle paths). Every {@code ShaderGraphMaterial} referencing the same graph shares one entry — the
 * GL programs exist once; each material stages its own uniform values before its draw.
 *
 * <p>Staleness is detected by tag identity: {@code ResourceInstance.getResource} returns the cached
 * {@link CompoundTag} instance, which is replaced when the resource is saved in the editor (or reloaded
 * from a pack) — so a per-frame identity compare is enough to catch edits without hashing. A same-source
 * recompile whose {@code contentHash} is unchanged still refreshes baked defaults (materials re-bake their
 * value stores off the new entry). Render thread only.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ShaderGraphRuntime {

    private static final Map<IResourcePath, Entry> CACHE = new HashMap<>();

    private ShaderGraphRuntime() {}

    /** One compiled graph: sources + define variants. Closed (GL programs freed) when its source changes. */
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
        private final Map<String, LDShaderInstance> variants = new HashMap<>();
        /** Defines whose GL build failed — remembered so a broken graph doesn't retry every frame. */
        private final Set<String> failedVariants = new HashSet<>();
        /** {@code PhotonGpuChannels} bits of the additional-data channels the graph reads. */
        @Getter
        private final long usedChannelMask;

        private Entry(CompoundTag sourceTag, @Nullable ShaderGraph graph,
                      @Nullable CompiledShaderGraph compiled, String errorMessage) {
            this(sourceTag, graph, compiled, errorMessage, 0);
        }

        private Entry(CompoundTag sourceTag, @Nullable ShaderGraph graph,
                      @Nullable CompiledShaderGraph compiled, String errorMessage, long usedChannelMask) {
            this.sourceTag = sourceTag;
            this.graph = graph;
            this.compiled = compiled;
            this.errorMessage = errorMessage;
            this.usedChannelMask = usedChannelMask;
        }

        public boolean isValid() {
            return compiled != null;
        }

        /**
         * Compiling with an EMPTY define set would cache the GL program stages under their PLAIN
         * name in the vanilla {@code Program} cache. A later DEFINED variant of the same source
         * would then silently REUSE that stage: {@code ShaderInstance.getOrCreate}'s defines-aware
         * lookup (LDLib2's ShaderInstanceMixin) only early-returns on a defines-key hit and falls
         * through to the vanilla plain-name lookup — so e.g. the PARTICLE_INSTANCE variant links
         * the CPU-attribute-layout vertex stage and renders nothing (until a reload rebuilds the
         * variants in a luckier order). Always compile under a defines-qualified cache key —
         * {@code LDShaderHolder} does the same via its per-holder uid define.
         */
        private static final String BASE_VARIANT_DEFINE = "PHOTON_VARIANT_BASE";

        /** The shader for one define permutation ({@code ""} = the plain BLOCK-attribute variant), built
         *  lazily on the render thread. Null when the graph or the GL build failed. */
        @Nullable
        public LDShaderInstance variant(String define) {
            if (compiled == null || failedVariants.contains(define)) return null;
            var existing = variants.get(define);
            if (existing != null) return existing;
            var format = KGVertexFormat.of(compiled.settings().vertexFormatElements());
            var created = KGShaderResourceProvider.createShaderInstance(compiled, format,
                    define.isEmpty() ? Set.of(BASE_VARIANT_DEFINE) : Set.of(define));
            if (created == null) {
                failedVariants.add(define);
                return null;
            }
            variants.put(define, created);
            return created;
        }

        private void close() {
            variants.values().forEach(LDShaderInstance::close);
            variants.clear();
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
            return new Entry(tag, graph, compiled, "", compiler.getUsedChannelMask());
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
                fn.graphModel.deserializeNBT(Platform.getFrozenRegistry(), fnTag);
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
