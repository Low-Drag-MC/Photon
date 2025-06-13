package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.LDLib;
import com.lowdragmc.lowdraglib2.gui.editor.Icons;
import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib2.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib2.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.editor.ui.ResourcePanel;
import com.lowdragmc.lowdraglib2.gui.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib2.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib2.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib2.utils.LocalizationUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.File;

import static com.lowdragmc.photon.gui.editor_outdated.MeshesResource.RESOURCE_NAME;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote MeshesResource
 */
@LDLRegister(name = RESOURCE_NAME, group = "resource")
public class MeshesResource extends Resource<MeshData> {
    public final static String RESOURCE_NAME = "mesh";

    public MeshesResource() {
        super(new File(LDLib.getLDLibDir(), "assets/resources/mesh"));
    }

    @Override
    public String name() {
        return RESOURCE_NAME;
    }

    @Override
    public void buildDefault() {
        addModelMesh("pedestal");
    }

    public void addModelMesh(String model) {
        var mesh = new MeshData(LDLib.location("block/" + model));
        mesh.meshName = model;
        addBuiltinResource(model, mesh);
    }

    @Override
    public ResourceContainer<MeshData, ImageWidget> createContainer(ResourcePanel panel) {
        ResourceContainer<MeshData, ImageWidget> container = new ResourceContainer<>(this, panel) {
            protected void renameResource() {
                if (selected != null) {
                    DialogWidget.showStringEditorDialog(Editor.INSTANCE, LocalizationUtils.format("ldlib.gui.editor.tips.rename") + " " + LocalizationUtils.format(resource.name()),
                            resource.getResourceName(selected), s -> {
                                if (!selected.map(l -> resource.hasBuiltinResource(s), r -> resource.hasStaticResource(resource.getStaticResourceFile(s)))) {
                                    return false;
                                }
                                if (renamePredicate != null) {
                                    return renamePredicate.test(s);
                                }
                                return true;
                            }, s -> {
                                if (s == null) return;
                                var stored = resource.removeResource(selected);
                                if (stored != null) {
                                    stored.meshName = s;
                                    var name = selected.mapBoth(l -> s, r -> resource.getStaticResourceFile(s));
                                    resource.addResource(name, stored);
                                }
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
