package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.configurator.ui.SearchComponentConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.photon.client.fx.FXHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;

/** How FX instances look in the editor, and the picker for the effect an instance places. */
public final class FXInstanceStyle {
    public static final int PLACED_COLOR = 0xFF8CB4FF;
    public static final int MISSING_COLOR = 0xFFFF6B6B;

    private FXInstanceStyle() {
    }

    /** Ask for an effect by id, from every effect the game can load (mod jars, resource packs, fx packs). */
    public static void pickFx(FXEditor editor, Consumer<Identifier> onPicked) {
        var candidates = new ArrayList<String>();
        FXHelper.listAllFX().forEach(fx -> candidates.add(fx.toString()));
        var chosen = new String[]{""};
        var dialog = new Dialog();
        dialog.overlay.layout(layout -> layout.width(240).maxWidthPercent(95));
        dialog.setTitle("photon.fx_instance.pick");
        var content = new UIElement().layout(layout -> layout.widthPercent(100));
        content.addChild(new SearchComponentConfigurator<>("photon.fx_instance.pick.fx",
                () -> chosen[0], value -> chosen[0] = value == null ? "" : value, "", true,
                (word, handler) -> {
                    var search = word.toLowerCase(Locale.ROOT);
                    for (var candidate : candidates) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (candidate.toLowerCase(Locale.ROOT).contains(search)) handler.accept(candidate);
                    }
                },
                value -> value,
                UIElementProvider.text(value -> Component.literal(value == null || value.isEmpty() ? "---" : value))));
        dialog.addContent(content);
        dialog.addButton(new Button().setOnClick(e -> {
            var id = chosen[0].isEmpty() ? null : Identifier.tryParse(chosen[0]);
            dialog.close();
            if (id != null) onPicked.accept(id);
        }).setText("ldlib.gui.tips.confirm").addClass("__confirm-button__"));
        dialog.addButton(new Button().setText("ldlib.gui.tips.cancel").setOnClick(e -> dialog.close()));
        dialog.show(editor);
    }
}
