package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;

/**
 * A timeline attached to an FX: an ordered list of {@link Track}s, each bound to a child of root.
 * <p>
 * The timeline is the master clock for tracked objects. An <b>empty</b> timeline (no tracks) is the
 * backward-compatible default: it changes nothing, and existing FX without timeline data deserialize
 * to an empty timeline.
 */
@ParametersAreNonnullByDefault
public class Timeline implements INBTSerializable<CompoundTag> {
    private final List<Track> tracks = new ArrayList<>();

    public List<Track> tracks() {
        return tracks;
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    /** An independent copy of this timeline (provider-free, used by {@code FXData.copy}). */
    public Timeline copy() {
        var copied = new Timeline();
        for (var track : tracks) {
            copied.tracks.add(track.copy());
        }
        return copied;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var track : tracks) {
            var entry = new CompoundTag();
            entry.putString("type", track.name());
            entry.put("data", track.writeData(provider));
            list.add(entry);
        }
        tag.put("tracks", list);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        tracks.clear();
        for (var element : tag.getList("tracks", Tag.TAG_COMPOUND)) {
            if (element instanceof CompoundTag entry) {
                var type = entry.getString("type");
                var holder = PhotonRegistries.TIMELINE_TRACKS.get(type);
                if (holder == null) {
                    Photon.LOGGER.warn("Unknown timeline track type '{}' skipped while loading", type);
                    continue;
                }
                var track = holder.value().get();
                track.readData(provider, entry.getCompound("data"));
                tracks.add(track);
            }
        }
    }
}
