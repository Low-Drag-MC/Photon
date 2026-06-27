package com.lowdragmc.photon.client.fx.timeline;

/**
 * The resolved per-object timeline state for a given tick: whether the object should advance its
 * simulation ({@link #tick}) and whether it should render ({@link #render}). This is the pure,
 * MC-free core of the timeline behavior so it can be unit-tested with plain JUnit.
 * <p>
 * Two axes drive it:
 * <ul>
 *   <li><b>Activator</b> tracks gate <i>visibility</i> with resume semantics.</li>
 *   <li><b>Control</b> tracks gate <i>lifecycle</i> (alive/ticking) and restart the object.</li>
 * </ul>
 * When both apply to the same object, the control track owns the lifecycle/ticking and the activator
 * track only masks rendering (see the combined "point 5" case).
 */
public record TimelineState(boolean tick, boolean render) {

    /**
     * @param hasActivator    the object is bound by at least one activator track
     * @param activatorActive the object is inside an activator clip right now (OR across tracks)
     * @param hasControl      the object is referenced by at least one control clip
     * @param controlActive   the object is inside a control clip right now
     */
    public static TimelineState resolve(boolean hasActivator, boolean activatorActive,
                                        boolean hasControl, boolean controlActive) {
        // alive/ticking: control owns lifecycle when present; otherwise the activator freezes it;
        // otherwise it just runs (default behavior).
        boolean alive = hasControl ? controlActive : (hasActivator ? activatorActive : true);
        // render only when alive AND not hidden by an activator track.
        boolean visible = hasActivator ? activatorActive : true;
        return new TimelineState(alive, alive && visible);
    }
}
