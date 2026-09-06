package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.client.scene.FBOWorldSceneRenderer;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceFileImport;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceImportContext;
import com.lowdragmc.lowdraglib2.editor.resource.ResourceProviderType;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.GltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ResourceMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;


public class MeshResource extends Resource<MeshData> {
    public static final MeshResource INSTANCE = new MeshResource();
    private static final String OBJ_EXTENSION = ".obj";
    private static final String JSON_EXTENSION = ".json";
    /** glTF 2.0, binary container and JSON form — both read by {@link GltfModelSource}. */
    private static final String[] GLTF_EXTENSIONS = {".glb", ".gltf"};

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
    public boolean canImportFile(File file) {
        if (super.canImportFile(file)) return true;
        if (!file.isFile()) return false;
        var name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(OBJ_EXTENSION) || name.endsWith(JSON_EXTENSION) || isGltf(name);
    }

    private static boolean isGltf(String lowerCaseName) {
        for (var extension : GLTF_EXTENSIONS) {
            if (lowerCaseName.endsWith(extension)) return true;
        }
        return false;
    }

    @Override
    public void importFile(ResourceImportContext<MeshData> context) {
        var file = context.getFile();
        if (super.canImportFile(file)) {
            super.importFile(context);
            return;
        }
        // Either way the file has to be addressable first, so one from outside the pack gets copied in.
        var name = file.getName().toLowerCase(Locale.ROOT);
        var gltf = isGltf(name);
        if (gltf || name.endsWith(OBJ_EXTENSION)) {
            // an obj/glTF is parsed straight off the pack when the mesh is first drawn — no bakery, no
            // reload. The location keeps its extension, that is how those sources open it.
            ResourceFileImport.resolveOrImport(context.getOwner(), file, "models", location -> {
                var source = gltf ? new GltfModelSource(location) : new ObjModelSource(location);
                // the path is normally fresh, but re-importing a file already in the pack could hit a
                // cached failure from an earlier load attempt
                source.invalidate();
                context.complete(new MeshData(source));
            }, context::cancel);
            return;
        }
        // a model json is only usable once it has been baked, and the bakery only learns about it from
        // onAdditionalModel — which runs during a resource reload, over the resources that exist by
        // then. So complete first, reload second.
        ResourceFileImport.resolveOrImport(context.getOwner(), file, "models", location -> {
            context.complete(new MeshData(new JsonModelSource(modelLocationOf(location))));
            Minecraft.getInstance().reloadResourcePacks();
        }, context::cancel);
    }

    /**
     * The location the model bakery addresses a model json by: it drops the {@code models/} prefix and
     * the extension, so {@code models/block/foo.json} becomes {@code block/foo}.
     */
    private static ResourceLocation modelLocationOf(ResourceLocation location) {
        var path = location.getPath();
        if (path.startsWith("models/")) path = path.substring("models/".length());
        if (path.endsWith(JSON_EXTENSION)) path = path.substring(0, path.length() - JSON_EXTENSION.length());
        return ResourceLocation.fromNamespaceAndPath(location.getNamespace(), path);
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
        container.setUiSupplier(path -> {
            var meshData = provider.getResource(path);
            if (meshData == null) {
                return new UIElement().layout(layout -> {
                    layout.widthPercent(100);
                    layout.heightPercent(100);
                });
            }
            var level = new TrackedDummyWorld();
            var fboRenderer = new FBOWorldSceneRenderer(level, 512, 512);
            fboRenderer.setFov(40);
            // Re-frame whenever the underlying geometry changes (obj set/hot-reloaded, source
            // switched): the tile isn't rebuilt on every edit, and drawLineFrames reads the live
            // mesh each frame, so a fixed build-time camera would leave edits looking off-screen
            // until a tab switch. Identity compare is a cheap map lookup.
            final PhotonMesh[] framed = {null};
            fboRenderer.setBeforeWorldRender(r -> {
                var mesh = meshData.getSource().getMesh();
                if (mesh == framed[0]) return;
                framed[0] = mesh;
                var min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
                var max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
                var vertices = meshData.getVertices();
                if (vertices.isEmpty()) {
                    min.set(0, 0, 0);
                    max.set(1, 1, 1);
                } else {
                    for (var vertex : vertices) {
                        min.min(vertex);
                        max.max(vertex);
                    }
                }
                var center = new Vector3f((min.x + max.x) / 2f + 0.5F, (min.y + max.y) / 2f + 0.5F, (min.z + max.z) / 2f + 0.5F);
                var zoom = (float) (3.5 * Math.sqrt(Math.max(Math.max(Math.max(max.x - min.x + 1, max.y - min.y + 1), max.z - min.z + 1), 1)));
                fboRenderer.setCameraLookAt(center, zoom, Math.toRadians(-135), Math.toRadians(25));
            });
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
        // drag a tile → hand over a live reference (not the raw resource instance), like materials
        container.setOnDragProvider(path -> new MeshData(new ResourceMeshSource(path)));
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
