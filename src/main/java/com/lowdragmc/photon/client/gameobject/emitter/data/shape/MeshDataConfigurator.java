package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.google.common.base.Predicates;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
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

        setPastable(MeshData.class, pasted -> {
            if (pasted != null && filter.test(pasted)) {
                onPaste(pasted);
            }
        });
        setCopiable(meshData -> new MeshData(meshData.getModelLocation()));
        setCanDropPredicate(obj -> obj instanceof MeshData && filter.test((MeshData) obj));
    }

    @Override
    protected void onValueUpdatePassively(MeshData newValue) {
        if (newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
        inlineContainer.clearAllChildren();
        inlineContainer.addChild(newValue.createPreviewScene());
    }
}
