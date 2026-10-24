package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.SearchComponentConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Editing and saving lists of block or entity ids and {@code #tags}, for the settings that filter by them. */
final class IdLists {
    private IdLists() {
    }

    static Configurator blockEntry(Supplier<String> getter, Consumer<String> setter) {
        return search("block", getter, setter, candidates(BuiltInRegistries.BLOCK));
    }

    static Configurator entityEntry(Supplier<String> getter, Consumer<String> setter) {
        return search("entity", getter, setter, candidates(BuiltInRegistries.ENTITY_TYPE));
    }

    private static List<String> candidates(Registry<?> registry) {
        var candidates = new ArrayList<String>();
        registry.listTagIds().forEach(tag -> candidates.add("#" + tag.location()));
        registry.keySet().forEach(id -> candidates.add(id.toString()));
        candidates.sort(String::compareTo);
        return candidates;
    }

    private static Configurator search(String name, Supplier<String> getter, Consumer<String> setter, List<String> candidates) {
        return new SearchComponentConfigurator<>(name, getter, setter, "", true,
                (word, handler) -> {
                    var search = word.toLowerCase(Locale.ROOT);
                    for (var candidate : candidates) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (candidate.contains(search)) handler.accept(candidate);
                    }
                },
                s -> s,
                UIElementProvider.text(s -> Component.literal(s == null || s.isEmpty() ? "---" : s)));
    }

    static ListTag serialize(List<String> values) {
        var list = new ListTag();
        for (var value : values) list.add(StringTag.valueOf(value == null ? "" : value));
        return list;
    }

    static List<String> deserialize(ListTag tag) {
        var values = new ArrayList<String>();
        for (Tag element : tag) values.add(element.asString().orElse(""));
        return values;
    }
}
