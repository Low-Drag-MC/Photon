package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;


public class MeshResource extends Resource<MeshData> {
    public static final MeshResource INSTANCE = new MeshResource();

    @Override
    public void buildBuiltin(BuiltinResourceProvider<MeshData> provider) {
        provider.addResource("block", new MeshData());
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
                        layout.setWidthPercent(100);
                        layout.setHeightPercent(100);
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
}
