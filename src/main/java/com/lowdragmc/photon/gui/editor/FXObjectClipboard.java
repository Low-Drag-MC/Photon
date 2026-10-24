package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.photon.client.fx.FXData;
import com.lowdragmc.photon.client.fx.FXInstances;
import com.lowdragmc.photon.client.fx.instance.InstanceBaker;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceRecord;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.instance.InstanceTemplate;
import com.lowdragmc.photon.client.fx.instance.InstanceWriter;
import com.lowdragmc.photon.client.fx.timeline.ControlTrack;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.TrackGroup;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * A detached snapshot of FX objects and the tracks bound to them; every instantiation gets fresh ids. An FX
 * instance is copied as what it saves (its source and record), never as the objects it placed, so a paste is
 * another instance of the same source with the same overrides; an object an instance placed that is copied
 * without its instance becomes an object of its own.
 */
public final class FXObjectClipboard {
    private final Set<UUID> ids;
    private final List<CompoundTag> objects;
    private final List<Track> tracks = new ArrayList<>();
    /** copied instance id → the template it places, so ids of what it placed follow it to the copy */
    private final Map<UUID, InstanceTemplate> instances = new HashMap<>();

    /** {@code roots} are copied with all their descendants. */
    public FXObjectClipboard(List<IFXObject> roots, Timeline timeline) {
        var collected = new LinkedHashMap<UUID, IFXObject>();
        for (var root : roots) {
            root.executeAll(object -> {
                if (object instanceof IFXObject fxObject) collected.put(fxObject.id(), fxObject);
            });
        }
        ids = new HashSet<>(collected.keySet());
        // what a copied instance placed is placed again by the copy
        var placedByCopies = new HashSet<UUID>();
        var asRecords = new HashMap<UUID, CompoundTag>();
        var animated = FXData.animatedTransforms(timeline, collected.values());
        for (var object : collected.values()) {
            if (!(object instanceof FXInstanceObject instance) || placedByCopies.contains(instance.id())) continue;
            var placement = placementOf(instance, collected);
            if (placement == null) continue;
            asRecords.put(instance.id(), saved(instance, placement, collected, animated));
            if (placement.isPlaced()) {
                instances.put(instance.id(), placement.template());
                instance.executeAll(child -> {
                    if (child != instance && child instanceof IFXObject placed
                            && placement.template().contains(InstanceIds.xor(placed.id(), instance.id()))) {
                        placedByCopies.add(placed.id());
                    }
                });
            }
        }
        objects = new ArrayList<>();
        for (var object : collected.values()) {
            if (placedByCopies.contains(object.id())) continue;
            var saved = asRecords.get(object.id());
            objects.add(saved != null ? saved : Objects.requireNonNull(object.serializeWrapper(), "Cannot copy FX object").copy());
        }
        for (var track : timeline.tracks()) {
            var bound = boundTracks(track);
            if (bound != null) tracks.add(bound);
        }
    }

    /**
     * What {@code instance} was placed from: its own placement when it is an outermost instance, otherwise (an
     * instance another instance placed, copied without that one) its source's template as it is now.
     */
    @Nullable
    private static InstanceBaker.Placement placementOf(FXInstanceObject instance, Map<UUID, IFXObject> collected) {
        if (instance.getPlacement() != null) return instance.getPlacement();
        if (instance.getFx() == null) return null;
        if (FXInstances.templates().lookup(instance.getFx()) instanceof InstanceBaker.Found found) {
            return new InstanceBaker.Placement(instance.id(), instance.getFx(), InstanceBaker.Status.PLACED,
                    found.template(), null, InstanceRecord.EMPTY, Map.of());
        }
        return null;
    }

    /** {@code instance} as a file saves it: its record in place of its source's timeline and what it placed. */
    private static CompoundTag saved(FXInstanceObject instance, InstanceBaker.Placement placement, Map<UUID, IFXObject> collected,
                                     Map<UUID, Set<List<String>>> animated) {
        var tag = Objects.requireNonNull(instance.serializeWrapper(), "Cannot copy FX object").copy();
        var data = InstanceTags.data(tag);
        data.remove(InstanceTags.TIMELINE);
        data.remove(InstanceTags.OVERRIDES);
        if (placement.isPlaced()) {
            var subtree = new ArrayList<CompoundTag>();
            instance.executeAll(object -> {
                if (object instanceof IFXObject fxObject && collected.containsKey(fxObject.id())) {
                    var saved = fxObject.serializeWrapper();
                    if (saved != null) subtree.add(saved);
                }
            });
            var record = InstanceWriter.recordOf(subtree, placement, id -> animated.getOrDefault(id, Set.of()));
            if (!record.isPristine()) data.put(InstanceTags.OVERRIDES, record.write());
        } else if (placement.saved() != null && !placement.saved().isEmpty()) {
            data.put(InstanceTags.OVERRIDES, placement.saved().copy());
        }
        return tag;
    }

    /** The part of {@code track} that drives the copied objects, or null. */
    @Nullable
    private Track boundTracks(Track track) {
        if (track instanceof TrackGroup group) {
            var children = group.children().stream().map(this::boundTracks).filter(Objects::nonNull).toList();
            if (children.isEmpty()) return null;
            var copy = (TrackGroup) group.copy();
            copy.children().clear();
            copy.children().addAll(children);
            return copy;
        }
        if (ids.contains(track.targetId())) return track.copy();
        if (track instanceof ControlTrack && track.clips().stream().anyMatch(clip -> ids.contains(clip.targetId()))) {
            var copy = track.copy();
            copy.clips().removeIf(clip -> !ids.contains(clip.targetId()));
            return copy;
        }
        return null;
    }

    public FXData instantiate(UnaryOperator<UUID> parentOf) {
        return instantiate(parentOf, List.of());
    }

    /**
     * Fresh copies, parents before children; references between them follow the copies, and so do references
     * to what a copied instance placed. {@code parentOf} maps a root's original parent id to its new one;
     * {@code knownAs} are the ids of the effect pasted into, so an instance that would place it is not placed.
     */
    public FXData instantiate(UnaryOperator<UUID> parentOf, Collection<ResourceLocation> knownAs) {
        var remap = new HashMap<UUID, UUID>();
        for (var id : ids) {
            remap.put(id, UUID.randomUUID());
        }
        instances.forEach((instance, template) -> {
            var fresh = remap.get(instance);
            for (var placed : template.ids()) {
                remap.put(InstanceIds.xor(instance, placed), InstanceIds.xor(fresh, placed));
            }
        });
        var remapper = new InstanceIds.Remapper(remap);
        var copied = new ArrayList<CompoundTag>();
        for (var snapshot : objects) {
            copied.add(remapper.apply(snapshot));
        }
        var baked = FXInstances.bake(knownAs, InstanceTags.fxData(copied, null));
        var result = new FXData();
        var byId = new HashMap<UUID, IFXObject>();
        for (var tag : InstanceTags.objects(baked.fxData())) {
            // an object of a type this game does not have is left out, as reading an effect leaves it out
            var object = IFXObject.deserializeWrapper(tag);
            if (object == null) continue;
            result.objects().add(object);
            byId.put(object.id(), object);
        }
        FXData.attach(result.objects(), baked, new HashMap<>());
        for (var object : result.objects()) {
            var transform = object.transform();
            var parent = byId.get(transform._getInternalParentID());
            if (parent == null) {
                transform._setInternalParentID(parentOf.apply(transform._getInternalParentID()));
            } else {
                // linked here: what an instance placed may come before its parent in its source's order
                transform.parent(parent.transform(), false);
            }
            transform._setInternalChildID(transform._getInternalChildID().stream().filter(byId::containsKey).toList());
        }
        for (var track : tracks) {
            var copy = track.copy();
            retarget(copy, remap);
            result.timeline().tracks().add(copy);
        }
        return result;
    }

    private static void retarget(Track track, Map<UUID, UUID> remap) {
        if (track.targetId() != null) track.targetId(remap.getOrDefault(track.targetId(), track.targetId()));
        for (var clip : track.clips()) {
            if (clip.targetId() != null) clip.targetId(remap.getOrDefault(clip.targetId(), clip.targetId()));
        }
        if (track instanceof TrackGroup group) {
            for (var child : group.children()) retarget(child, remap);
        }
    }
}
