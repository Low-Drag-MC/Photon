package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.client.model.ModelFactory;
import com.lowdragmc.lowdraglib2.client.renderer.impl.IModelRenderer;
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
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Geometry from a baked Minecraft JSON model. UVs are block-atlas coordinates and the model must be
 * known to the model bakery (registered via {@code ModelEvent.RegisterAdditional} or loadable
 * dynamically), so picking a new model file needs a resource-pack reload. Positions are shifted
 * into centered space and per-face shade factors are baked per quad (the {@code shade} toggle is
 * applied at consumption time).
 */
@OnlyIn(Dist.CLIENT)
@LDLRegisterClient(name = "json_model", registry = "photon:model_source")
public class JsonModelSource implements IModelSource {
    @Getter
    @Configurable(name = "MeshData.modelLocation")
    private ResourceLocation modelLocation = ResourceLocation.withDefaultNamespace("block/stone");

    public JsonModelSource() {
    }

    public JsonModelSource(ResourceLocation modelLocation) {
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "modelLocation")
    public void setModelLocation(ResourceLocation modelLocation) {
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
        // do not access the model bakery during reloading (null = retry later, not cached)
        if (Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) {
            return null;
        }
        var random = RandomSource.create();
        var bakedModel = ModelFactory.getUnBakedModel(modelLocation).bake(
                ModelFactory.getModelBaker(),
                Material::sprite,
                BlockModelRotation.X0_Y0);
        if (bakedModel == null) {
            bakedModel = ModelFactory.getUnBakedModel(ResourceLocation.withDefaultNamespace("block/stone")).bake(
                    ModelFactory.getModelBaker(),
                    Material::sprite,
                    BlockModelRotation.X0_Y0);
        }
        var quads = new ArrayList<Pair<BakedQuad, Float>>();
        for (var side : TileParticle.MODEL_SIDES) {
            var brightness = side == null ? 1f : switch (side) {
                case DOWN, UP -> 0.9F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
            for (var quad : bakedModel.getQuads(null, side, random, ModelData.EMPTY, null)) {
                quads.add(Pair.of(quad, brightness));
            }
        }
        return PhotonMesh.fromBakedQuads(quads);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void buildConfigurator(ConfiguratorGroup father) {
        IModelSource.super.buildConfigurator(father);
        var buttonConfigurator = new Configurator();
        buttonConfigurator.addInlineChild(new Button().setText("ldlib.gui.editor.tips.select_model").setOnClick(e -> {
            var mui = e.currentElement.getModularUI();
            if (mui == null) return;
            Dialog.showFileDialog("ldlib.gui.editor.tips.select_model", LDLib2.getAssetsDir(), true, node -> {
                if (!node.getKey().isFile() || node.getKey().getName().toLowerCase().endsWith(".json".toLowerCase())) {
                    if (node.getKey().isFile()) {
                        return IModelRenderer.getModelFromFile(node.getKey()) != null;
                    }
                    return true; // allow directories
                }
                return false;
            }, r -> {
                if (r != null && r.isFile()) {
                    var newModel = IModelRenderer.getModelFromFile(r);
                    if (newModel == null) return;
                    if (newModel.equals(modelLocation)) return;
                    setModelLocation(newModel);
                    buttonConfigurator.notifyChanges();
                }
            }).show(mui.ui.rootElement);
        }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        var reloadButton = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> Minecraft.getInstance().reloadResourcePacks().thenAccept(v ->
                        Minecraft.getInstance().execute(() -> {
                            invalidate();
                            buttonConfigurator.notifyChanges();
                        })
                )).setText("photon.reload_mesh").layout(layout -> layout.alignSelf(AlignItems.CENTER)));
        father.addConfigurators(buttonConfigurator, reloadButton);
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
