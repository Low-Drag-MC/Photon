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
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Objects;

/**
 * Geometry from a Wavefront OBJ file parsed at runtime through the resource manager — no bakery,
 * no atlas, no resource reload needed. The location keeps its {@code .obj} extension (e.g.
 * {@code photon:models/rocket.obj}); files under {@code <gameDir>/ldlib2/assets/<ns>/...} (LDLib2's
 * injected pack) or any resource pack resolve. UVs are raw 0..1 — the texture comes from the
 * material system. Load failures are cached as an empty mesh (cleared by reload/invalidate) so a
 * missing file doesn't retry every frame.
 */
@LDLRegisterClient(name = "obj_model", registry = "photon:model_source")
public class ObjModelSource implements IModelSource {
    @Getter
    @Configurable(name = "ObjModelSource.modelLocation")
    private Identifier modelLocation = Photon.id("models/missing.obj");
    @Getter
    @Configurable(name = "ObjModelSource.flipV", tips = "photon.model_source.obj_model.flipV.tips")
    private boolean flipV = true;

    public ObjModelSource() {
    }

    public ObjModelSource(Identifier modelLocation) {
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "modelLocation")
    public void setModelLocation(Identifier modelLocation) {
        invalidate(); // drop the old key's entry before it changes
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "flipV")
    public void setFlipV(boolean flipV) {
        invalidate();
        this.flipV = flipV;
    }

    private PhotonMeshCache.ObjKey key() {
        return new PhotonMeshCache.ObjKey(modelLocation, flipV);
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
        var copy = new ObjModelSource(modelLocation);
        copy.flipV = flipV;
        return copy;
    }

    /** {@code null} = "can't load right now, don't cache" (retry next call); see {@link PhotonMeshCache#get}. */
    @Nullable
    private PhotonMesh load() {
        // The resource manager is mid-swap during a reload (F3+T / resource reload also clears our
        // cache). Loading now can transiently fail; caching EMPTY would blank the mesh until the next
        // invalidation. Retry after the reload instead — mirrors JsonModelSource's overlay guard.
        if (Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) {
            return null;
        }
        try (var in = Minecraft.getInstance().getResourceManager().open(modelLocation)) {
            var mesh = ObjMeshParser.parse(in, flipV);
            // track the editable disk copy (if any) so pollFileChanges can hot-reload it
            var file = new File(LDLib2.getAssetsDir(), modelLocation.getNamespace() + "/" + modelLocation.getPath());
            if (file.isFile()) {
                PhotonMeshCache.INSTANCE.trackFile(key(), file);
            }
            return mesh;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load OBJ model {}", modelLocation, e);
            return PhotonMesh.EMPTY;
        }
    }

    /**
     * Map a file under {@code .../assets/<namespace>/<path>} to a Identifier keeping the
     * extension, or null when the file is outside an assets tree.
     */
    @Nullable
    public static Identifier getAssetLocationFromFile(File file) {
        String fullPath = file.getPath().replace('\\', '/');
        int assetsIndex = fullPath.indexOf("assets/");
        if (assetsIndex == -1) return null;
        String relativePath = fullPath.substring(assetsIndex + "assets/".length());
        int slashIndex = relativePath.indexOf('/');
        if (slashIndex == -1) return null;
        String location = relativePath.substring(0, slashIndex) + ":" + relativePath.substring(slashIndex + 1);
        return LDLib2.isValidResourceLocation(location) ? Identifier.parse(location) : null;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IModelSource.super.buildConfigurator(father);
        var buttonConfigurator = new Configurator();
        buttonConfigurator.addInlineChild(new Button().setText("photon.gui.editor.tips.select_obj").setOnClick(e -> {
            var mui = e.currentElement.getModularUI();
            if (mui == null) return;
            Dialog.showFileDialog("photon.gui.editor.tips.select_obj", LDLib2.getAssetsDir(), true,
                    Dialog.suffixFilter(".obj"), r -> {
                        if (r != null && r.isFile()) {
                            var location = getAssetLocationFromFile(r);
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
        var that = (ObjModelSource) o;
        return flipV == that.flipV && Objects.equals(modelLocation, that.modelLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelLocation, flipV);
    }
}
