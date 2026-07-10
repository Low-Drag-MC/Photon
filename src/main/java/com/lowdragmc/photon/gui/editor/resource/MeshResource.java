package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceProviderType;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


public class MeshResource extends Resource<MeshData> {
    public static final MeshResource INSTANCE = new MeshResource();

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
        var container = super.createResourceProviderContainer(provider);
        container.setUiSupplier(path -> {
            var meshData = provider.getResource(path);
            Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            if (meshData.getVertices().isEmpty()) {
                min = new Vector3f(0, 0, 0);
                max = new Vector3f(1, 1, 1);
            } else  {
                for (var vertex : meshData.getVertices()) {
                    min.x = Math.min(min.x, vertex.x);
                    min.y = Math.min(min.y, vertex.y);
                    min.z = Math.min(min.z, vertex.z);
                    max.x = Math.max(max.x, vertex.x);
                    max.y = Math.max(max.y, vertex.y);
                    max.z = Math.max(max.z, vertex.z);
                }
            }
            var level = new TrackedDummyWorld();
            var fboRenderer = new FBOWorldSceneRenderer(level, 512, 512);
            fboRenderer.setFov(40);
            var center = new Vector3f((min.x + max.x) / 2f + 0.5F, (min.y + max.y) / 2f + 0.5F, (min.z + max.z) / 2f + 0.5F);
            var zoom = (float) (3.5 * Math.sqrt(Math.max(Math.max(Math.max(max.x - min.x + 1, max.y - min.y + 1), max.z - min.z + 1), 1)));
            fboRenderer.setCameraLookAt(center, zoom, Math.toRadians(-135), Math.toRadians(25));
            fboRenderer.setAfterWorldRender(renderer -> meshData.drawLineFrames(new PoseStack()));
            return new UIElement().layout(layout -> {
                        layout.widthPercent(100);
                        layout.heightPercent(100);
                    }).style(style -> style.backgroundTexture(fboRenderer.drawAsTexture()))
                    // release resources here
                    .addEventListener(UIEvents.REMOVED, e -> fboRenderer.releaseResource());
        });
        container.setOnEdit((c, path) -> {
            var meshData = provider.getResource(path);
            if (meshData == null) return;
            c.getEditor().inspectorView.inspect(meshData, configurator -> c.markResourceDirty(path));
        });
        container.setAddDefault(MeshData::new);
        return container;
    }

    public void onAdditionalModel(Consumer<ModelResourceLocation> registry) {
        for (var meshData : getLoadedResourceMeshes()) {
            // only json models go through the bakery; obj sources are parsed at runtime
            if (meshData.getSource() instanceof JsonModelSource json) {
                registry.accept(ModelResourceLocation.standalone(json.getModelLocation()));
            }
        }
    }

    private List<MeshData> getLoadedResourceMeshes() {
        var instance = getResourceInstance();
        refreshProviders(instance.getBuiltinProviders());
        refreshProviders(instance.getCustomProviders());
        var meshes = new ArrayList<MeshData>();
        for (var entry : instance.listAllResources()) {
            if (entry.getValue() != null) {
                meshes.add(entry.getValue());
            }
        }
        return meshes;
    }

    private static void refreshProviders(Map<ResourceProviderType, List<IResourceProvider<MeshData>>> providersByType) {
        for (var providers : providersByType.values()) {
            for (var provider : providers) {
                provider.checkAndUpdateResourceProvider();
            }
        }
    }
}
