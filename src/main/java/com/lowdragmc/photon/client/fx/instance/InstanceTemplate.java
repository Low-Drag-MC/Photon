package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A source as instances place it: every object of the source with its own instances already placed, in the
 * source's id space, plus the source's own timeline. Immutable; shared by every instance placed from it.
 */
public final class InstanceTemplate {
    private final ResourceLocation id;
    private final List<CompoundTag> objects;
    @Nullable
    private final CompoundTag timeline;
    private final Set<ResourceLocation> dependencies;
    private final Map<UUID, CompoundTag> byId = new LinkedHashMap<>();
    private final Map<UUID, UUID> parentOf = new HashMap<>();
    private final Map<UUID, List<UUID>> childrenOf = new HashMap<>();

    /**
     * @param objects      {@code {type, data}} tags, not copied: the caller hands them over
     * @param dependencies every source this one places, at any depth
     */
    public InstanceTemplate(ResourceLocation id, List<CompoundTag> objects, @Nullable CompoundTag timeline,
                            Set<ResourceLocation> dependencies) {
        this.id = id;
        this.objects = List.copyOf(objects);
        this.timeline = timeline;
        this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        for (var object : this.objects) {
            var objectId = InstanceTags.id(object);
            if (objectId != null && !byId.containsKey(objectId)) {
                byId.put(objectId, object);
            }
        }
        // a parent the source does not have hangs off the root, as FXRuntime does it
        var listed = new HashMap<UUID, List<UUID>>();
        for (var entry : byId.entrySet()) {
            var parent = InstanceTags.parent(entry.getValue());
            parentOf.put(entry.getKey(), parent != null && byId.containsKey(parent) ? parent : InstanceIds.ROOT);
            listed.put(entry.getKey(), InstanceTags.children(entry.getValue()));
        }
        // a damaged source can save a parent loop, which nothing could reach from the root
        for (var start : byId.keySet()) {
            var seen = new HashSet<UUID>();
            var at = start;
            while (!InstanceIds.ROOT.equals(at) && seen.add(at)) {
                at = parentOf.get(at);
            }
            if (!InstanceIds.ROOT.equals(at)) {
                parentOf.put(at, InstanceIds.ROOT);
            }
        }
        var unordered = new LinkedHashMap<UUID, List<UUID>>();
        for (var child : byId.keySet()) {
            unordered.computeIfAbsent(parentOf.get(child), key -> new ArrayList<>()).add(child);
        }
        unordered.forEach((parent, children) -> childrenOf.put(parent,
                List.copyOf(childOrder(children, listed.getOrDefault(parent, List.of())))));
    }

    /**
     * {@code children} (in list order) in the order a runtime gives them: the parent's saved order, then the ones
     * it does not list, which {@code Transform} appends as they are attached.
     */
    static List<UUID> childOrder(List<UUID> children, List<UUID> saved) {
        var sorted = new ArrayList<>(children);
        sorted.sort((a, b) -> Integer.compare(rank(saved, a), rank(saved, b)));
        return sorted;
    }

    private static int rank(List<UUID> saved, UUID child) {
        var index = saved.indexOf(child);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    public ResourceLocation id() {
        return id;
    }

    /** In the source's list order. Read-only: they belong to every instance placed from this. */
    public List<CompoundTag> objects() {
        return objects;
    }

    @Nullable
    public CompoundTag timeline() {
        return timeline;
    }

    public Set<ResourceLocation> dependencies() {
        return dependencies;
    }

    public Set<UUID> ids() {
        return byId.keySet();
    }

    public boolean contains(UUID id) {
        return byId.containsKey(id);
    }

    @Nullable
    public CompoundTag object(UUID id) {
        return byId.get(id);
    }

    /** {@link InstanceIds#ROOT} for a top-level object. */
    public UUID parent(UUID id) {
        return parentOf.getOrDefault(id, InstanceIds.ROOT);
    }

    /** In authored order; {@link InstanceIds#ROOT} gives the top-level objects. */
    public List<UUID> children(UUID id) {
        return childrenOf.getOrDefault(id, List.of());
    }

    /** The id map an instance {@code instance} places with: the root to the instance, every object {@code y} to {@code instance ^ y}. */
    public Map<UUID, UUID> idMap(UUID instance) {
        var map = new HashMap<UUID, UUID>(byId.size() * 2 + 1);
        map.put(InstanceIds.ROOT, instance);
        for (var objectId : byId.keySet()) {
            map.put(objectId, InstanceIds.xor(instance, objectId));
        }
        return map;
    }

    /** Whether {@code other} places exactly what this does (a source baked again without having changed). */
    public boolean sameContent(InstanceTemplate other) {
        return id.equals(other.id) && objects.equals(other.objects) && Objects.equals(timeline, other.timeline);
    }

    @Override
    public String toString() {
        return "InstanceTemplate(" + id + ", " + objects.size() + " objects)";
    }
}
