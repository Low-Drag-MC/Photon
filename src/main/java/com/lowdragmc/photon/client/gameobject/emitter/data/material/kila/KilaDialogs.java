package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The material inspector's dialogs, built from the resource panel's cells and configurator groups. */
@OnlyIn(Dist.CLIENT)
public final class KilaDialogs {
    private static final int PRESET = 56;

    private KilaDialogs() {
    }

    static ScrollerView list() {
        var list = new ScrollerView();
        list.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        list.layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
        });
        return list;
    }

    /** A titled section of {@code list}, as a configurator group; returns the grid its cells go in. */
    static UIElement section(ScrollerView list, String title) {
        var grid = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.wrap(FlexWrap.WRAP);
        });
        var group = new ConfiguratorGroup(title, false);
        group.addConfigurator(new Configurator().addInlineChild(grid));
        list.addScrollViewChild(group);
        return grid;
    }

    /** The resource panel's cell, showing {@code thumbnail} and wearing its selection look while {@code selected}. */
    static UIElement cell(UIElement thumbnail, Component name, int width, BooleanSupplier selected) {
        var cell = ResourceProviderContainer.createResourceCell(Resource.DisplayMode.GRID, width, thumbnail, name.getString());
        var wash = ResourceProviderContainer.defaultSelectedTexture();
        Runnable sync = () -> {
            boolean on = selected.getAsBoolean();
            if (on == cell.hasClass("__selected__")) return;
            if (on) {
                cell.addClass("__selected__");
            } else {
                cell.removeClass("__selected__");
            }
            cell.style(style -> Style.defaultPipeline(style, s -> s.overlayTexture(on ? wash : IGuiTexture.EMPTY)));
        };
        sync.run();
        cell.addEventListener(UIEvents.TICK, event -> sync.run());
        return cell;
    }

    static UIElement fill(IGuiTexture texture) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
        }).style(style -> style.backgroundTexture(texture));
    }

    /** Every preset drawn with its own material; a click applies it. */
    public static Dialog presets(UIElement origin, float x, float y, Consumer<KilaMaterial> apply) {
        var dialog = new Dialog().windowMode(x, y, 220, 240);
        dialog.setTitle("kila.preset.gallery");
        var list = list();
        list.addClass("__kila-presets__");
        var grid = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.wrap(FlexWrap.WRAP);
        });
        for (var preset : KilaPresets.ALL) {
            var material = preset.create();
            var name = Component.translatable(preset.langKey());
            var cell = cell(fill(KilaSlotPreview.checker()).addChild(fill(material.preview())), name, PRESET, () -> false);
            cell.style(style -> style.tooltips(name, Component.translatable("kila.preset.click_to_apply")));
            cell.addEventListener(UIEvents.MOUSE_DOWN, event -> {
                if (event.button != 0) return;
                apply.accept(preset.create());
                dialog.close();
            });
            grid.addChild(cell);
        }
        list.addScrollViewChild(grid);
        dialog.addContent(list);
        dialog.addButton(new Button().setText("ldlib.gui.tips.cancel").setOnClick(e -> dialog.close())
                .addClass("__cancel-button__"));
        return dialog.show(origin.getModularUI());
    }

    /** The modules by category, each a row with what it does under it; the ones already on cannot be added. */
    public static Dialog modules(UIElement origin, float x, float y, KilaMaterial material, Consumer<KilaModule> add) {
        var dialog = new Dialog().windowMode(x, y, 220, 260);
        dialog.setTitle("kila.module.add.title");
        var list = list();
        list.addClass("__kila-modules__");
        for (var category : KilaModule.Category.values()) {
            var group = new ConfiguratorGroup("kila.module.category." + category.name().toLowerCase(), false);
            for (var module : material.modules()) {
                if (module.category() == category) group.addConfigurator(moduleEntry(dialog, module, add));
            }
            list.addScrollViewChild(group);
        }
        dialog.addContent(list);
        dialog.addButton(new Button().setText("ldlib.gui.tips.cancel").setOnClick(e -> dialog.close())
                .addClass("__cancel-button__"));
        return dialog.show(origin.getModularUI());
    }

    private static Configurator moduleEntry(Dialog dialog, KilaModule module, Consumer<KilaModule> add) {
        var entry = new Configurator(module.langKey());
        entry.addClass("__kila-modules_entry__");
        var button = new Button().setText(module.isEnable() ? "kila.module.add.added" : "kila.module.add.add");
        button.setActive(!module.isEnable());
        button.setOnClick(e -> {
            add.accept(module);
            dialog.close();
        });
        entry.addInlineChild(button);
        var description = new Label().setText(module.langKey() + ".desc");
        description.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        description.layout(layout -> layout.widthPercent(100));
        description.addClass("__kila-modules_description__");
        entry.addChild(description);
        return entry;
    }
}
