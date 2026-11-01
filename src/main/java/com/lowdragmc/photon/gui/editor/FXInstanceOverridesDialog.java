package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
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
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.gui.editor.resource.ResourceDialogs;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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
                // one line per row the inspector shows: a colour's channels or a curve's keys are one change to read
                var rows = new LinkedHashMap<String, List<List<String>>>();
                for (var change : changes) {
                    rows.computeIfAbsent(describe(live, change.change().path()), name -> new ArrayList<>()).add(change.change().path());
                }
                rows.forEach((name, paths) -> {
                    var label = new Label().setText(Component.literal("    " + name));
                    label.style(style -> style.tooltips(paths.stream().map(path -> Component.literal(String.join("/", path)))
                            .toArray(Component[]::new)));
                    list.addChild(row(label, ResourceDialogs.iconButton(Icons.REPLAY, "photon.fx_instance.revert", e -> {
                        instances.revert(live, paths);
                        reopen(editor, dialog, instance);
                    })));
                });
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

    /** The rows {@code path} runs through, named as the inspector names them; the raw path when none resolves. */
    public static String describe(IFXObject object, List<String> path) {
        if (path.equals(FXInstanceInspector.ENABLED_PATH)) return Component.translatable("photon.fx_object.enabled").getString();
        var names = new ArrayList<String>();
        Object at = object;
        for (var segment : path) {
            if (at instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    continue; // the list's own wrapper key
                }
                if (index < 0 || index >= list.size()) break;
                names.add("#" + (index + 1));
                at = list.get(index);
                continue;
            }
            var field = at == null ? null : persistedField(at.getClass(), segment);
            if (field == null) break;
            var configurable = field.getAnnotation(Configurable.class);
            if (configurable != null && configurable.showName() && !configurable.name().isEmpty()) {
                names.add(Component.translatable(configurable.name()).getString());
            }
            try {
                field.setAccessible(true);
                at = field.get(at);
            } catch (IllegalAccessException e) {
                break;
            }
        }
        return names.isEmpty() ? String.join("/", path) : String.join(" › ", names);
    }

    @Nullable
    private static Field persistedField(Class<?> type, String key) {
        for (var c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && key.equals(PersistedParser.persistedKey(field))) return field;
            }
        }
        return null;
    }

    private static void reopen(FXEditor editor, Dialog dialog, FXInstanceObject instance) {
        dialog.close();
        show(editor, instance);
    }

}
