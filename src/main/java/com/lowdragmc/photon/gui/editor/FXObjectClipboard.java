package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.photon.client.fx.FXData;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.TrackGroup;
import com.lowdragmc.photon.client.fx.timeline.ControlTrack;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;

import java.util.*;

/** A detached snapshot; each paste remaps both objects and their internal references. */
public final class FXObjectClipboard {
    private final List<UUID> ids;
    private final List<CompoundTag> objects;
    private final List<CompoundTag> tracks;

    public FXObjectClipboard(List<IFXObject> roots, Timeline timeline) {
        var collected = new LinkedHashMap<UUID, IFXObject>();
        for (var root : roots) {
            root.executeAll(object -> {
                if (object instanceof IFXObject fxObject) collected.put(fxObject.id(), fxObject);
            });
        }
        ids = List.copyOf(collected.keySet());
        objects = collected.values().stream().map(object ->
                Objects.requireNonNull(object.serializeWrapper(), "Cannot copy FX object").copy()).toList();
        tracks = new ArrayList<>();
        for (var track : timeline.tracks()) {
            var filtered = copySelectedTrack(track, collected.keySet());
            if (filtered != null) tracks.add(Timeline.writeTrack(filtered, null));
        }
    }

    private static Track copySelectedTrack(Track track, Set<UUID> ids) {
        if (track instanceof TrackGroup group) {
            var copy = (TrackGroup) group.copy();
            copy.children().clear();
            for (var child : group.children()) {
                var selected = copySelectedTrack(child, ids);
                if (selected != null) copy.children().add(selected);
            }
            return copy.children().isEmpty() ? null : copy;
        }
        if (ids.contains(track.targetId())) return track.copy();
        if (track instanceof ControlTrack) {
            var copy = track.copy();
            copy.clips().removeIf(clip -> !ids.contains(clip.targetId()));
            return copy.clips().isEmpty() ? null : copy;
        }
        return null;
    }

    public FXData instantiate(UUID parentId) {
        var remapping = new HashMap<UUID, UUID>();
        for (var id : ids) remapping.put(id, UUID.randomUUID());
        var result = new FXData();
        for (var snapshot : objects) {
            var object = Objects.requireNonNull(IFXObject.deserializeWrapper(remap(snapshot, remapping)),
                    "Cannot paste FX object");
            var transform = object.transform();
            if (!remapping.containsValue(transform._getInternalParentID())) {
                transform._setInternalParentID(parentId);
            }
            transform._setInternalChildID(transform._getInternalChildID().stream()
                    .filter(remapping::containsValue).toList());
            result.objects().add(object);
        }
        for (var snapshot : tracks) {
            result.timeline().tracks().add(Objects.requireNonNull(
                    Timeline.readTrack(null, (CompoundTag) remap(snapshot, remapping)), "Cannot paste FX track"));
        }
        return result;
    }

    private static Tag remap(Tag tag, Map<UUID, UUID> ids) {
        if (tag instanceof CompoundTag compound) {
            var copy = compound.copy();
            for (var key : compound.keySet()) copy.put(key, remap(compound.get(key), ids));
            return copy;
        }
        if (tag instanceof ListTag list) {
            var copy = new ListTag();
            for (var child : list) copy.add(remap(child, ids));
            return copy;
        }
        // UUID codecs use int arrays in NBT. Only replace values present in the object ID map.
        if (tag instanceof IntArrayTag array && array.getAsIntArray().length == 4) {
            var original = UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
            var replacement = ids.get(original);
            if (replacement != null) {
                return UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, replacement).getOrThrow();
            }
        }
        if (tag instanceof StringTag string) {
            for (var entry : ids.entrySet()) {
                if (entry.getKey().toString().equals(string.value())) {
                    return StringTag.valueOf(entry.getValue().toString());
                }
            }
        }
        return tag.copy();
    }
}
