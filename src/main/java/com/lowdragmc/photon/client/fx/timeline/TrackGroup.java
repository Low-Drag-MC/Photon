package com.lowdragmc.photon.client.fx.timeline;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * A container track (Unity-style) holding an ordered list of child {@link Track}s (including nested
 * groups). It has no clips/target of its own; its {@link #displayName()} is a renamable label. The
 * player flattens groups away ({@link Timeline#leafTracks(boolean)}) — a muted group's whole subtree is
 * ignored. Child (de)serialization reuses {@link Timeline#writeTrack}/{@link Timeline#readTrack}.
 */
@OnlyIn(Dist.CLIENT)
public class TrackGroup extends Track {
    private final List<Track> children = new ArrayList<>();

    public TrackGroup() {
    }

    public List<Track> children() {
        return children;
    }

    @Override
    protected void copyExtra(Track target) {
        if (target instanceof TrackGroup group) {
            for (var child : children) {
                group.children.add(child.copy());
            }
        }
    }

    @Override
    protected void writeExtra(CompoundTag tag, HolderLookup.Provider provider) {
        var list = new ListTag();
        for (var child : children) {
            list.add(Timeline.writeTrack(child, provider));
        }
        tag.put("children", list);
    }

    @Override
    protected void readExtra(CompoundTag tag, HolderLookup.Provider provider) {
        children.clear();
        for (var t : tag.getList("children", Tag.TAG_COMPOUND)) {
            if (t instanceof CompoundTag c) {
                var child = Timeline.readTrack(provider, c);
                if (child != null) {
                    children.add(child);
                }
            }
        }
    }
}
