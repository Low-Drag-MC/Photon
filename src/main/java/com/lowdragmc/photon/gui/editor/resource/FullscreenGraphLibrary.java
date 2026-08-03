package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceInstance;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResource;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.IGraphReferenceResolver;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.SubgraphRegistry;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.Photon;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Path-routed access to the fullscreen-graph library for edit flows that run OUTSIDE its own
 * resource container — the pass-node dive-in ({@code RenderGraphView}) edits a fullscreen graph
 * from within a render-graph editor, so its saves must be written into the provider that actually
 * owns the path (builtins are read-only) and broadcast via {@link SubgraphRegistry} so every open
 * editor refreshes.
 */
public final class FullscreenGraphLibrary {

    private FullscreenGraphLibrary() {}

    @Nullable
    public static CompoundTag load(IResourcePath path) {
        return FullscreenShaderGraphResource.INSTANCE.getResourceInstance().getResource(path);
    }

    /** Whether {@code path} can be written back (builtins and pack resources are read-only). */
    public static boolean isEditable(IResourcePath path) {
        if (path == null) return false;
        var owner = findOwner(FullscreenShaderGraphResource.INSTANCE.getResourceInstance(), path);
        return owner != null && owner.canEdit(path);
    }

    /** Save {@code tag} back into whichever editable provider owns {@code path} + broadcast.
     *  Returns false (and logs) when the path is read-only or unowned. */
    public static boolean save(IResourcePath path, CompoundTag tag) {
        if (path == null || tag == null) return false;
        if (saveInto(FullscreenShaderGraphResource.INSTANCE.getResourceInstance(), path, tag)) {
            SubgraphRegistry.INSTANCE.notifyExternalGraphSaved(path);
            return true;
        }
        return false;
    }

    private static boolean saveInto(ResourceInstance<CompoundTag> instance, IResourcePath path, CompoundTag tag) {
        var owner = findOwner(instance, path);
        if (owner == null) {
            Photon.LOGGER.warn("cannot save fullscreen graph '{}': no provider owns it", path.getResourceName());
            return false;
        }
        if (!owner.canEdit(path)) {
            Photon.LOGGER.warn("fullscreen graph '{}' is read-only (builtin) — dive-in changes were NOT saved; "
                    + "copy it into the project first", path.getResourceName());
            return false;
        }
        owner.addResource(path, tag);
        return true;
    }

    @Nullable
    private static IResourceProvider<CompoundTag> findOwner(ResourceInstance<CompoundTag> instance, IResourcePath path) {
        for (var providers : instance.getBuiltinProviders().values()) {
            for (var provider : providers) {
                if (provider.hasResource(path)) return provider;
            }
        }
        for (var providers : instance.getCustomProviders().values()) {
            for (var provider : providers) {
                if (provider.hasResource(path)) return provider;
            }
        }
        return null;
    }

    /**
     * Reference resolver for dive-in editing: resolves nested shader-function/fullscreen references
     * like the runtime does, and routes nested external saves into the owning library.
     */
    public static final IGraphReferenceResolver EDIT_RESOLVER = new IGraphReferenceResolver() {
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
            var fsTag = load(refPath);
            if (fsTag != null) {
                return FullscreenShaderGraphResource.INSTANCE.deserializeGraph(fsTag, this);
            }
            return null;
        }

        @Override
        public void save(IResourcePath refPath, CompoundTag refTag) {
            if (refPath == null || refTag == null) return;
            var functionInstance = PhotonShaderFunctionGraphResource.INSTANCE.getResourceInstance();
            if (functionInstance.getResource(refPath) != null) {
                if (saveInto(functionInstance, refPath, refTag)) {
                    SubgraphRegistry.INSTANCE.notifyExternalGraphSaved(refPath);
                }
                return;
            }
            FullscreenGraphLibrary.save(refPath, refTag);
        }

        @Override
        public GraphResource<?> getSourceResource() {
            return FullscreenShaderGraphResource.INSTANCE;
        }
    };
}
