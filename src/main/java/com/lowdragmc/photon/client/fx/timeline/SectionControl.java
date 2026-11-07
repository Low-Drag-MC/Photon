package com.lowdragmc.photon.client.fx.timeline;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Moves a playing timeline between its sections from outside: Java, commands, the editor's preview. Requests take effect
 * at the next tick ({@link #jumpTo}) or at the end of the current pass ({@link #queue}, {@link #advance}); each returns
 * false when it does nothing (no such section, the timeline has ended, or the effect is being stopped). Client thread.
 */
public final class SectionControl {
    private final TimelinePlayer player;

    SectionControl(TimelinePlayer player) {
        this.player = player;
    }

    public boolean hasSections() {
        return player.clock().hasSections();
    }

    /** The section names, in time order. */
    public List<String> names() {
        return player.clock().names();
    }

    /** The section playing (a requested jump's target once requested); null before the first, or once it ended. */
    @Nullable
    public String current() {
        return playing() ? player.clock().current() : null;
    }

    /** Passes completed through the current section. */
    public int pass() {
        return player.clock().pass();
    }

    /** From the next tick on, play from the start of {@code section}. */
    public boolean jumpTo(String section) {
        return playing() && player.clock().jumpTo(section);
    }

    /** When this pass through the current section ends, go to {@code section} instead (seamless). */
    public boolean queue(String section) {
        return playing() && player.clock().queue(section);
    }

    /** Stop repeating the current section: when this pass ends, go where the section's "then" says. */
    public boolean advance() {
        return playing() && player.clock().advance();
    }

    /** Go to the release section, now or at the end of this pass as the timeline says. */
    public boolean release() {
        return playing() && player.clock().release();
    }

    public boolean isReleased() {
        return player.clock().isReleased();
    }

    public boolean hasRelease() {
        return player.clock().hasRelease();
    }

    private boolean playing() {
        return !player.isStopped() && !player.isFinished();
    }
}
