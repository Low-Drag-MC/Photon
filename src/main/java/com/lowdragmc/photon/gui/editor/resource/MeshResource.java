package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceFileImport;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceImportContext;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ResourceMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.function.Consumer;


public class MeshResource extends Resource<MeshData> {
    public static final MeshResource INSTANCE = new MeshResource();
    private static final String OBJ_EXTENSION = ".obj";
    private static final String JSON_EXTENSION = ".json";

    /**
     * Fired (with the clicked path) whenever a mesh tile is selected in ANY container of this
     * resource — set transiently by {@code MeshDataConfigurator}'s selector dialog to receive the
     * chosen <em>path</em> and wrap it as a live {@link ResourceMeshSource} reference (the stock
     * dialog callback only reports the value), cleared on close. Mirrors {@code MaterialResource}.
     */
    @Nullable
    private Consumer<IResourcePath> pathSelectListener;

    public void setPathSelectListener(@Nullable Consumer<IResourcePath> listener) {
        this.pathSelectListener = listener;
    }

    @Override
    public void buildBuiltin(BuiltinResourceProvider<MeshData> provider) {
        provider.addResource("block", new MeshData());
        // unity-style primitives, shipped as obj assets (parsed at runtime, no bakery/atlas)
        for (var primitive : new String[]{"cube", "sphere", "plane", "quad", "cylinder", "capsule"}) {
            provider.addResource(primitive, new MeshData(new ObjModelSource(Photon.id("models/" + primitive + ".obj"))));
        }
    }

    @Override
    public IGuiTexture getIcon() {
        return Icons.MESH;
    }

    @Override
    public String getName() {
        return "mesh";
    }

    @Nullable
    @Override
    public Tag serializeResource(MeshData meshData, HolderLookup.Provider provider) {
        return meshData.serializeNBT(provider);
    }

    @Override
    public MeshData deserializeResource(Tag tag, HolderLookup.Provider provider) {
        if (tag instanceof CompoundTag compoundTag) {
            return new MeshData(compoundTag);
        }
        return new MeshData();
    }

    @Override
    public boolean canImportFile(File file) {
        if (super.canImportFile(file)) return true;
        if (!file.isFile()) return false;
        var name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(OBJ_EXTENSION) || name.endsWith(JSON_EXTENSION);
    }

    /**
     * Drag an {@code .obj} or a model {@code .json} onto the mesh library and get a mesh out of it.
     * <p>
     * Either way the file has to be addressable before it can be referenced, so one from outside the
     * pack is copied in first. Both sources then read it straight off the resource manager the first
     * time the mesh is drawn — a folder pack resolves a file that appeared after the last reload, and
     * 26.1's json path does its own baking ({@link com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonModelBaker}),
     * so neither needs a resource reload. (1.21 did, for the json case: the model bakery only learned
     * about a model during one.)
     */
    @Override
    public void importFile(ResourceImportContext<MeshData> context) {
        var file = context.getFile();
        if (super.canImportFile(file)) {
            super.importFile(context);
            return;
        }
        boolean obj = file.getName().toLowerCase(Locale.ROOT).endsWith(OBJ_EXTENSION);
        ResourceFileImport.resolveOrImport(context.getOwner(), file, "models", location -> {
            // the location keeps its extension for an obj — that is how ObjModelSource opens it; the
            // json path is addressed the way the model loader does, without models/ or the extension
            var source = obj ? new ObjModelSource(location) : new JsonModelSource(modelLocationOf(location));
            // the path is normally fresh, but re-importing a file already in the pack could hit a
            // cached failure from an earlier load attempt
            source.invalidate();
            context.complete(new MeshData(source));
        }, context::cancel);
    }

    /**
     * The location a model json is addressed by: it drops the {@code models/} prefix and the
     * extension, so {@code models/block/foo.json} becomes {@code block/foo}.
     */
    private static Identifier modelLocationOf(Identifier location) {
        var path = location.getPath();
        if (path.startsWith("models/")) path = path.substring("models/".length());
        if (path.endsWith(JSON_EXTENSION)) path = path.substring(0, path.length() - JSON_EXTENSION.length());
        return Identifier.fromNamespaceAndPath(location.getNamespace(), path);
    }

    @Override
    public ResourceProviderContainer<MeshData> createResourceProviderContainer(IResourceProvider<MeshData> provider) {
        var container = new ResourceProviderContainer<MeshData>(provider) {
            @Override
            public void selectResource(IResourcePath resourcePath) {
                super.selectResource(resourcePath);
                if (pathSelectListener != null && resourcePath != null && provider.hasResource(resourcePath)) {
                    pathSelectListener.accept(resourcePath);
                }
            }
        };
        // 26.1: the tile used to drive an FBOWorldSceneRenderer straight from a GuiTexture lambda,
        // i.e. from INSIDE the open GUI render pass — where drawScene's opening
        // clearColorAndDepthTextures is illegal. createTilePreview keeps 1.21's per-tile FBO but
        // hands it to Scene, whose ScenePIPRenderer defers the draw out of that pass and blits the
        // FBO's own texture. It also auto-frames, releases on removal, and leaves the mouse to the
        // tile so drag-to-emitter and click-to-select still work.
        container.setUiSupplier(path -> {
            var meshData = provider.getResource(path);
            if (meshData == null) {
                return new UIElement().layout(layout -> {
                    layout.widthPercent(100);
                    layout.heightPercent(100);
                });
            }
            return meshData.createTilePreview().layout(layout -> {
                layout.widthPercent(100);
                layout.heightPercent(100);
            });
        });
        container.setOnEdit((c, path) -> {
            var meshData = provider.getResource(path);
            if (meshData == null) return;
            c.getEditor().inspectorView.inspect(meshData, configurator -> c.markResourceDirty(path));
        });
        // drag a tile → hand over a live reference (not the raw resource instance), like materials
        container.setOnDragProvider(path -> new MeshData(new ResourceMeshSource(path)));
        container.setAddDefault(MeshData::new);
        return container;
    }

}
