package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;

/**
 * A timeline attached to an FX: a tree of {@link Track}s. The top-level {@link #tracks()} are the roots;
 * a {@link TrackGroup} nests further tracks. Each leaf track is bound to a child of root.
 * <p>
 * The timeline is the master clock for tracked objects. An <b>empty</b> timeline (no tracks) is the
 * backward-compatible default: it changes nothing, and existing FX without timeline data deserialize
 * to an empty timeline.
 */
@ParametersAreNonnullByDefault
public class Timeline implements INBTSerializable<CompoundTag> {
    private final List<Track> tracks = new ArrayList<>();
    /** Named bookmarks on the master clock: snapping aids + visual markers (see {@link Marker}). */
    private final List<Marker> markers = new ArrayList<>();

    public List<Track> tracks() {
        return tracks;
    }

    public List<Marker> markers() {
        return markers;
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    /**
     * Depth-first list of all non-group (leaf) tracks. When {@code includeMuted} is false, a muted
     * {@link TrackGroup}'s whole subtree is skipped (muted group = as if absent). A leaf's own
     * {@link Track#mute()} is left to the caller.
     */
    public List<Track> leafTracks(boolean includeMuted) {
        var out = new ArrayList<Track>();
        collectLeaves(tracks, includeMuted, out);
        return out;
    }

    private static void collectLeaves(List<Track> list, boolean includeMuted, List<Track> out) {
        for (var track : list) {
            if (track instanceof TrackGroup group) {
                if (!includeMuted && group.mute()) continue;
                collectLeaves(group.children(), includeMuted, out);
            } else {
                out.add(track);
            }
        }
    }

    /**
     * The timeline's content end in master-clock ticks: the max {@link Track#contentEnd()} over all
     * non-muted leaf tracks (a muted leaf or muted group subtree contributes nothing, matching what
     * the player evaluates). 0 for an empty timeline. The timeline is finished once the clock passes
     * it — after that, activator/control-gated objects stay permanently inactive and no signal,
     * animation key, or audio clip remains. Cheap on-demand tree walk; not cached here (the player
     * snapshots it per playback in {@code TimelinePlayer.begin}).
     */
    public double getDuration() {
        double duration = 0;
        for (var track : leafTracks(false)) {
            if (track.mute()) continue;
            duration = Math.max(duration, track.contentEnd());
        }
        return duration;
    }

    /**
     * Ripple-insert {@code deltaTicks} of empty time at {@code atTick} across the whole timeline: every
     * marker and every track's content at or after the insertion point shifts later by {@code delta}
     * (each track decides how — see {@link Track#insertTime}). A no-op for {@code delta == 0}.
     */
    public void insertTime(double atTick, double deltaTicks) {
        if (deltaTicks == 0) {
            return;
        }
        Marker.insertTime(markers, atTick, deltaTicks);
        for (var track : tracks) {
            track.insertTime(atTick, deltaTicks);
        }
    }

    /** The children-list (root list or a group's children) that directly contains {@code track}, or null. */
    @Nullable
    public List<Track> parentListOf(Track track) {
        return findParentList(tracks, track);
    }

    @Nullable
    private static List<Track> findParentList(List<Track> list, Track track) {
        if (list.contains(track)) return list;
        for (var t : list) {
            if (t instanceof TrackGroup group) {
                var found = findParentList(group.children(), track);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Whether {@code track} is {@code maybeAncestor} itself or nested anywhere inside it. */
    public static boolean isDescendant(Track maybeAncestor, Track track) {
        if (maybeAncestor == track) return true;
        if (maybeAncestor instanceof TrackGroup group) {
            for (var child : group.children()) {
                if (isDescendant(child, track)) return true;
            }
        }
        return false;
    }

    /** An independent copy of this timeline (provider-free, used by {@code FXData.copy}). */
    public Timeline copy() {
        var copied = new Timeline();
        for (var track : tracks) {
            copied.tracks.add(track.copy());
        }
        for (var marker : markers) {
            copied.markers.add(marker.copy());
        }
        return copied;
    }

    /** Serialize one track to a {@code {type, data}} entry (shared by Timeline + {@link TrackGroup}). */
    public static CompoundTag writeTrack(Track track, HolderLookup.Provider provider) {
        var entry = new CompoundTag();
        entry.putString("type", track.name());
        entry.put("data", track.writeData(provider));
        return entry;
    }

    /** Deserialize one {@code {type, data}} entry, or null if the type is unknown (warns). */
    @Nullable
    public static Track readTrack(HolderLookup.Provider provider, CompoundTag entry) {
        var type = entry.getString("type");
        var trackType = PhotonRegistries.TIMELINE_TRACKS.get(type);
        if (trackType == null) {
            Photon.LOGGER.warn("Unknown timeline track type '{}' skipped while loading", type);
            return null;
        }
        var track = trackType.create();
        track.readData(provider, entry.getCompound("data"));
        return track;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var track : tracks) {
            list.add(writeTrack(track, provider));
        }
        tag.put("tracks", list);
        var markerList = new ListTag();
        for (var marker : markers) {
            var m = new CompoundTag();
            m.putDouble("tick", marker.tick());
            m.putString("name", marker.name());
            markerList.add(m);
        }
        tag.put("markers", markerList);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        tracks.clear();
        for (var element : tag.getList("tracks", Tag.TAG_COMPOUND)) {
            if (element instanceof CompoundTag entry) {
                var track = readTrack(provider, entry);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }
        markers.clear();
        // absent "markers" key (legacy .fx) deserializes to an empty list — backward compatible
        for (var element : tag.getList("markers", Tag.TAG_COMPOUND)) {
            if (element instanceof CompoundTag m) {
                markers.add(new Marker(m.getDouble("tick"), m.getString("name")));
            }
        }
    }
}
