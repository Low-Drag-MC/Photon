package com.lowdragmc.photon.gui.editor_outdated;

import com.lowdragmc.lowdraglib2.gui.editor.Icons;
import com.lowdragmc.lowdraglib2.gui.editor.data.resource.Resource;
import com.lowdragmc.lowdraglib2.gui.editor.ui.ConfigPanel;
import com.lowdragmc.lowdraglib2.gui.editor.ui.ResourcePanel;
import com.lowdragmc.lowdraglib2.gui.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib2.gui.widget.ImageWidget;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;

/**
 * @author KilaBash
 * @date 2022/12/5
 * @implNote TexturesResourceContainer
 */
public class MaterialsResourceContainer extends ResourceContainer<IMaterial, ImageWidget> {

    public MaterialsResourceContainer(Resource<IMaterial> resource, ResourcePanel panel) {
        super(resource, panel);
        setWidgetSupplier(k -> new ImageWidget(0, 0, 30, 30, () -> getResource().getResource(k).preview()));
        setDragging(key -> getResource().getResource(key), IMaterial::preview);
        setOnEdit(key -> getPanel().getEditor().getConfigPanel().openConfigurator(ConfigPanel.Tab.RESOURCE, getResource().getResource(key)));
        setOnMenu((selected, m) -> m.branch(Icons.ADD_FILE, "ldlib.gui.editor.menu.add_resource", menu -> {
            for (var clazz : IMaterial.MATERIALS) {
                try {
                    IMaterial icon = clazz.getConstructor().newInstance();
                    menu.leaf(icon.preview(), clazz.getSimpleName(), () -> {
                        resource.addBuiltinResource(genNewFileName(), icon);
                        reBuild();
                    });
                } catch (Throwable ignored) {}
            }
        }));
    }
}
