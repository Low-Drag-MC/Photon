package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.google.common.base.Predicates;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@Accessors(chain = true)
public class MeshDataConfigurator extends ValueConfigurator<MeshData> {
    public final UIElement preview = new UIElement();
    @Setter
    protected Predicate<MeshData> filter = Predicates.alwaysTrue();

    public MeshDataConfigurator(String name, Supplier<MeshData> supplier, Consumer<MeshData> onUpdate, MeshData defaultValue, boolean forceUpdate) {
        super(name, supplier, onUpdate, defaultValue, forceUpdate);
        setTips("editor.drag_drop_resource");
        if (value == null) {
            value = defaultValue;
        }

        inlineContainer.addChild(value.createPreviewScene());
        // the preview scene consumes drags for camera rotation, so selection gets its own button
        inlineContainer.addChild(new Button().setText("photon.gui.editor.tips.select_mesh")
                .setOnClick(this::showMeshDialog)
                .layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        setPastable(MeshData.class, pasted -> {
            if (pasted != null && filter.test(pasted)) {
                onPaste(pasted);
            }
        });
        setCopiable(meshData -> new MeshData(meshData.getSource().copy()));
        setCanDropPredicate(obj -> obj instanceof MeshData && filter.test((MeshData) obj));
    }

    /** Pick a mesh from the editor's mesh resources; selection applies live, cancel restores. */
    protected void showMeshDialog(UIEvent event) {
        var previous = getValue();
        var dialog = MeshResource.INSTANCE.getResourceInstance().createSelectorDialog(event.x, event.y,
                meshData -> {
                    if (meshData != null && filter.test(meshData) && !meshData.equals(getValue())) {
                        onValueUpdatePassively(meshData);
                        updateValue();
                    }
                },
                () -> {
                    if (previous != null && !previous.equals(getValue())) {
                        onValueUpdatePassively(previous);
                        updateValue();
                    }
                });
        dialog.show(getModularUI());
    }

    @Override
    protected void onValueUpdatePassively(MeshData newValue) {
        if (newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
        // child 0 is the preview scene; the select button stays
        var children = inlineContainer.getChildren();
        if (!children.isEmpty()) {
            inlineContainer.removeChild(children.get(0));
        }
        inlineContainer.addChildAt(newValue.createPreviewScene(), 0);
    }
}
