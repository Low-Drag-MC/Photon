package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.Photon;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Objects;

/**
 * Geometry from a glTF 2.0 file parsed at runtime through the resource manager — same deal as
 * {@link ObjModelSource}: no bakery, no atlas, no resource reload. Reads {@code .glb} and
 * {@code .gltf} with embedded buffers; see {@link GltfMeshParser} for what is taken from the file.
 *
 * <p>The reason to pick this over OBJ: glTF can carry real per-vertex <b>tangents</b>, so a model
 * exported alongside a baked normal map keeps the exact frame the map was baked against instead of one
 * reconstructed from UVs. Turn on the emitter's {@code Tangent} renderer setting to upload them.</p>
 */
@OnlyIn(Dist.CLIENT)
@LDLRegisterClient(name = "gltf_model", registry = "photon:model_source")
public class GltfModelSource implements IModelSource {
    @Getter
    @Configurable(name = "GltfModelSource.modelLocation")
    private ResourceLocation modelLocation = Photon.id("models/missing.glb");
    /** glTF's UV origin is already top-left like Minecraft's, so unlike OBJ this defaults to off. */
    @Getter
    @Configurable(name = "GltfModelSource.flipV", tips = "photon.model_source.gltf_model.flipV.tips")
    private boolean flipV = false;

    public GltfModelSource() {
    }

    public GltfModelSource(ResourceLocation modelLocation) {
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "modelLocation")
    public void setModelLocation(ResourceLocation modelLocation) {
        invalidate(); // drop the old key's entry before it changes
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "flipV")
    public void setFlipV(boolean flipV) {
        invalidate();
        this.flipV = flipV;
    }

    private PhotonMeshCache.GltfKey key() {
        return new PhotonMeshCache.GltfKey(modelLocation, flipV);
    }

    @Override
    public PhotonMesh getMesh() {
        return PhotonMeshCache.INSTANCE.get(key(), k -> load());
    }

    @Override
    public void invalidate() {
        PhotonMeshCache.INSTANCE.invalidate(key());
    }

    @Override
    public IModelSource copy() {
        var copy = new GltfModelSource(modelLocation);
        copy.flipV = flipV;
        return copy;
    }

    /** {@code null} = "can't load right now, don't cache" (retry next call); see {@link PhotonMeshCache#get}. */
    @Nullable
    private PhotonMesh load() {
        // mid-reload the resource manager is swapping; caching EMPTY now would blank the mesh until the
        // next invalidation, so retry afterwards instead (mirrors ObjModelSource).
        if (Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) {
            return null;
        }
        try (var in = Minecraft.getInstance().getResourceManager().open(modelLocation)) {
            var mesh = GltfMeshParser.parse(in, flipV);
            // track the editable disk copy (if any) so pollFileChanges can hot-reload it
            var file = new File(LDLib2.getAssetsDir(), modelLocation.getNamespace() + "/" + modelLocation.getPath());
            if (file.isFile()) {
                PhotonMeshCache.INSTANCE.trackFile(key(), file);
            }
            return mesh;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load glTF model {}", modelLocation, e);
            return PhotonMesh.EMPTY;
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void buildConfigurator(ConfiguratorGroup father) {
        IModelSource.super.buildConfigurator(father);
        var buttonConfigurator = new Configurator();
        buttonConfigurator.addInlineChild(new Button().setText("photon.gui.editor.tips.select_gltf").setOnClick(e -> {
            var mui = e.currentElement.getModularUI();
            if (mui == null) return;
            Dialog.showFileDialog("photon.gui.editor.tips.select_gltf", LDLib2.getAssetsDir(), true,
                    node -> {
                        if (!node.getKey().isFile()) return true; // allow directories
                        var name = node.getKey().getName().toLowerCase();
                        return name.endsWith(".glb") || name.endsWith(".gltf");
                    }, r -> {
                        if (r != null && r.isFile()) {
                            var location = IModelSource.getAssetLocationFromFile(r);
                            if (location == null || location.equals(modelLocation)) return;
                            setModelLocation(location);
                            buttonConfigurator.notifyChanges();
                        }
                    }).show(mui.ui.rootElement);
        }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        // no resource-pack reload needed: the injected pack reads disk on demand
        var reloadButton = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> {
                    invalidate();
                    buttonConfigurator.notifyChanges();
                }).setText("photon.reload_mesh").layout(layout -> layout.alignSelf(AlignItems.CENTER)));
        father.addConfigurators(buttonConfigurator, reloadButton);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        var that = (GltfModelSource) o;
        return flipV == that.flipV && Objects.equals(modelLocation, that.modelLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelLocation, flipV);
    }
}
