package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.lowdraglib2.editor.resource.FileResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared "copy from resource" / "save to resource" dialogs used by curve and gradient configurators.
 */
public class ResourceDialogs {

    /**
     * A small icon button (with the {@code __white_icon__} class so the icon is tinted) to place in an editor popup.
     */
    public static Button iconButton(IGuiTexture icon, String tooltip, UIEventListener onClick) {
        var button = new Button().noText().setOnClick(onClick);
        button.addChild(new UIElement().layout(layout -> layout.heightPercent(100).setAspectRatio(1f))
                .style(style -> style.backgroundTexture(icon))
                .addClasses("__icon__", "__button_post-icon__", "__white_icon__"));
        button.style(style -> style.tooltips(tooltip));
        return button;
    }

    /**
     * Opens the resource selector so the user can copy an existing resource into an editor.
     * @return the shown dialog, or null if there is no ui to attach to.
     */
    @Nullable
    public static <T> Dialog showLoadDialog(Resource<T> resource, @Nullable ModularUI mui, float x, float y, Consumer<T> onSelect) {
        if (mui == null) return null;
        return resource.getResourceInstance().createSelectorDialog(x, y, onSelect, null).show(mui);
    }

    /**
     * Opens a dialog to save the supplied value to a {@link FileResourceProvider}. Provider selection and naming happen
     * in the same dialog; the name field shows an error while it is blank or collides with an existing resource.
     * @return the shown dialog, or null if there is no ui to attach to.
     */
    @Nullable
    public static <T> Dialog showSaveDialog(Resource<T> resource, @Nullable ModularUI mui, float x, float y, Supplier<T> toSave) {
        if (mui == null) return null;
        var instance = resource.getResourceInstance();
        var fileProviders = new ArrayList<IResourceProvider<T>>();
        for (var list : instance.getBuiltinProviders().values()) {
            for (var provider : list) {
                if (provider instanceof FileResourceProvider) fileProviders.add(provider);
            }
        }
        for (var list : instance.getCustomProviders().values()) {
            for (var provider : list) {
                if (provider instanceof FileResourceProvider) fileProviders.add(provider);
            }
        }
        if (fileProviders.isEmpty()) {
            return Dialog.showNotification("photon.resource.no_file_provider.title", "photon.resource.no_file_provider.info", null).show(mui);
        }

        var selected = new AtomicReference<>(fileProviders.get(0));
        var nameField = new TextField().setText(resource.getName(), false);
        nameField.setTextValidator(name -> !name.isBlank() && !selected.get().hasResource(selected.get().createSubPath(name)));

        var dialog = new Dialog().setTitle("photon.resource.save_to_resource");

        // provider selection row (always shown, so the user knows where it saves)
        var providerRow = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.wrap(FlexWrap.WRAP);
            layout.gapAll(2);
        });
        var providerButtons = new HashMap<IResourceProvider<T>, Button>();
        Runnable refreshHighlight = () -> providerButtons.forEach((provider, button) ->
                button.buttonStyle(style -> style.baseTexture(provider == selected.get() ? Sprites.RECT_RD_DARK : Sprites.RECT_RD)));
        for (var provider : fileProviders) {
            var button = new Button().setText(provider.getName()).setOnClick(e -> {
                selected.set(provider);
                refreshHighlight.run();
                nameField.insertText(""); // re-validate the name against the newly selected provider
            });
            providerButtons.put(provider, button);
            providerRow.addChild(button);
        }
        refreshHighlight.run();

        dialog.addContent(providerRow);
        dialog.addContent(nameField.layout(layout -> layout.widthPercent(100)));
        nameField.insertText(""); // initial validation so a colliding default name shows an error immediately

        dialog.addButton(new Button().setOnClick(e -> {
            var provider = selected.get();
            var name = nameField.getText();
            if (name.isBlank() || provider.hasResource(provider.createSubPath(name))) return; // invalid: keep the dialog open
            provider.addResource(provider.createSubPath(name), toSave.get());
            dialog.close();
        }).setText("ldlib.gui.tips.confirm").addClass("__confirm-button__"));
        dialog.addButton(new Button().setOnClick(e -> dialog.close()).setText("ldlib.gui.tips.cancel").addClass("__cancel-button__"));
        return dialog.show(mui);
    }
}
