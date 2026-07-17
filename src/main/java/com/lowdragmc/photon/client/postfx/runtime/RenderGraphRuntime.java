package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphCompiler;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.RenderGraphResource;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The shared compile cache for {@link RenderGraph} (effect) resources: one {@link Entry} per resource
 * path holding the {@link CompiledEffect}. Staleness is detected by tag identity, plus <b>nested</b>
 * staleness: an entry also recompiles when any referenced fullscreen graph's runtime entry was
 * replaced (its identity is captured at compile) — editing a pass's shader graph live-updates every
 * effect using it. Render thread only.
 *
 * <p>{@link #get} returns null when the path does not exist in the render-graph library at all —
 * that lets {@code PostEffectStack} fall back to the bare-fullscreen-graph adapter.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RenderGraphRuntime {

    private static final Map<IResourcePath, Entry> CACHE = new HashMap<>();

    private RenderGraphRuntime() {}

    public static final class Entry {
        final CompoundTag sourceTag;
        @Getter
        @Nullable
        private final RenderGraph graph;
        @Getter
        @Nullable
        private final CompiledEffect effect;
        @Getter
        private final String errorMessage;
        /** The fullscreen entries captured at compile — identity-compared for nested staleness. */
        private final Map<IResourcePath, FullscreenGraphRuntime.Entry> passEntries;

        private Entry(CompoundTag sourceTag, @Nullable RenderGraph graph, @Nullable CompiledEffect effect,
                      String errorMessage, Map<IResourcePath, FullscreenGraphRuntime.Entry> passEntries) {
            this.sourceTag = sourceTag;
            this.graph = graph;
            this.effect = effect;
            this.errorMessage = errorMessage;
            this.passEntries = passEntries;
        }

        public boolean isValid() {
            return effect != null;
        }

        boolean passesFresh() {
            for (var binding : passEntries.entrySet()) {
                if (FullscreenGraphRuntime.get(binding.getKey()) != binding.getValue()) return false;
            }
            return true;
        }
    }

    /**
     * Resolve + compile the effect at {@code path}. Null when the path isn't a render-graph resource;
     * a non-{@link Entry#isValid()} entry when it exists but fails to compile (logged once per
     * source change). Render thread only.
     */
    @Nullable
    public static Entry get(@Nullable IResourcePath path) {
        if (path == null) return null;
        var tag = RenderGraphResource.INSTANCE.getResourceInstance().getResource(path);
        if (tag == null) {
            CACHE.remove(path);
            return null;
        }
        var entry = CACHE.get(path);
        if (entry != null && entry.sourceTag == tag && entry.passesFresh()) return entry;
        entry = compile(path, tag);
        CACHE.put(path, entry);
        return entry;
    }

    /** Force-recompile {@code path} on next {@link #get}. */
    public static void invalidate(@Nullable IResourcePath path) {
        if (path == null) return;
        CACHE.remove(path);
    }

    private static Entry compile(IResourcePath path, CompoundTag tag) {
        try {
            var graph = RenderGraphResource.INSTANCE.deserializeGraph(tag);
            var result = RenderGraphCompiler.compile(path, graph);
            return new Entry(tag, graph, result.effect(), "", result.passEntries());
        } catch (RenderGraphCompiler.CompileError error) {
            Photon.LOGGER.warn("effect '{}' failed to compile: {}", path.getResourceName(), error.getMessage());
            return new Entry(tag, null, null, error.getMessage(), Map.of());
        } catch (Throwable e) {
            Photon.LOGGER.error("Failed to compile render graph '{}'", path.getResourceName(), e);
            return new Entry(tag, null, null, String.valueOf(e.getMessage()), Map.of());
        }
    }
}
