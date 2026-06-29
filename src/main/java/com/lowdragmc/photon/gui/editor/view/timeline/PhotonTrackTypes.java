package com.lowdragmc.photon.gui.editor.view.timeline;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.fx.timeline.ActivatorTrack;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.ControlTrack;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.TrackGroup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The built-in {@link TrackType} singletons, registered into {@code photon:timeline_track} via their
 * {@code @LDLRegisterClient}-annotated static fields (discovered by {@code findAnnotationStaticField}).
 * Each type bundles the {@link com.lowdragmc.photon.client.fx.timeline.Track} creator and its
 * {@link TrackEditor}. Add a new track type by registering another {@code TrackType} here (or in any mod).
 */
@OnlyIn(Dist.CLIENT)
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
}
