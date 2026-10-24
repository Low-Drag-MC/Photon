package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads an FX's saved objects, placing each instance: a copy of its source's template with ids
 * {@code instance ^ source id}, references re-pointed, and the record's changes made. Works on NBT only.
 * Nothing is dropped: what does not apply is kept on the {@link Placement} and written back by
 * {@link InstanceWriter}, and an instance whose source cannot be placed keeps its record as it was saved.
 */
public final class InstanceBaker {

    public sealed interface Lookup permits Found, Missing, Cyclic {
    }

    public record Found(InstanceTemplate template) implements Lookup {
    }

    public record Missing() implements Lookup {
    }

    /** The source is already being placed further out: {@code cycle} runs from it back to itself. */
    public record Cyclic(List<ResourceLocation> cycle) implements Lookup {
    }

    public static final Missing MISSING = new Missing();

    @FunctionalInterface
    public interface Templates {
        Lookup lookup(ResourceLocation id);
    }

    public enum Status {
        PLACED,
        MISSING,
        /** Placing the source would place the instance inside itself. */
        CYCLIC,
        /** The instance's own data is unreadable (no id or no source id). */
        BROKEN
    }

    /**
     * One instance as read: what it was placed from, and what of its record did not apply.
     *
     * @param unreadable source ids it placed that the containing FX could not read back as objects; they are not
     *                   missing on purpose, so they are not saved as removed
     */
    public record Placement(UUID instance, @Nullable ResourceLocation fx, Status status,
                            @Nullable InstanceTemplate template, @Nullable CompoundTag saved,
                            InstanceRecord unused, Map<UUID, List<NbtPatch.Change>> applied, Set<UUID> unreadable) {

        public Placement(UUID instance, @Nullable ResourceLocation fx, Status status, @Nullable InstanceTemplate template,
                         @Nullable CompoundTag saved, InstanceRecord unused, Map<UUID, List<NbtPatch.Change>> applied) {
            this(instance, fx, status, template, saved, unused, applied, Set.of());
        }

        public boolean isPlaced() {
            return status == Status.PLACED && template != null;
        }

        /** The same placement with {@code more} kept as unused, e.g. changes the objects did not take. */
        public Placement withUnused(List<InstanceRecord.Modification> more) {
            if (more.isEmpty()) return this;
            var modified = new ArrayList<>(unused.modified());
            modified.addAll(more);
            return withUnused(new InstanceRecord(modified, unused.removed(), unused.added()));
        }

        public Placement withUnused(InstanceRecord unused) {
            return new Placement(instance, fx, status, template, saved, unused, applied, unreadable);
        }

        public Placement withUnreadable(Set<UUID> unreadable) {
            return new Placement(instance, fx, status, template, saved, unused, applied, Set.copyOf(unreadable));
        }

        /** The same placement from {@code template}, which places exactly what the one it was placed from did. */
        public Placement withTemplate(InstanceTemplate template) {
            return new Placement(instance, fx, status, template, saved, unused, applied, unreadable);
        }
    }

    /**
     * @param fxData       the baked FX data: instances in their baked form, each followed by what it placed
     * @param placements   by instance id, in list order
     * @param dependencies every source placed, at any depth, whether it could be read or not
     * @param problems     what to tell the author, one line each
     */
    public record Result(CompoundTag fxData, Map<UUID, Placement> placements, Set<ResourceLocation> dependencies,
                         List<String> problems) {
    }

    private InstanceBaker() {
    }

    /** Whether {@code fxData} holds any instance, i.e. whether {@link #bake} would change anything. */
    public static boolean hasInstances(CompoundTag fxData) {
        var list = fxData.getList(InstanceTags.OBJECTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            if (InstanceTags.isInstance(list.getCompound(i))) {
                return true;
            }
        }
        return false;
    }

    public static Set<ResourceLocation> directSources(CompoundTag fxData) {
        var out = new LinkedHashSet<ResourceLocation>();
        for (var object : InstanceTags.objects(fxData)) {
            if (InstanceTags.isInstance(object)) {
                var fx = InstanceTags.fx(object);
                if (fx != null) out.add(fx);
            }
        }
        return out;
    }

    /** {@code fxData} is not modified. */
    public static Result bake(CompoundTag fxData, Templates templates) {
        return new Bake(fxData, templates).run();
    }

    private static final class Bake {
        private final CompoundTag fxData;
        private final Templates templates;
        private final List<CompoundTag> outer = new ArrayList<>();
        private final Map<UUID, CompoundTag> outerById = new HashMap<>();
        private final Map<UUID, List<CompoundTag>> placedAfter = new HashMap<>();
        private final Map<UUID, Placement> placements = new LinkedHashMap<>();
        private final Set<ResourceLocation> dependencies = new LinkedHashSet<>();
        private final List<String> problems = new ArrayList<>();
        private final Set<UUID> taken = new HashSet<>();

        Bake(CompoundTag fxData, Templates templates) {
            this.fxData = fxData;
            this.templates = templates;
        }

        Result run() {
            for (var object : InstanceTags.objects(fxData)) {
                var copy = object.copy();
                outer.add(copy);
                var id = InstanceTags.id(copy);
                if (id != null) {
                    outerById.putIfAbsent(id, copy);
                    taken.add(id);
                }
            }
            for (var object : outer) {
                if (InstanceTags.isInstance(object)) {
                    instance(object);
                }
            }
            var list = new ListTag();
            for (var object : outer) {
                list.add(object);
                var id = InstanceTags.id(object);
                var placed = id == null ? null : placedAfter.remove(id);
                if (placed != null) {
                    list.addAll(placed);
                }
            }
            var baked = fxData.copy();
            baked.put(InstanceTags.OBJECTS, list);
            return new Result(baked, Collections.unmodifiableMap(placements),
                    Collections.unmodifiableSet(dependencies), List.copyOf(problems));
        }

        private void instance(CompoundTag object) {
            var data = InstanceTags.data(object);
            var id = InstanceTags.id(object);
            var fx = InstanceTags.fx(object);
            var saved = data.contains(InstanceTags.OVERRIDES, Tag.TAG_COMPOUND)
                    ? data.getCompound(InstanceTags.OVERRIDES).copy() : null;
            // the baked form never carries a record; the placement keeps it
            data.remove(InstanceTags.OVERRIDES);
            data.remove(InstanceTags.TIMELINE);
            if (id == null || fx == null) {
                problems.add("FX instance '%s' has no %s, so nothing is placed".formatted(
                        InstanceTags.name(object), id == null ? "id" : "source"));
                if (id != null) {
                    placements.put(id, new Placement(id, fx, Status.BROKEN, null, saved, InstanceRecord.EMPTY, Map.of()));
                }
                return;
            }
            if (placements.containsKey(id)) {
                problems.add("FX instance '%s' (%s) is saved twice; the second copy places nothing"
                        .formatted(InstanceTags.name(object), id));
                return;
            }
            dependencies.add(fx);
            var record = InstanceRecord.read(saved);
            switch (templates.lookup(fx)) {
                case Found(var template) -> {
                    dependencies.addAll(template.dependencies());
                    place(object, id, fx, template, record, saved);
                }
                case Missing ignored -> {
                    problems.add("FX instance '%s' places %s, which could not be read; it is kept as saved"
                            .formatted(InstanceTags.name(object), fx));
                    placements.put(id, new Placement(id, fx, Status.MISSING, null, saved, record, Map.of()));
                }
                case Cyclic(var cycle) -> {
                    problems.add("FX instance '%s' places %s, which would place itself (%s); it is kept as saved"
                            .formatted(InstanceTags.name(object), fx, describe(cycle)));
                    placements.put(id, new Placement(id, fx, Status.CYCLIC, null, saved, record, Map.of()));
                }
            }
        }

        private void place(CompoundTag object, UUID instance, ResourceLocation fx, InstanceTemplate template,
                           InstanceRecord record, @Nullable CompoundTag saved) {
            var remapper = new InstanceIds.Remapper(template.idMap(instance));
            var removedTops = new LinkedHashSet<>(record.removed());
            var dropped = dropped(template, removedTops);

            var unusedRemoved = new ArrayList<UUID>();
            for (var removed : removedTops) {
                if (!template.contains(removed)) unusedRemoved.add(removed);
            }
            var byTarget = new LinkedHashMap<UUID, List<InstanceRecord.Modification>>();
            var unusedModified = new ArrayList<InstanceRecord.Modification>();
            for (var modification : record.modified()) {
                var target = template.object(modification.target());
                if (target == null || dropped.contains(modification.target())
                        || !InstanceDiffRules.of(InstanceTags.type(target)).accepts(modification.change().path())) {
                    unusedModified.add(modification);
                } else {
                    byTarget.computeIfAbsent(modification.target(), key -> new ArrayList<>()).add(modification);
                }
            }
            var unusedAdded = new ArrayList<InstanceRecord.Added>();
            var anchored = anchors(template, instance, dropped, record, unusedAdded);

            var placed = new ArrayList<CompoundTag>();
            var applied = new HashMap<UUID, List<NbtPatch.Change>>();
            for (var source : template.objects()) {
                var sourceId = InstanceTags.id(source);
                if (sourceId == null || dropped.contains(sourceId) || template.object(sourceId) != source) {
                    continue;
                }
                var copy = remapper.apply(source);
                var modifications = byTarget.get(sourceId);
                if (modifications != null) {
                    var changes = modifications.stream().map(InstanceRecord.Modification::change).toList();
                    var result = NbtPatch.apply(InstanceTags.data(copy), changes);
                    copy.put(InstanceTags.DATA, result.tag());
                    var took = new ArrayList<NbtPatch.Change>();
                    for (var modification : modifications) {
                        if (result.unused().contains(modification.change())) {
                            unusedModified.add(modification);
                        } else {
                            took.add(modification.change());
                        }
                    }
                    if (!took.isEmpty()) applied.put(sourceId, took);
                }
                // a top-level object may save no parent at all; placed, it hangs under the instance
                var parent = template.parent(sourceId);
                InstanceTags.setParent(copy, InstanceIds.ROOT.equals(parent) ? instance : InstanceIds.xor(instance, parent));
                var children = new ArrayList<UUID>();
                for (var child : template.children(sourceId)) {
                    if (!dropped.contains(child)) children.add(InstanceIds.xor(instance, child));
                }
                children.addAll(anchored.getOrDefault(sourceId, List.of()));
                InstanceTags.setChildren(copy, children);
                var placedId = InstanceIds.xor(instance, sourceId);
                if (!taken.add(placedId)) {
                    problems.add("FX instance '%s' places an object whose id %s is already used".formatted(
                            InstanceTags.name(object), placedId));
                }
                placed.add(copy);
            }

            var data = InstanceTags.data(object);
            if (template.timeline() != null) {
                data.put(InstanceTags.TIMELINE, new InstanceIds.Remapper(template.idMap(instance), true).apply(template.timeline()));
            }
            var tops = new ArrayList<UUID>();
            for (var top : template.children(InstanceIds.ROOT)) {
                if (!dropped.contains(top)) tops.add(InstanceIds.xor(instance, top));
            }
            tops.addAll(anchored.getOrDefault(InstanceIds.ROOT, List.of()));
            for (var child : InstanceTags.children(object)) {
                if (!tops.contains(child) && outerById.containsKey(child)) tops.add(child);
            }
            InstanceTags.setChildren(object, tops);

            placedAfter.put(instance, placed);
            placements.put(instance, new Placement(instance, fx, Status.PLACED, template, saved,
                    new InstanceRecord(unusedModified, unusedRemoved, unusedAdded), Map.copyOf(applied)));
        }

        /** Every template object left out: a removed top and everything under it. */
        private static Set<UUID> dropped(InstanceTemplate template, Set<UUID> removedTops) {
            var dropped = new HashSet<UUID>();
            if (removedTops.isEmpty()) return dropped;
            for (var id : template.ids()) {
                var at = id;
                // bounded: a damaged source may save a parent loop
                for (int depth = 0; depth <= template.ids().size() && !InstanceIds.ROOT.equals(at); depth++) {
                    if (removedTops.contains(at)) {
                        dropped.add(id);
                        break;
                    }
                    at = template.parent(at);
                }
            }
            return dropped;
        }

        /**
         * Hangs each added object where the record says, by source id; one whose place is not placed goes under the
         * instance, and when the source does not have that place at all, its anchor is kept in {@code unused} for when
         * it is back.
         * Returns the added children per source id, in recorded order.
         */
        private Map<UUID, List<UUID>> anchors(InstanceTemplate template, UUID instance, Set<UUID> dropped,
                                              InstanceRecord record, List<InstanceRecord.Added> unused) {
            var out = new HashMap<UUID, List<UUID>>();
            for (var added : record.added()) {
                var child = outerById.get(added.object());
                if (child == null) {
                    continue;   // the object is gone from this FX; the anchor is nothing on its own
                }
                var target = added.target();
                if (!InstanceIds.ROOT.equals(target) && (!template.contains(target) || dropped.contains(target))) {
                    problems.add("'%s' hung under an object its FX instance no longer places; it moves under the instance"
                            .formatted(InstanceTags.name(child)));
                    if (!template.contains(target)) unused.add(added);
                    target = InstanceIds.ROOT;
                }
                InstanceTags.setParent(child, InstanceIds.ROOT.equals(target) ? instance : InstanceIds.xor(instance, target));
                var children = out.computeIfAbsent(target, key -> new ArrayList<>());
                if (!children.contains(added.object())) children.add(added.object());
            }
            return out;
        }
    }

    static String describe(List<ResourceLocation> cycle) {
        var out = new StringBuilder();
        for (var id : cycle) {
            if (!out.isEmpty()) out.append(" -> ");
            out.append(id);
        }
        return out.toString();
    }
}
