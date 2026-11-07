package com.lowdragmc.photon.client.fx.timeline;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The master clock of a timeline with sections. At the end of a pass through a section (a seam) it carries on, wraps
 * back, jumps or ends; requests from outside take effect at the next tick ({@link #applyPendingJump}) or at a seam,
 * never mid-evaluation. Without sections it is the plain clock: {@code time += rate}.
 */
public class SectionClock {
    /** A stretch of timeline whose signals still have to fire: {@code (from, to)}, or {@code (from, to]}. */
    public record Window(double from, double to, boolean inclusive) {
    }

    public interface JumpListener {
        /** The clock went from {@code from} to {@code to} without passing the time between. */
        void jumped(double from, double to);
    }

    private enum Exit { LINEAR, WRAP, JUMP, END }

    private static final int MAX_SEAMS_PER_STEP = 64;

    private final String[] names;
    private final double[] starts;
    private final double[] ends;
    private final int[] repeats;
    private final TimelineSection.Then[] thens;
    private final int[] targets;
    private final int release;
    private final boolean releaseNow;
    private final List<String> skipped = new ArrayList<>();

    private double time;
    /** -1 before the first section, {@code names.length} past the last. */
    private int current = -1;
    private int pass;
    private int pendingJump = -1;
    private int pendingQueue = -1;
    private int exitTarget = -1;
    private boolean advanceRequested;
    private boolean releasePending;
    private boolean releaseEntered;
    private boolean windingDown;
    private boolean[] visited;
    private boolean endPending;
    private boolean ended;
    private boolean jumped;
    private boolean follow = true;
    private boolean capped;
    private double frameCap = Double.POSITIVE_INFINITY;
    private double signalFrom = -1;
    private final List<Window> windows = new ArrayList<>();
    @Nullable
    private JumpListener jumpListener;

    public SectionClock(List<TimelineSection> sections, double duration, @Nullable String releaseSection, boolean releaseNow) {
        var sorted = new ArrayList<>(sections);
        sorted.sort(Comparator.comparingDouble(TimelineSection::start));
        // a section ends where the next one starts: one sharing a start, or starting at the content end, is empty
        var kept = new ArrayList<TimelineSection>();
        for (var section : sorted) {
            if (!kept.isEmpty() && section.start() <= kept.getLast().start()) {
                skipped.add(section.name());
            } else {
                kept.add(section);
            }
        }
        while (!kept.isEmpty() && kept.getLast().start() >= duration) {
            skipped.add(kept.removeLast().name());
        }
        var n = kept.size();
        names = new String[n];
        starts = new double[n];
        ends = new double[n];
        repeats = new int[n];
        thens = new TimelineSection.Then[n];
        targets = new int[n];
        for (int i = 0; i < n; i++) {
            var section = kept.get(i);
            names[i] = section.name();
            starts[i] = Math.max(0, section.start());
            repeats[i] = section.repeat();
            thens[i] = section.then();
        }
        for (int i = 0; i < n; i++) {
            ends[i] = i + 1 < n ? starts[i + 1] : duration;
            targets[i] = thens[i] == TimelineSection.Then.GOTO ? indexOf(kept.get(i).target()) : -1;
        }
        this.release = releaseSection == null ? -1 : indexOf(releaseSection);
        this.releaseNow = releaseNow;
        this.visited = new boolean[n];
    }

    /** Start (again) from the start of {@code startSection}, or from 0 when it is null or unknown. */
    public void begin(@Nullable String startSection) {
        var at = startSection == null ? -1 : indexOf(startSection);
        time = at >= 0 ? starts[at] : 0;
        current = segmentAt(time);
        pass = 0;
        pendingJump = pendingQueue = -1;
        advanceRequested = releasePending = windingDown = endPending = ended = jumped = capped = false;
        releaseEntered = current >= 0 && current == release;
        Arrays.fill(visited, false);
        signalFrom = Math.nextDown(time);
        windows.clear();
        frameCap = frameLimit();
    }

    // ---- requests ------------------------------------------------------------------------------------------------

    /** Next tick, play from the start of {@code section}. */
    public boolean jumpTo(String section) {
        var at = indexOf(section);
        if (at < 0 || !accepting()) return false;
        pendingJump = at;
        pendingQueue = -1;
        advanceRequested = false;
        releasePending = releaseEntered = false;
        return true;
    }

    /** When this pass through the current section ends, go to {@code section} instead of where it would go. */
    public boolean queue(String section) {
        var at = indexOf(section);
        if (at < 0 || !accepting()) return false;
        pendingQueue = at;
        releasePending = releaseEntered = false;
        return true;
    }

    /** Stop repeating: when this pass ends, go where the section's "then" says. */
    public boolean advance() {
        var at = pendingJump >= 0 ? pendingJump : current;
        if (!accepting() || at < 0) return false;
        advanceRequested = true;
        return true;
    }

    /** Go to the release section, now or at the end of this pass as the timeline says. */
    public boolean release() {
        if (release < 0 || isReleased() || !accepting()) return false;
        requestRelease();
        return true;
    }

    /**
     * The effect is being stopped (destroy): release if not already, then play the release chain out — a forever
     * section plays once, and going into a section already gone through on the way out ends it — and accept nothing
     * else. False (and nothing changes) without a release section or when the timeline has already ended.
     */
    public boolean beginWindDown() {
        if (release < 0 || windingDown || ended || endPending || current >= names.length) return false;
        if (!isReleased()) requestRelease();
        windingDown = true;
        if (current >= 0) visited[current] = true;
        return true;
    }

    private void requestRelease() {
        if (releaseNow) {
            pendingJump = release;
            pendingQueue = -1;
            advanceRequested = false;
        } else {
            pendingQueue = release;
        }
        releasePending = true;
    }

    private boolean accepting() {
        return follow && !windingDown && !ended && !endPending && current < names.length;
    }

    // ---- ticking -------------------------------------------------------------------------------------------------

    /** Apply a requested jump, if any (start of a tick, before evaluating). */
    public boolean applyPendingJump() {
        if (pendingJump < 0) return false;
        var to = pendingJump;
        pendingJump = -1;
        var from = time;
        enter(to);
        time = starts[to];
        signalFrom = Math.nextDown(time);
        jumped = true;
        if (jumpListener != null) jumpListener.jumped(from, time);
        return true;
    }

    /** Move on by {@code rate} ticks, handling every seam passed on the way (after evaluating). */
    public void step(double rate) {
        // frames until the next tick interpolate from the time just evaluated, so they stop at this section's seam
        frameCap = frameLimit();
        if (ended || endPending || !(rate > 0)) return;
        var t = time + rate;
        var seams = 0;
        while (t >= segmentEnd(current)) {
            var end = segmentEnd(current);
            if (++seams > MAX_SEAMS_PER_STEP) {
                t = current >= 0 ? starts[current] : 0;
                capped = true;
                break;
            }
            var exit = follow ? decide() : Exit.LINEAR;
            if (exit == Exit.LINEAR) {
                var next = current + 1;
                if (next >= names.length) {
                    current = names.length;
                    break;
                }
                if (windingDown && visited[next]) {
                    endAt(end);
                    return;
                }
                enter(next);
            } else if (exit == Exit.WRAP) {
                closeWindow(end);
                var start = starts[current];
                t = start + (t - end);
                pass++;
                moved(end, start);
            } else if (exit == Exit.JUMP) {
                if (windingDown && visited[exitTarget]) {
                    endAt(end);
                    return;
                }
                closeWindow(end);
                var to = exitTarget;
                t = starts[to] + (t - end);
                enter(to);
                moved(end, starts[to]);
            } else {
                endAt(end);
                return;
            }
        }
        time = t;
    }

    private Exit decide() {
        if (pendingQueue >= 0) {
            exitTarget = pendingQueue;
            pendingQueue = -1;
            advanceRequested = false;
            return Exit.JUMP;
        }
        if (current < 0) return Exit.LINEAR;
        if (repeatsAgain()) return Exit.WRAP;
        advanceRequested = false;
        return switch (thens[current]) {
            case END -> Exit.END;
            case GOTO -> {
                exitTarget = targets[current];
                yield exitTarget >= 0 ? Exit.JUMP : Exit.LINEAR;
            }
            case LINEAR -> Exit.LINEAR;
        };
    }

    private boolean repeatsAgain() {
        var repeat = repeats[current];
        return !advanceRequested && (repeat == TimelineSection.FOREVER ? !windingDown : pass + 1 < repeat);
    }

    private void enter(int section) {
        current = section;
        pass = 0;
        if (section == release) {
            releaseEntered = true;
            releasePending = false;
        }
        if (windingDown) visited[section] = true;
    }

    private void moved(double from, double to) {
        jumped = true;
        signalFrom = Math.nextDown(to);
        if (jumpListener != null) jumpListener.jumped(from, to);
    }

    private void endAt(double end) {
        closeWindow(end);
        signalFrom = end;
        time = end;
        endPending = true;
    }

    /** The signals of this pass not yet fired: up to the seam, and the seam itself only after the last section. */
    private void closeWindow(double end) {
        if (end > signalFrom) windows.add(new Window(signalFrom, end, current == names.length - 1));
    }

    /** The timeline ran into an end: the end evaluation has been done and nothing more happens. */
    public void markEnded() {
        endPending = false;
        ended = true;
    }

    // ---- what it reports -----------------------------------------------------------------------------------------

    public double time() {
        return time;
    }

    /** How far a frame may sample until the next tick: short of a seam that does not run on into the next section. */
    public double frameCap() {
        return frameCap;
    }

    private double frameLimit() {
        if (endPending || ended) return time;
        if (!follow || current >= names.length) return Double.POSITIVE_INFINITY;
        return seamBreaks() ? Math.nextDown(segmentEnd(current)) : Double.POSITIVE_INFINITY;
    }

    /** Whether the coming seam wraps, jumps or ends instead of running on into the next section. */
    private boolean seamBreaks() {
        if (pendingQueue >= 0) return true;
        if (current < 0) return false;
        if (repeatsAgain() || thens[current] == TimelineSection.Then.END
                || thens[current] == TimelineSection.Then.GOTO && targets[current] >= 0) {
            return true;
        }
        var next = current + 1;
        return windingDown && next < names.length && visited[next];
    }

    /** The section the clock is in (a requested jump's target once requested); null before the first or after the end. */
    @Nullable
    public String current() {
        var at = pendingJump >= 0 ? pendingJump : current;
        return at >= 0 && at < names.length && !ended && !endPending ? names[at] : null;
    }

    /** Passes completed through the current section. */
    public int pass() {
        return pass;
    }

    public boolean isReleased() {
        return releasePending || releaseEntered;
    }

    public boolean isWindingDown() {
        return windingDown;
    }

    public boolean endPending() {
        return endPending;
    }

    public boolean ended() {
        return ended;
    }

    public boolean hasSections() {
        return names.length > 0;
    }

    public boolean hasRelease() {
        return release >= 0;
    }

    public List<String> names() {
        return List.of(names);
    }

    /** Names of sections left out because they would be empty. */
    public List<String> skipped() {
        return skipped;
    }

    /** Whether a step ever crossed more seams than allowed and dropped the rest of itself. */
    public boolean capped() {
        return capped;
    }

    /** Whether the clock jumped (or wrapped) since this was last asked. */
    public boolean consumeJumped() {
        var was = jumped;
        jumped = false;
        return was;
    }

    public void follow(boolean follow) {
        this.follow = follow;
    }

    public boolean follow() {
        return follow;
    }

    public void jumpListener(@Nullable JumpListener jumpListener) {
        this.jumpListener = jumpListener;
    }

    /** Exclusive lower bound of the signals still to fire up to the clock. */
    public double signalFrom() {
        return signalFrom;
    }

    public void signalFrom(double signalFrom) {
        this.signalFrom = signalFrom;
    }

    /** The windows closed at seams since the last call, in order. */
    public List<Window> takeWindows() {
        if (windows.isEmpty()) return List.of();
        var out = List.copyOf(windows);
        windows.clear();
        return out;
    }

    // ---- sections ------------------------------------------------------------------------------------------------

    private int indexOf(String name) {
        if (name == null || name.isEmpty()) return -1;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return i;
        }
        return -1;
    }

    private int segmentAt(double t) {
        var at = -1;
        for (int i = 0; i < names.length; i++) {
            if (t >= starts[i]) at = i;
        }
        return at == names.length - 1 && at >= 0 && t >= ends[at] ? names.length : at;
    }

    private double segmentEnd(int segment) {
        if (segment < 0) return names.length > 0 ? starts[0] : Double.POSITIVE_INFINITY;
        if (segment >= names.length) return Double.POSITIVE_INFINITY;
        return ends[segment];
    }
}
