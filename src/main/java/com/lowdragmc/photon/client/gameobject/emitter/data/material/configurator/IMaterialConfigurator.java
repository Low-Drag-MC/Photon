package com.lowdragmc.photon.client.gameobject.emitter.data.material.configurator;

import com.google.common.base.Predicates;
import com.lowdragmc.lowdraglib2.configurator.ui.ValueConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.MethodsReturnNonnullByDefault;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaEdge;

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
            layout.setWidthPercent(100);
            layout.setMaxWidth(100);
            layout.setMaxHeight(100);
            layout.setAlignSelf(YogaAlign.CENTER);
            layout.setPadding(YogaEdge.ALL, 3);
        }).style(style -> style.backgroundTexture(Sprites.BORDER1_RT1))
                .addChild(new UIElement().layout(layout -> {
                    layout.setWidthPercent(100);
                    layout.setHeightPercent(100);
                }).style(style -> style.backgroundTexture(DynamicTexture.of(() -> value.preview())))));

        setPastable(IMaterial.class, pasted -> {
            if (pasted != null && filter.test(pasted)) {
                onPaste(pasted);
            }
        });
        setCopiable(IMaterial::copy);
        setCanDropPredicate(obj -> obj instanceof IMaterial && filter.test((IMaterial) obj));
    }

    @Override
    protected void onValueUpdatePassively(IMaterial newValue) {
        if (newValue.equals(value)) return;
        super.onValueUpdatePassively(newValue);
    }
}
