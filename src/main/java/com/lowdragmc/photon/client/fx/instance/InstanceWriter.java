package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Saves an FX's live objects, writing each instance as its record: the inverse of {@link InstanceBaker}.
 * Overrides are computed, not recorded, by diffing the live objects against the template each instance was
 * <b>placed from</b>, so any kind of edit is caught and a newer source is not misread as edits. Which objects an
 * instance placed is worked out from ids alone, not from where they hang (a runtime parents an object whose parent
 * it cannot find to its root): {@code z} is placed by instance {@code I} when {@code z ^ I} is one of the template's ids.
 */
public final class InstanceWriter {

    private InstanceWriter() {
    }

    /**
     * @param objects    every live object serialized ({@code {type, data}}, instances in their baked form), in list order
     * @param timeline   the FX's own timeline, written as given
     * @param placements by instance id; an instance without one is written as it is
     * @param skipped    per live object id, further paths not to compare (transform channels a timeline drives)
     */
    public static CompoundTag write(List<CompoundTag> objects, @Nullable CompoundTag timeline,
                                    Map<UUID, InstanceBaker.Placement> placements,
                                    Function<UUID, Set<List<String>>> skipped) {
        return new Write(objects, placements, skipped).run(timeline);
    }

    /** The record {@code placement}'s instance would be saved with now. */
    public static InstanceRecord recordOf(List<CompoundTag> objects, InstanceBaker.Placement placement,
                                          Function<UUID, Set<List<String>>> skipped) {
        if (!placement.isPlaced()) return InstanceRecord.read(placement.saved());
        var write = new Write(objects, Map.of(placement.instance(), placement), skipped);
        write.collectPlaced();
        return write.record(placement);
    }

    /** Live id → source id of everything {@code placement}'s instance placed. */
    public static Map<UUID, UUID> placedBy(List<CompoundTag> objects, InstanceBaker.Placement placement) {
        var write = new Write(objects, Map.of(placement.instance(), placement), id -> Set.of());
        write.collectPlaced();
        var out = new LinkedHashMap<UUID, UUID>();
        write.placed.getOrDefault(placement.instance(), Map.of()).forEach((source, live) -> out.put(live, source));
        return out;
    }

    private static final class Write {
        private final List<CompoundTag> objects;
        private final Map<UUID, InstanceBaker.Placement> placements;
        private final Function<UUID, Set<List<String>>> skipped;
        private final Map<UUID, CompoundTag> byId = new LinkedHashMap<>();
        private final Map<UUID, List<UUID>> childrenOf = new HashMap<>();
        /** instance → source id → live id */
        private final Map<UUID, Map<UUID, UUID>> placed = new HashMap<>();
        /** instance → the live ids it placed */
        private final Map<UUID, Set<UUID>> placedLive = new HashMap<>();
        private final Set<UUID> allPlaced = new HashSet<>();

        Write(List<CompoundTag> objects, Map<UUID, InstanceBaker.Placement> placements,
              Function<UUID, Set<List<String>>> skipped) {
            this.objects = objects;
            this.placements = placements;
            this.skipped = skipped;
            for (var object : objects) {
                var id = InstanceTags.id(object);
                if (id != null) byId.putIfAbsent(id, object);
            }
            var unordered = new LinkedHashMap<UUID, List<UUID>>();
            for (var entry : byId.entrySet()) {
                var parent = InstanceTags.parent(entry.getValue());
                if (parent != null && byId.containsKey(parent)) {
                    unordered.computeIfAbsent(parent, key -> new ArrayList<>()).add(entry.getKey());
                }
            }
            unordered.forEach((parent, children) ->
                    childrenOf.put(parent, InstanceTemplate.childOrder(children, InstanceTags.children(byId.get(parent)))));
        }

        CompoundTag run(@Nullable CompoundTag timeline) {
            collectPlaced();
            var list = new ListTag();
            for (var object : objects) {
                var id = InstanceTags.id(object);
                if (id != null && allPlaced.contains(id)) {
                    continue;
                }
                var placement = id == null ? null : placements.get(id);
                list.add(placement == null ? object : instance(object, placement));
            }
            var fxData = new CompoundTag();
            fxData.put(InstanceTags.OBJECTS, list);
            if (timeline != null) {
                fxData.put(InstanceTags.TIMELINE, timeline);
            }
            return fxData;
        }

        void collectPlaced() {
            for (var placement : placements.values()) {
                if (!placement.isPlaced() || !byId.containsKey(placement.instance())) continue;
                var instance = placement.instance();
                var mine = new LinkedHashMap<UUID, UUID>();
                for (var source : placement.template().ids()) {
                    var live = InstanceIds.xor(instance, source);
                    if (byId.containsKey(live)) {
                        mine.put(source, live);
                        allPlaced.add(live);
                    }
                }
                placed.put(instance, mine);
                placedLive.put(instance, new HashSet<>(mine.values()));
            }
        }

        private CompoundTag instance(CompoundTag object, InstanceBaker.Placement placement) {
            var out = object.copy();
            var data = InstanceTags.data(out);
            data.remove(InstanceTags.TIMELINE);
            data.remove(InstanceTags.OVERRIDES);
            if (!placement.isPlaced()) {
                // a source that could not be read: save back what was read, nothing more
                if (placement.saved() != null && !placement.saved().isEmpty()) {
                    data.put(InstanceTags.OVERRIDES, placement.saved().copy());
                }
                return out;
            }
            var record = record(placement);
            if (!record.isPristine()) {
                data.put(InstanceTags.OVERRIDES, record.write());
            }
            // what it placed is rebuilt on load; only the objects of this FX are its saved children
            var mine = placedLive.getOrDefault(placement.instance(), Set.of());
            var kept = new ArrayList<UUID>();
            for (var child : InstanceTags.children(out)) {
                if (byId.containsKey(child) && !mine.contains(child)) kept.add(child);
            }
            InstanceTags.setChildren(out, kept);
            return out;
        }

        InstanceRecord record(InstanceBaker.Placement placement) {
            var template = placement.template();
            var instance = placement.instance();
            var mine = placed.getOrDefault(instance, Map.of());
            var remapper = new InstanceIds.Remapper(template.idMap(instance));

            var modified = new ArrayList<InstanceRecord.Modification>();
            var written = new HashMap<UUID, List<List<String>>>();
            for (var source : template.objects()) {
                var sourceId = InstanceTags.id(source);
                var live = sourceId == null ? null : mine.get(sourceId);
                if (live == null || template.object(sourceId) != source) continue;
                var pristine = InstanceTags.data(remapper.apply(source));
                var rules = new InstanceDiffRules(InstanceTags.type(source), skipped.apply(live));
                for (var change : NbtPatch.diff(pristine, InstanceTags.data(byId.get(live)), rules)) {
                    modified.add(new InstanceRecord.Modification(sourceId, change));
                    written.computeIfAbsent(sourceId, key -> new ArrayList<>()).add(change.path());
                }
            }
            // what did not apply when read, unless a live value now says something at, above or under its path:
            // written after it, the old value would win when read
            for (var unused : placement.unused().modified()) {
                var paths = written.getOrDefault(unused.target(), List.of());
                if (paths.stream().noneMatch(path -> NbtPatch.overlaps(path, unused.change().path()))) {
                    modified.add(unused);
                }
            }

            var removed = new ArrayList<UUID>();
            removedUnder(template, InstanceIds.ROOT, mine, placement.unreadable(), removed);
            removed.addAll(placement.unused().removed());

            var added = new ArrayList<InstanceRecord.Added>();
            var liveSet = placedLive.getOrDefault(instance, Set.of());
            for (var child : childrenOf.getOrDefault(instance, List.of())) {
                if (!liveSet.contains(child)) added.add(anchorUnderInstance(placement, child));
            }
            for (var source : template.objects()) {
                var sourceId = InstanceTags.id(source);
                var live = sourceId == null ? null : mine.get(sourceId);
                if (live != null && template.object(sourceId) == source) {
                    addedUnder(sourceId, live, liveSet, added);
                }
            }
            return new InstanceRecord(modified, removed, added);
        }

        /**
         * An object hanging straight under the instance keeps the anchor it was read with when that one's place
         * could not be found: it was only moved here for the time being.
         */
        private static InstanceRecord.Added anchorUnderInstance(InstanceBaker.Placement placement, UUID child) {
            for (var anchor : placement.unused().added()) {
                if (anchor.object().equals(child)) return anchor;
            }
            return new InstanceRecord.Added(InstanceIds.ROOT, child);
        }

        /** The tops of what the template has under {@code parent} and the live objects do not. */
        private static void removedUnder(InstanceTemplate template, UUID parent, Map<UUID, UUID> mine,
                                         Set<UUID> unreadable, List<UUID> out) {
            for (var child : template.children(parent)) {
                if (mine.containsKey(child) || unreadable.contains(child)) {
                    removedUnder(template, child, mine, unreadable, out);
                } else {
                    out.add(child);
                }
            }
        }

        private void addedUnder(UUID target, UUID live, Set<UUID> mine, List<InstanceRecord.Added> out) {
            for (var child : childrenOf.getOrDefault(live, List.of())) {
                if (!mine.contains(child)) {
                    out.add(new InstanceRecord.Added(target, child));
                }
            }
        }
    }
}
