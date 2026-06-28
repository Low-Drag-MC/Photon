package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.timeline.Track;

import java.util.function.Supplier;

/**
 * A registered kind of {@link Track}: it drives track creation <b>and</b> provides the {@link
 * TrackEditor} (UI api). Registered as a {@code @LDLRegisterClient} static singleton in
 * {@code photon:timeline_track} (see {@code PhotonTrackTypes}); the timeline serializes/deserializes
 * tracks by this type's registry {@link #name()}.
 */
public final class TrackType {
    private final Class<? extends Track> trackClass;
    private final Supplier<? extends Track> creator;
    private final TrackEditor editor;

    public TrackType(Class<? extends Track> trackClass, Supplier<? extends Track> creator, TrackEditor editor) {
        this.trackClass = trackClass;
        this.creator = creator;
        this.editor = editor;
    }

    /** This type's registry name (reverse-looked-up from the singleton registry). */
    public String name() {
        return PhotonRegistries.TIMELINE_TRACKS.getKey(this);
    }

    public Class<? extends Track> trackClass() {
        return trackClass;
    }

    /** The UI editor provided by this track type. */
    public TrackEditor editor() {
        return editor;
    }

    /** Create a new track of this type (with its type back-reference set). */
    public Track create() {
        var track = creator.get();
        track.setType(this);
        return track;
    }
}
