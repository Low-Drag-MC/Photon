package com.lowdragmc.photon.client.gameobject.emitter.data.material.configurator;

import com.google.common.base.Predicates;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.UIResourceMaterial;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
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
public class IMaterialConfigurator extends ValueConfigurator<IMaterial> {
    public final UIElement preview = new UIElement();
    @Setter
    protected Predicate<IMaterial> filter = Predicates.alwaysTrue();

    public IMaterialConfigurator(String name, Supplier<IMaterial> supplier, Consumer<IMaterial> onUpdate, IMaterial defaultValue, boolean forceUpdate) {
        super(name, supplier, onUpdate, defaultValue, forceUpdate);
        setTips("editor.drag_drop_resource");
        if (value == null) {
            value = defaultValue;
        }

        inlineContainer.addChild(preview.layout(layout -> {
            layout.setAspectRatio(1.0f);
            layout.widthPercent(100);
            layout.maxWidth(100);
            layout.maxHeight(100);
            layout.alignSelf(AlignItems.CENTER);
            layout.paddingAll(3);
        }).addClass("preview_bg").style(style -> Style.defaultPipeline(style, s -> s.backgroundTexture(Sprites.BORDER1_RT1)))
                .addChild(new UIElement().layout(layout -> {
                    layout.widthPercent(100);
                    layout.heightPercent(100);
                }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> value.preview())))));

        preview.addEventListener(UIEvents.MOUSE_DOWN, this::showMaterialDialog);

        setPastable(IMaterial.class, pasted -> {
            if (pasted != null && filter.test(pasted)) {
                onPaste(pasted);
            }
        });
        setCopiable(IMaterial::copy);
        setCanDropPredicate(obj -> obj instanceof IMaterial && filter.test((IMaterial) obj));
    }

    protected void showMaterialDialog(UIEvent event) {
        var previous = getValue();
        // Selecting from the library assigns a LIVE reference (UIResourceMaterial) — the same semantics
        // as dragging a tile — so later edits to the library resource keep propagating to this slot.
        // The path arrives via MaterialResource's selection listener (the stock dialog callback only
        // reports the value, and an inline copy would silently freeze the material at selection time).
        MaterialResource.INSTANCE.setPathSelectListener(path -> {
            var material = new UIResourceMaterial(path);
            // filter semantics are about the material's actual type — test the resolved target.
            if (filter.test(material.getInternalTexture())) {
                onValueUpdatePassively(material);
                updateValue();
            }
        });
        var dialog = MaterialResource.INSTANCE.getResourceInstance().createSelectorDialog(event.x, event.y,
                material -> { }, () -> {
                    if (previous == null) return;
                    onValueUpdatePassively(previous);
                    updateValue();
                });
        dialog.setOnClose(() -> MaterialResource.INSTANCE.setPathSelectListener(null));
        dialog.show(getModularUI());
    }

    @Override
    protected void onValueUpdatePassively(IMaterial newValue) {
        if (newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
    }
}
