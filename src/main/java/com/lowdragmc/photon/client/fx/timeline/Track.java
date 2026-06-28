package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.gui.editor.view.timeline.TrackType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A timeline track bound to a single FX object (a child of root), holding an ordered list of
 * {@link Clip}s. Tracks are created by a registered {@link TrackType} ({@code photon:timeline_track}),
 * which also supplies the track's UI editor; the track keeps a back-reference to its type.
 * <p>
 * Serialization is done manually here (rather than via {@code @Persisted}/codec dispatch) so the
 * timing-critical {@link Clip} stays free of any {@code net.minecraft} dependency and can be
 * unit-tested. Subclasses add per-track / per-clip data through the {@code *Extra} hooks.
 */
public abstract class Track {
    /** The type that created this track (set by {@link TrackType#create()}); drives name()/copy(). */
    @Nullable
    protected TrackType type;
    /** The bound object's transform id ({@code transform().id()}); {@code null} when unbound. */
    @Nullable
    protected UUID targetId;
    /** Optional display name (used by control tracks, which have no bound target object). */
    protected String displayName = "";
    /** Muted tracks are ignored by the player (as if the track did not exist). */
    protected boolean mute = false;
    /** Locked tracks cannot be edited in the UI (clips can't be added/moved/resized/removed). */
    protected boolean lock = false;
    protected final List<Clip> clips = new ArrayList<>();

    @Nullable
    public TrackType type() {
        return type;
    }

    public void setType(TrackType type) {
        this.type = type;
    }

    /** This track's registry name (from its {@link TrackType}); empty if somehow untyped. */
    public String name() {
        return type == null ? "" : type.name();
    }

    public String displayName() {
        return displayName;
    }

    public Track displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public boolean mute() {
        return mute;
    }

    public Track mute(boolean mute) {
        this.mute = mute;
        return this;
    }

    public boolean lock() {
        return lock;
    }

    public Track lock(boolean lock) {
        this.lock = lock;
        return this;
    }

    @Nullable
    public UUID targetId() {
        return targetId;
    }

    public Track targetId(@Nullable UUID targetId) {
        this.targetId = targetId;
        return this;
    }

    public List<Clip> clips() {
        return clips;
    }

    /** The active clip at {@code time}, or {@code null} when in a gap. */
    @Nullable
    public Clip clipAt(double time) {
        return Clip.activeClip(clips, time);
    }

    /**
     * An independent copy of this track (same registered type, copied target + clips). Provider-free
     * so it can be used from {@code FXData.copy(boolean)}.
     */
    public Track copy() {
        if (type == null) {
            throw new IllegalStateException("Track has no type: " + getClass());
        }
        var track = type.create();
        track.targetId = this.targetId;
        track.displayName = this.displayName;
        track.mute = this.mute;
        track.lock = this.lock;
        for (var clip : clips) {
            track.clips.add(clip.copy());
        }
        copyExtra(track);
        return track;
    }

    /** Hook for subclasses to copy additional track data into {@code target}. */
    protected void copyExtra(Track target) {
    }

    public CompoundTag writeData(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        if (targetId != null) {
            tag.putUUID("target", targetId);
        }
        tag.putString("name", displayName);
        tag.putBoolean("mute", mute);
        tag.putBoolean("lock", lock);
        var list = new ListTag();
        for (var clip : clips) {
            var c = new CompoundTag();
            c.putDouble("start", clip.start());
            c.putDouble("duration", clip.duration());
            c.putFloat("speed", clip.speed());
            if (clip.targetId() != null) {
                c.putUUID("clipTarget", clip.targetId());
            }
            c.putLong("seed", clip.seed());
            c.putBoolean("randomSeed", clip.randomSeed());
            writeClipExtra(clip, c, provider);
            list.add(c);
        }
        tag.put("clips", list);
        writeExtra(tag, provider);
        return tag;
    }

    public void readData(HolderLookup.Provider provider, CompoundTag tag) {
        targetId = tag.hasUUID("target") ? tag.getUUID("target") : null;
        displayName = tag.getString("name");
        mute = tag.getBoolean("mute");
        lock = tag.getBoolean("lock");
        clips.clear();
        for (var t : tag.getList("clips", Tag.TAG_COMPOUND)) {
            if (t instanceof CompoundTag c) {
                var clip = new Clip(c.getDouble("start"), c.getDouble("duration"), c.getFloat("speed"));
                if (c.hasUUID("clipTarget")) {
                    clip.targetId(c.getUUID("clipTarget"));
                }
                clip.seed(c.getLong("seed")).randomSeed(c.getBoolean("randomSeed"));
                readClipExtra(clip, c, provider);
                clips.add(clip);
            }
        }
        readExtra(tag, provider);
    }

    /** Hook for subclasses to persist additional track-level data. */
    protected void writeExtra(CompoundTag tag, HolderLookup.Provider provider) {
    }

    protected void readExtra(CompoundTag tag, HolderLookup.Provider provider) {
    }

    /** Hook for subclasses to persist additional per-clip data (e.g. animation curves). */
    protected void writeClipExtra(Clip clip, CompoundTag clipTag, HolderLookup.Provider provider) {
    }

    protected void readClipExtra(Clip clip, CompoundTag clipTag, HolderLookup.Provider provider) {
    }
}
