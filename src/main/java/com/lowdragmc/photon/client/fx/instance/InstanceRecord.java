package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What an FX instance saves besides its own placement: Unity's {@code m_Modification}. Targets are ids in the
 * source's id space; values are in the containing FX's. Added objects are ordinary objects of the containing
 * FX, so only where each hangs (and in which order) is recorded here.
 */
public final class InstanceRecord {
    public static final InstanceRecord EMPTY = new InstanceRecord(List.of(), List.of(), List.of());

    public record Modification(UUID target, NbtPatch.Change change) {
    }

    /** Object {@code object} of the containing FX hangs under source object {@code target} ({@link InstanceIds#ROOT} = the instance). */
    public record Added(UUID target, UUID object) {
    }

    private final List<Modification> modified;
    private final List<UUID> removed;
    private final List<Added> added;

    public InstanceRecord(List<Modification> modified, List<UUID> removed, List<Added> added) {
        this.modified = List.copyOf(modified);
        this.removed = List.copyOf(removed);
        this.added = List.copyOf(added);
    }

    public List<Modification> modified() {
        return modified;
    }

    /** The tops of removed source subtrees. */
    public List<UUID> removed() {
        return removed;
    }

    public List<Added> added() {
        return added;
    }

    public boolean isPristine() {
        return modified.isEmpty() && removed.isEmpty() && added.isEmpty();
    }

    public CompoundTag write() {
        var tag = new CompoundTag();
        if (!modified.isEmpty()) {
            var list = new ListTag();
            for (var modification : modified) {
                var entry = modification.change().write();
                entry.putString("target", modification.target().toString());
                list.add(entry);
            }
            tag.put("modified", list);
        }
        if (!removed.isEmpty()) {
            var list = new ListTag();
            for (var id : removed) {
                list.add(StringTag.valueOf(id.toString()));
            }
            tag.put("removed", list);
        }
        if (!added.isEmpty()) {
            var list = new ListTag();
            for (var entry : added) {
                var compound = new CompoundTag();
                compound.putString("target", entry.target().toString());
                compound.putString("object", entry.object().toString());
                list.add(compound);
            }
            tag.put("added", list);
        }
        return tag;
    }

    /** A damaged entry is skipped rather than read as something else. */
    public static InstanceRecord read(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return EMPTY;
        }
        var modified = new ArrayList<Modification>();
        var list = tag.getListOrEmpty("modified");
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompoundOrEmpty(i);
            var target = InstanceIds.parse(entry.getStringOr("target", ""));
            var change = NbtPatch.Change.read(entry);
            if (target != null && change != null) {
                modified.add(new Modification(target, change));
            }
        }
        var removed = new ArrayList<UUID>();
        var removedList = tag.getListOrEmpty("removed");
        for (int i = 0; i < removedList.size(); i++) {
            var id = InstanceIds.parse(removedList.getStringOr(i, ""));
            if (id != null) {
                removed.add(id);
            }
        }
        var added = new ArrayList<Added>();
        var addedList = tag.getListOrEmpty("added");
        for (int i = 0; i < addedList.size(); i++) {
            var entry = addedList.getCompoundOrEmpty(i);
            var target = InstanceIds.parse(entry.getStringOr("target", ""));
            var object = InstanceIds.parse(entry.getStringOr("object", ""));
            if (target != null && object != null) {
                added.add(new Added(target, object));
            }
        }
        return new InstanceRecord(modified, removed, added);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InstanceRecord record && write().equals(record.write());
    }

    @Override
    public int hashCode() {
        return write().hashCode();
    }

    @Override
    public String toString() {
        return "InstanceRecord" + write();
    }
}
