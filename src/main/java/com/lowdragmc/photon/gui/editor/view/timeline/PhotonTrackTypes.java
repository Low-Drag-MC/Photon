package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.fx.timeline.ActivatorTrack;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.AudioTrack;
import com.lowdragmc.photon.client.fx.timeline.ControlTrack;
import com.lowdragmc.photon.client.fx.timeline.PostProcessTrack;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.SpeedTrack;
import com.lowdragmc.photon.client.fx.timeline.TrackGroup;

/**
 * The built-in {@link TrackType} singletons, registered into {@code photon:timeline_track} via their
 * {@code @LDLRegisterClient}-annotated static fields (discovered by {@code findAnnotationStaticField}).
 * Each type bundles the {@link com.lowdragmc.photon.client.fx.timeline.Track} creator and its
 * {@link TrackEditor}. Add a new track type by registering another {@code TrackType} here (or in any mod).
 */
public class PhotonTrackTypes {

    @LDLRegisterClient(name = "activator", registry = "photon:timeline_track")
    public static final TrackType ACTIVATOR = new TrackType(ActivatorTrack.class, ActivatorTrack::new, new ActivatorTrackEditor());

    @LDLRegisterClient(name = "control", registry = "photon:timeline_track")
    public static final TrackType CONTROL = new TrackType(ControlTrack.class, ControlTrack::new, new ControlTrackEditor());

    @LDLRegisterClient(name = "animation", registry = "photon:timeline_track")
    public static final TrackType ANIMATION = new TrackType(AnimationTrack.class, AnimationTrack::new, new AnimationTrackEditor());

    @LDLRegisterClient(name = "signal", registry = "photon:timeline_track")
    public static final TrackType SIGNAL = new TrackType(SignalTrack.class, SignalTrack::new, new SignalTrackEditor());

    @LDLRegisterClient(name = "group", registry = "photon:timeline_track")
    public static final TrackType GROUP = new TrackType(TrackGroup.class, TrackGroup::new, new TrackGroupEditor());

    @LDLRegisterClient(name = "speed", registry = "photon:timeline_track")
    public static final TrackType SPEED = new TrackType(SpeedTrack.class, SpeedTrack::new, new SpeedTrackEditor());

    @LDLRegisterClient(name = "audio", registry = "photon:timeline_track")
    public static final TrackType AUDIO = new TrackType(AudioTrack.class, AudioTrack::new, new AudioTrackEditor());

    @LDLRegisterClient(name = "post_process", registry = "photon:timeline_track")
    public static final TrackType POST_PROCESS = new TrackType(
            PostProcessTrack.class,
            PostProcessTrack::new,
            new PostProcessTrackEditor());
}
