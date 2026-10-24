package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.configurator.ui.ArrayConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.instance.NbtPatch;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * What the inspector shows of FX instances: a header naming an instance's source, or the instance an object was
 * placed by, whose menu holds what may be done to it; and on a placed object, a mark on every row whose value
 * differs from the source and a Revert entry on its menu.
 */
public final class FXInstanceInspector {
    private static final List<String> ENABLED_PATH = List.of("_additional");
    private static final List<String> CONFIG = List.of("config");
    /** The key a read-only managed field (a final list, say) saves its value under. */
    private static final String PAYLOAD = "payload";

    private FXInstanceInspector() {
    }

    public static void decorate(FXEditor editor, ConfiguratorGroup root, IFXObject object) {
        var instances = editor.instances;
        switch (instances.role(object)) {
            case INSTANCE, MISSING -> root.addConfiguratorAt(instanceHeader(editor, (FXInstanceObject) object), 0);
            case PLACED -> {
                root.addConfiguratorAt(placedHeader(editor, object), 0);
                // a list's rows come and go with its elements, so the paths follow the rows
                var rows = new Rows();
                root.addEventListener(UIEvents.TICK, e -> {
                    var key = rowsKey(root);
                    if (rows.paths == null || key != rows.key) {
                        var saved = object.serializeWrapper();
                        rows.paths = paths(root, saved == null ? new CompoundTag() : InstanceTags.data(saved));
                        rows.key = key;
                    }
                    rows.paths.forEach((row, path) -> row.setOverridden(instances.isOverridden(object, path)));
                });
                root.addMenuContributor((clicked, menu) -> {
                    var path = rows.paths == null ? null : rows.paths.get(clicked);
                    if (path == null || !instances.isOverridden(object, path)) return;
                    menu.crossLine();
                    menu.leaf(Icons.REPLAY, Component.translatable("photon.fx_instance.revert"),
                            () -> instances.revert(object, path));
                });
            }
            default -> {
            }
        }
    }

    private static final class Rows {
        @Nullable
        Map<Configurator, List<String>> paths;
        long key;
    }

    /** Which rows there are: it changes whenever a row is made or replaced. */
    private static long rowsKey(ConfiguratorGroup group) {
        long key = 1;
        for (var row : group.getConfigurators()) {
            key = key * 31 + System.identityHashCode(row);
            if (row instanceof ArrayConfiguratorGroup<?> list) {
                for (var item : list.getConfigurators()) {
                    key = key * 31 + System.identityHashCode(item);
                    if (item instanceof ArrayConfiguratorGroup<?>.ItemConfigurator element && element.inner instanceof ConfiguratorGroup inner) {
                        key = key * 31 + rowsKey(inner);
                    }
                }
            } else if (row instanceof ConfiguratorGroup inner) {
                key = key * 31 + rowsKey(inner);
            }
        }
        return key;
    }

    private static Configurator instanceHeader(FXEditor editor, FXInstanceObject instance) {
        var placement = instance.getPlacement();
        var placed = placement != null && placement.isPlaced();
        var fx = instance.getFx() == null ? "?" : instance.getFx().toString();
        var header = new Configurator();
        header.setId("photon_fx_instance_header");
        header.setLabel(Component.translatable(placed ? "photon.fx_instance.header" : "photon.fx_instance.header.missing", fx));
        if (!placed) {
            header.label.textStyle(style -> style.textColor(FXInstanceStyle.MISSING_COLOR));
        }
        header.addMenuContributor((clicked, menu) -> {
            if (clicked == header) instanceActions(editor, instance, menu, UnaryOperator.identity());
        });
        return header;
    }

    private static Configurator placedHeader(FXEditor editor, IFXObject object) {
        var instances = editor.instances;
        var owner = instances.owner(object);
        var header = new Configurator();
        header.setId("photon_fx_instance_placed_header");
        header.setLabel(Component.translatable("photon.fx_instance.placed_by", owner == null ? "?" : owner.getName(),
                owner == null || owner.getFx() == null ? "?" : owner.getFx().toString()));
        header.label.textStyle(style -> style.textColor(FXInstanceStyle.PLACED_COLOR));
        header.addMenuContributor((clicked, menu) -> {
            if (clicked != header) return;
            if (instances.isOverridden(object)) {
                menu.leaf(Icons.REPLAY, "photon.fx_instance.revert_object", () -> instances.revertObject(object));
            } else {
                menu.disabledLeaf("photon.fx_instance.revert_object");
            }
        });
        return header;
    }

    /**
     * What may be done to an instance, for the hierarchy's menu and the inspector header's.
     *
     * @param afterwards wraps an action that opens nothing, e.g. to give the focus back to where the menu was opened
     */
    public static void instanceActions(FXEditor editor, FXInstanceObject instance, TreeBuilder.Menu menu,
                                       UnaryOperator<Runnable> afterwards) {
        var instances = editor.instances;
        var placed = instances.role(instance) == FXInstanceEditing.Role.INSTANCE;
        // these open dialogs, which taking the focus back would close
        if (instance.getFx() != null) {
            menu.leaf("photon.fx_instance.open_source", () -> FXFileActions.openSource(editor, instance.getFx()));
        } else {
            menu.disabledLeaf("photon.fx_instance.open_source");
        }
        if (placed) {
            menu.leaf("photon.fx_instance.overrides", () -> FXInstanceOverridesDialog.show(editor, instance));
            menu.leaf("photon.fx_instance.revert_all", afterwards.apply(() -> instances.revertAll(instance)));
        } else {
            menu.disabledLeaf("photon.fx_instance.overrides");
            menu.disabledLeaf("photon.fx_instance.revert_all");
        }
        menu.leaf("photon.fx_instance.replace", () -> FXInstanceStyle.pickFx(editor, fx -> instances.replace(instance, fx)));
    }

    /**
     * Each row showing a saved value → the path of keys it is saved under in {@code data} (the object's saved data,
     * read for the shape lists are saved in). The rows of an object's {@code config} hang straight under the
     * object's own, so a top row whose field is not the object's is under {@code config}.
     */
    static Map<Configurator, List<String>> paths(ConfiguratorGroup root, CompoundTag data) {
        var out = new IdentityHashMap<Configurator, List<String>>();
        collect(root, List.of(), true, data, out);
        return out;
    }

    private static void collect(ConfiguratorGroup group, List<String> at, boolean top, CompoundTag data,
                                Map<Configurator, List<String>> into) {
        for (var row : group.getConfigurators()) {
            if (FXObject.ENABLED_ROW.equals(row.getId())) {
                into.put(row, ENABLED_PATH);
                continue;
            }
            var path = at;
            var field = row.getSourceField();
            if (field != null) {
                var key = PersistedParser.persistedKey(field);
                if (key == null) continue;   // not saved, so never overridden
                var base = top && !IFXObject.class.isAssignableFrom(field.getDeclaringClass()) ? CONFIG : at;
                path = PersistedParser.isPersistedFlat(field) ? base : append(base, key);
                into.put(row, path);
            }
            if (row instanceof ArrayConfiguratorGroup<?> list) {
                var elements = NbtPatch.get(data, path) instanceof CompoundTag managed && managed.contains(PAYLOAD)
                        ? append(path, PAYLOAD) : path;
                int index = 0;
                for (var item : list.getConfigurators()) {
                    if (!(item instanceof ArrayConfiguratorGroup<?>.ItemConfigurator element)) continue;
                    var itemPath = append(elements, Integer.toString(index++));
                    into.put(element, itemPath);
                    if (element.inner instanceof ConfiguratorGroup inner) {
                        collect(inner, itemPath, false, data, into);
                    } else if (element.inner != null) {
                        into.put(element.inner, itemPath);
                    }
                }
            } else if (row instanceof ConfiguratorGroup inner) {
                collect(inner, path, false, data, into);
            }
        }
    }

    private static List<String> append(List<String> path, String key) {
        var longer = new ArrayList<>(path);
        longer.add(key);
        return List.copyOf(longer);
    }
}
