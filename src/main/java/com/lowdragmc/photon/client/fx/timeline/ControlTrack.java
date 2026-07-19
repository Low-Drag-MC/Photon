package com.lowdragmc.photon.client.fx.timeline;


import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Control track (Phase 1a). Unlike {@link ActivatorTrack}, the header has no bound target — each
 * {@link Clip} is bound to its own FXObject (drag an object into the lane). A clip controls the
 * bound object's lifecycle: it restarts the object (age 0, fresh, applying the clip seed) at the
 * clip start and keeps it running for the clip's span. Outside its clips the object is not running.
 */
public class ControlTrack extends Track {

    public ControlTrack() {
    }

    /** The active clip bound to {@code objectId} at {@code time}, or {@code null}. */
    @Nullable
    public Clip clipForObjectAt(UUID objectId, double time) {
        for (var clip : clips) {
            if (objectId.equals(clip.targetId()) && clip.contains(time)) {
                return clip;
            }
        }
        return null;
    }
}
