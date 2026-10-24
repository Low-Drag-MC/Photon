package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.gui.editor.resource.ResourceDialogs;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

/** Everything an instance overrides, each with a way back (Unity's Overrides list), plus the overrides that found nothing. */
public final class FXInstanceOverridesDialog {
    private FXInstanceOverridesDialog() {
    }

    public static void show(FXEditor editor, FXInstanceObject instance) {
        var instances = editor.instances;
        var placement = instance.getPlacement();
        if (placement == null) return;
        var record = instances.recordOf(instance);
        var dialog = new Dialog();
        dialog.overlay.layout(layout -> layout.width(260).maxWidthPercent(95));
        dialog.setTitle(Component.translatable("photon.fx_instance.overrides.title", instance.getName()).getString());
        var list = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.gapAll(2);
        });
        list.addChild(rolling(new Label().setText(Component.translatable("photon.fx_instance.overrides.source",
                placement.fx() == null ? "?" : placement.fx().toString()))));

        var runtime = editor.runtime;
        var any = false;
        if (placement.isPlaced() && runtime != null) {
            for (var source : placement.template().objects()) {
                var sourceId = InstanceTags.id(source);
                if (sourceId == null) continue;
                var live = runtime.objects.get(InstanceIds.xor(instance.id(), sourceId));
                if (live == null) continue;
                var changes = record.modified().stream().filter(m -> m.target().equals(sourceId)).toList();
                if (changes.isEmpty()) continue;
                any = true;
                list.addChild(row(new Label().setText(Component.literal(live.getName())).textStyle(style ->
                                style.textColor(FXInstanceStyle.PLACED_COLOR)),
                        ResourceDialogs.iconButton(Icons.REPLAY, "photon.fx_instance.revert_object", e -> {
                            instances.revertObject(live);
                            reopen(editor, dialog, instance);
                        })));
                for (var change : changes) {
                    var path = change.change().path();
                    list.addChild(row(new Label().setText(Component.literal("    " + change.change().describePath())),
                            ResourceDialogs.iconButton(Icons.REPLAY, "photon.fx_instance.revert", e -> {
                                instances.revert(live, path);
                                reopen(editor, dialog, instance);
                            })));
                }
            }
            for (var removed : record.removed()) {
                var source = placement.template().object(removed);
                if (source == null) continue;
                any = true;
                list.addChild(row(new Label().setText(Component.translatable("photon.fx_instance.overrides.removed",
                                InstanceTags.name(source))),
                        ResourceDialogs.iconButton(Icons.REPLAY, "photon.fx_instance.restore", e -> {
                            instances.restoreRemoved(instance, removed);
                            reopen(editor, dialog, instance);
                        })));
            }
            for (var object : instances.addedTops(instance)) {
                any = true;
                list.addChild(new Label().setText(Component.translatable("photon.fx_instance.overrides.added", object.getName())));
            }
        }
        var unused = instances.unusedOf(instance);
        if (!unused.isPristine()) {
            list.addChild(row(new Label().setText(Component.translatable("photon.fx_instance.overrides.unused",
                            unused.modified().size() + unused.removed().size() + unused.added().size())),
                    ResourceDialogs.iconButton(Icons.DELETE, "photon.fx_instance.remove_unused", e -> {
                        instances.removeUnused(instance);
                        reopen(editor, dialog, instance);
                    })));
        }
        if (!any && unused.isPristine()) {
            list.addChild(new Label().setText("photon.fx_instance.overrides.none"));
        }
        var scroller = new ScrollerView();
        scroller.layout(layout -> {
            layout.widthPercent(100);
            layout.maxHeight(240);
        });
        scroller.addScrollViewChild(list);
        dialog.addContent(scroller);
        if (any) {
            dialog.addButton(new Button().setText("photon.fx_instance.revert_all").setOnClick(e -> {
                dialog.close();
                instances.revertAll(instance);
            }));
        }
        dialog.addButton(new Button().setOnClick(e -> dialog.close())
                .setText("ldlib.gui.tips.confirm").addClass("__confirm-button__"));
        dialog.show(editor);
    }

    /** A long id rolls on hover rather than being cut at the dialog's edge. */
    private static UIElement rolling(TextElement label) {
        label.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
        return label.layout(layout -> layout.widthPercent(100));
    }

    private static UIElement row(UIElement label, UIElement button) {
        var row = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(4);
        });
        label.layout(layout -> layout.flex(1));
        if (label instanceof TextElement text) text.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
        return row.addChildren(label, button);
    }

    private static void reopen(FXEditor editor, Dialog dialog, FXInstanceObject instance) {
        dialog.close();
        show(editor, instance);
    }

}
