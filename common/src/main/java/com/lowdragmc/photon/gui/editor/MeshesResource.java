package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.editor.ui.ResourcePanel;
import com.lowdragmc.lowdraglib.gui.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import com.lowdragmc.photon.client.data.shape.MeshData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote MeshesResource
 */
public class MeshesResource extends Resource<MeshData> {
    @Override
    public String name() {
        return "mesh";
    }

    @Override
    public void buildDefault() {
        addModelMesh("pedestal");
    }

    public void addModelMesh(String model) {
        var mesh = new MeshData(LDLib.location("block/" + model));
        mesh.meshName = model;
        data.put(model, mesh);
    }

    @Override
    public ResourceContainer<MeshData, ImageWidget> createContainer(ResourcePanel panel) {
        ResourceContainer<MeshData, ImageWidget> container = new ResourceContainer<>(this, panel) {
            protected void renameResource() {
                if (selected != null) {
                    DialogWidget.showStringEditorDialog(Editor.INSTANCE, LocalizationUtils.format("ldlib.gui.editor.tips.rename") + " " + LocalizationUtils.format(resource.name()), selected, s -> {
                        if (resource.hasResource(s)) {
                            return false;
                        }
                        if (renamePredicate != null) {
                            return renamePredicate.test(s);
                        }
                        return true;
                    }, s -> {
                        if (s == null) return;
                        var stored =  resource.removeResource(selected);
                        stored.meshName = s;
                        resource.addResource(s, stored);
                        reBuild();
                    });
                }
            }
        };
        container.setWidgetSupplier(k -> new ImageWidget(0, 0, 30, 30, Icons.MESH.copy()))
                .setDragging(this::getResource, r -> Icons.MESH.copy())
                .setOnEdit(k -> panel.getEditor().getConfigPanel().openConfigurator(ConfigPanel.Tab.RESOURCE, getResource(k)))
                .setOnAdd(key -> new MeshData());
        return container;
    }

    @Override
    public Tag serialize(MeshData value) {
        return value.serializeNBT();
    }

    @Override
    public MeshData deserialize(Tag nbt) {
        if (nbt instanceof CompoundTag tag) {
            var mesh = new MeshData();
            mesh.deserializeNBT(tag);
            return mesh;
        }
        return null;
    }

}
