package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Geometry from a Minecraft JSON model, baked by {@link PhotonModelBaker} straight off the resource
 * manager — any model id works, at any time, with no registration and no resource-pack reload.
 * UVs are block-atlas coordinates (a texture missing from the atlas comes back as the missing
 * sprite; see {@link PhotonModelBaker}). Positions are shifted into centered space and per-face
 * shade factors are baked per quad (the {@code shade} toggle is applied at consumption time).
 */
@LDLRegisterClient(name = "json_model", registry = "photon:model_source")
public class JsonModelSource implements IModelSource {
    @Getter
    @Configurable(name = "MeshData.modelLocation")
    private Identifier modelLocation = Identifier.withDefaultNamespace("block/stone");

    public JsonModelSource() {
    }

    public JsonModelSource(Identifier modelLocation) {
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "modelLocation")
    public void setModelLocation(Identifier modelLocation) {
        invalidate(); // drop the old key's entry before it changes
        this.modelLocation = modelLocation;
    }

    @Override
    public PhotonMesh getMesh() {
        return PhotonMeshCache.INSTANCE.get(new PhotonMeshCache.JsonKey(modelLocation), k -> bake());
    }

    @Override
    public void invalidate() {
        PhotonMeshCache.INSTANCE.invalidate(new PhotonMeshCache.JsonKey(modelLocation));
    }

    @Override
    public boolean hasAtlasUV() {
        return true;
    }

    @Override
    public IModelSource copy() {
        return new JsonModelSource(modelLocation);
    }

    @Nullable
    private PhotonMesh bake() {
        // don't touch the resource manager mid-reload (null = retry later, not cached)
        if (Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) {
            return null;
        }
        var quads = PhotonModelBaker.bake(modelLocation);
        if (quads == null) {
            return null; // atlas not ready yet
        }
        var collected = new ArrayList<Pair<BakedQuad, Float>>();
        for (var side : TileParticle.MODEL_SIDES) {
            var brightness = side == null ? 1f : switch (side) {
                case DOWN, UP -> 0.9F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
            for (var quad : quads.getQuads(side)) {
                collected.add(Pair.of(quad, brightness));
            }
        }
        return PhotonMesh.fromBakedQuads(collected);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IModelSource.super.buildConfigurator(father);
        var buttonConfigurator = new Configurator();
        buttonConfigurator.addInlineChild(new Button().setText("ldlib.gui.editor.tips.select_model").setOnClick(e -> {
            var mui = e.currentElement.getModularUI();
            if (mui == null) return;
            Dialog.showFileDialog("ldlib.gui.editor.tips.select_model", LDLib2.getAssetsDir(), true, node -> {
                if (!node.getKey().isFile() || node.getKey().getName().toLowerCase().endsWith(".json".toLowerCase())) {
                    if (node.getKey().isFile()) {
                        return getModelFromFile(node.getKey()) != null;
                    }
                    return true; // allow directories
                }
                return false;
            }, r -> {
                if (r != null && r.isFile()) {
                    var newModel = getModelFromFile(r);
                    if (newModel == null) return;
                    if (newModel.equals(modelLocation)) return;
                    setModelLocation(newModel);
                    buttonConfigurator.notifyChanges();
                }
            }).show(mui.ui.rootElement);
        }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        // geometry is re-read from disk on the next getMesh(), so dropping the cache entry is the
        // whole reload — no more full reloadResourcePacks() stall. (A texture that is not yet in
        // the block atlas still needs a real resource reload to get stitched in.)
        var reloadButton = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> {
                    invalidate();
                    buttonConfigurator.notifyChanges();
                }).setText("photon.reload_mesh").layout(layout -> layout.alignSelf(AlignItems.CENTER)));
        father.addConfigurators(buttonConfigurator, reloadButton);
    }

    /** {@code assets/<ns>/models/<path>.json} → {@code <ns>:<path>} (was LDLib2 IModelRenderer.getModelFromFile). */
    @Nullable
    private static Identifier getModelFromFile(File filePath) {
        String fullPath = filePath.getPath().replace('\\', '/');
        int assetsIndex = fullPath.indexOf("assets/");
        if (assetsIndex == -1) return null;
        String relativePath = fullPath.substring(assetsIndex + "assets/".length());
        int slashIndex = relativePath.indexOf('/');
        if (slashIndex == -1) return null;
        String modId = relativePath.substring(0, slashIndex);
        String subPath = relativePath.substring(slashIndex + 1);
        int modelsIndex = subPath.indexOf("models/");
        if (modelsIndex == -1) return null;
        String modelPath = subPath.substring(modelsIndex + "models/".length());
        if (!modelPath.endsWith(".json")) return null;
        String location = modId + ":" + modelPath.substring(0, modelPath.length() - 5);
        return LDLib2.isValidResourceLocation(location) ? Identifier.parse(location) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(modelLocation, ((JsonModelSource) o).modelLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(modelLocation);
    }
}
