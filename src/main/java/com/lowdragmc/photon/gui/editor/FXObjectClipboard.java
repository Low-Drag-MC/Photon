package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.photon.client.fx.FXData;
import com.lowdragmc.photon.client.fx.timeline.ControlTrack;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.TrackGroup;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** A detached snapshot of FX objects and the tracks bound to them; every instantiation gets fresh ids. */
public final class FXObjectClipboard {
    private final Set<UUID> ids;
    private final List<CompoundTag> objects;
    private final List<Track> tracks = new ArrayList<>();

    /** {@code roots} are copied with all their descendants. */
    public FXObjectClipboard(List<IFXObject> roots, Timeline timeline) {
        var collected = new LinkedHashMap<UUID, IFXObject>();
        for (var root : roots) {
            root.executeAll(object -> {
                if (object instanceof IFXObject fxObject) collected.put(fxObject.id(), fxObject);
            });
        }
        ids = new HashSet<>(collected.keySet());
        objects = collected.values().stream()
                .map(object -> Objects.requireNonNull(object.serializeWrapper(), "Cannot copy FX object").copy())
                .toList();
        for (var track : timeline.tracks()) {
            var bound = boundTracks(track);
            if (bound != null) tracks.add(bound);
        }
    }

    /** Copies of whatever in {@code track} drives the copied objects, or null if nothing does. */
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

    /**
     * Objects (flat, parents before children) and tracks with fresh ids. References between copied objects
     * follow the copies; anything else still points where it did. {@code parentOf} maps a copied root's
     * original parent id to the parent it should land under.
     */
    public FXData instantiate(UnaryOperator<UUID> parentOf) {
        var remap = new HashMap<UUID, UUID>();
        var remapStrings = new HashMap<String, String>();
        for (var id : ids) {
            var fresh = UUID.randomUUID();
            remap.put(id, fresh);
            remapStrings.put(id.toString(), fresh.toString());
        }
        var fresh = new HashSet<>(remap.values());
        var result = new FXData();
        for (var snapshot : objects) {
            var object = Objects.requireNonNull(IFXObject.deserializeWrapper(remap(snapshot, remap, remapStrings)),
                    "Cannot paste FX object");
            var transform = object.transform();
            if (!fresh.contains(transform._getInternalParentID())) {
                transform._setInternalParentID(parentOf.apply(transform._getInternalParentID()));
            }
            transform._setInternalChildID(transform._getInternalChildID().stream().filter(fresh::contains).toList());
            result.objects().add(object);
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

    /** Object references are UUIDs in either codec form: a 4-int array or a string. */
    private static Tag remap(Tag tag, Map<UUID, UUID> remap, Map<String, String> remapStrings) {
        return switch (tag) {
            case CompoundTag compound -> {
                var copy = new CompoundTag();
                for (var key : compound.getAllKeys()) {
                    copy.put(key, remap(Objects.requireNonNull(compound.get(key)), remap, remapStrings));
                }
                yield copy;
            }
            case ListTag list -> {
                var copy = new ListTag();
                for (var child : list) copy.add(remap(child, remap, remapStrings));
                yield copy;
            }
            case IntArrayTag array when array.size() == 4 -> {
                var replacement = remap.get(UUIDUtil.uuidFromIntArray(array.getAsIntArray()));
                yield replacement == null ? array.copy() : NbtUtils.createUUID(replacement);
            }
            case StringTag string -> {
                var replacement = remapStrings.get(string.getAsString());
                yield replacement == null ? string : StringTag.valueOf(replacement);
            }
            default -> tag.copy();
        };
    }
}
