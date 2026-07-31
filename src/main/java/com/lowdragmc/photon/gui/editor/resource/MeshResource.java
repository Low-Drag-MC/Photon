package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ResourceMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;


public class MeshResource extends Resource<MeshData> {
    public static final MeshResource INSTANCE = new MeshResource();

    /**
     * Fired (with the clicked path) whenever a mesh tile is selected in ANY container of this
     * resource — set transiently by {@code MeshDataConfigurator}'s selector dialog to receive the
     * chosen <em>path</em> and wrap it as a live {@link ResourceMeshSource} reference (the stock
     * dialog callback only reports the value), cleared on close. Mirrors {@code MaterialResource}.
     */
    @Nullable
    private java.util.function.Consumer<IResourcePath> pathSelectListener;

    public void setPathSelectListener(@Nullable java.util.function.Consumer<IResourcePath> listener) {
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
