package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The section clock driven the way the timeline player drives it: jump, evaluate (fire signals), step. */
class SectionClockTest {
    private static final double EPS = 1e-9;

    private static TimelineSection section(String name, double start) {
        return new TimelineSection(name, start);
    }

    private static SectionClock clock(double duration, TimelineSection... sections) {
        return clock(duration, null, true, sections);
    }

    private static SectionClock clock(double duration, String release, boolean now, TimelineSection... sections) {
        var clock = new SectionClock(List.of(sections), duration, release, now);
        clock.begin(null);
        return clock;
    }

    /** Ticks the clock like the player does and records what each tick evaluated and fired. */
    private static final class Driver {
        final SectionClock clock;
        final double[] signals;
        final List<Double> evaluated = new ArrayList<>();
        final List<Double> fired = new ArrayList<>();

        Driver(SectionClock clock, double... signals) {
            this.clock = clock;
            this.signals = signals;
        }

        Driver ticks(int n, double rate) {
            for (int i = 0; i < n; i++) {
                if (clock.ended()) break;
                clock.applyPendingJump();
                var time = clock.time();
                evaluated.add(time);
                for (var window : clock.takeWindows()) fire(window.from(), window.to(), window.inclusive());
                if (clock.endPending()) {
                    clock.markEnded();
                    continue;
                }
                fire(clock.signalFrom(), time, true);
                clock.signalFrom(Math.max(clock.signalFrom(), time));
                clock.step(rate);
            }
            return this;
        }

        Driver ticks(int n) {
            return ticks(n, 1);
        }

        private void fire(double from, double to, boolean inclusive) {
            for (var signal : signals) {
                if (signal > from && (inclusive ? signal <= to : signal < to)) fired.add(signal);
            }
        }
    }

    // ---- without sections ----------------------------------------------------------------------------------------

    @Test
    void withoutSectionsItIsThePlainClock() {
        var clock = clock(50);
        var expected = 0.0;
        for (int i = 0; i < 200; i++) {
            assertEquals(expected, clock.time(), EPS);
            clock.applyPendingJump();
            clock.step(0.75);
            expected += 0.75;
        }
        assertNull(clock.current());
        assertFalse(clock.hasSections());
        assertTrue(clock.takeWindows().isEmpty());
    }

    @Test
    void withoutSectionsRequestsAreRefused() {
        var clock = clock(50);
        assertFalse(clock.jumpTo("Idle"));
        assertFalse(clock.queue("Idle"));
        assertFalse(clock.advance());
        assertFalse(clock.release());
        assertFalse(clock.beginWindDown());
    }

    // ---- linear ---------------------------------------------------------------------------------------------------

    @Test
    void linearSectionsPlayInTimeOrderAndTheLastEndsTheTimeline() {
        var clock = clock(30, section("A", 10), section("B", 20));
        var driver = new Driver(clock);
        assertNull(clock.current(), "before the first section");
        driver.ticks(10);
        assertEquals("A", clock.current());
        driver.ticks(10);
        assertEquals("B", clock.current());
        driver.ticks(15);
        assertNull(clock.current(), "past the last section");
        assertTrue(clock.time() > 30, "the clock runs on past the content end, as without sections");
        for (int i = 0; i < driver.evaluated.size(); i++) assertEquals(i, driver.evaluated.get(i), EPS);
    }

    @Test
    void aStartSectionStartsThere() {
        var clock = new SectionClock(List.of(section("Appear", 0), section("Idle", 20)), 60, null, true);
        clock.begin("Idle");
        assertEquals(20, clock.time(), EPS);
        assertEquals("Idle", clock.current());
        var driver = new Driver(clock, 20, 30).ticks(1);
        assertEquals(List.of(20.0), driver.fired, "a signal at the start section's start fires");
        clock.begin("nope");
        assertEquals(0, clock.time(), EPS);
    }

    // ---- repeating ------------------------------------------------------------------------------------------------

    @Test
    void aForeverSectionWrapsAtItsSeamWhichIsNeverEvaluated() {
        var clock = clock(90, section("Appear", 0), section("Idle", 20).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        var driver = new Driver(clock).ticks(200);
        assertFalse(driver.evaluated.contains(60.0), "the seam itself is never evaluated");
        assertEquals("Idle", clock.current());
        assertTrue(clock.pass() >= 3, "passes counted: " + clock.pass());
        for (var t : driver.evaluated.subList(20, driver.evaluated.size())) {
            assertTrue(t >= 20 && t < 60, "stays inside Idle: " + t);
        }
    }

    @Test
    void aWrapCarriesTheTimeLeftOver() {
        var clock = clock(90, section("Idle", 20).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        clock.begin("Idle");
        var driver = new Driver(clock);
        while (clock.time() < 59.5) driver.ticks(1, 0.75);
        assertEquals(59.75, clock.time(), EPS, "20 + 0.75k first passes 59.5 at 59.75");
        driver.ticks(1, 0.75);
        assertEquals(20.5, clock.time(), EPS, "59.75 + 0.75 = 60.5, which is 20.5 in the next pass");
        assertTrue(clock.consumeJumped());
        assertFalse(clock.consumeJumped(), "reported once");
    }

    @Test
    void aStepAcrossManySeamsLandsInsideTheSection() {
        var clock = clock(10, section("Pulse", 0).repeat(TimelineSection.FOREVER), section("End", 2));
        new Driver(clock).ticks(50, 5);
        assertTrue(clock.time() >= 0 && clock.time() < 2, "inside Pulse: " + clock.time());
        assertFalse(clock.capped());
    }

    @Test
    void aStepAcrossTooManySeamsIsCapped() {
        var clock = clock(10, section("Tiny", 0).repeat(TimelineSection.FOREVER), section("After", 0.001));
        new Driver(clock).ticks(2, 1);
        assertTrue(clock.capped());
        assertEquals(0, clock.time(), EPS);
    }

    @Test
    void aSectionRepeatsItsCountThenGoesOn() {
        var clock = clock(20, section("A", 0).repeat(3), section("B", 10));
        var driver = new Driver(clock).ticks(29);
        assertEquals("A", clock.current());
        assertEquals(2, clock.pass());
        driver.ticks(1);
        assertEquals("B", clock.current());
        assertEquals(10, clock.time(), EPS);
    }

    @Test
    void advanceStopsRepeating() {
        var clock = clock(20, section("A", 0).repeat(TimelineSection.FOREVER), section("B", 10));
        var driver = new Driver(clock).ticks(25);
        assertTrue(clock.advance());
        driver.ticks(4);
        assertEquals("A", clock.current(), "the pass in progress plays out");
        driver.ticks(1);
        assertEquals("B", clock.current());
        assertEquals(10, clock.time(), EPS);
    }

    @Test
    void advanceBeforeTheFirstSectionIsRefused() {
        var clock = clock(20, section("A", 5));
        assertFalse(clock.advance());
    }

    // ---- then ----------------------------------------------------------------------------------------------------

    @Test
    void thenEndEndsTheTimelineThere() {
        var clock = clock(20, section("Fizzle", 0).then(TimelineSection.Then.END), section("Blast", 10));
        var driver = new Driver(clock).ticks(11);
        assertTrue(clock.ended(), "the tick after the seam is the end evaluation");
        assertEquals(10, clock.time(), EPS, "time stops at the seam");
        assertFalse(driver.evaluated.stream().anyMatch(t -> t >= 10 && t != 10.0), "Blast never plays");
        assertNull(clock.current());
    }

    @Test
    void thenGotoJumps() {
        var clock = clock(30, section("A", 0).then(TimelineSection.Then.GOTO).target("C"), section("B", 10), section("C", 20));
        new Driver(clock).ticks(10);
        assertEquals("C", clock.current());
        assertEquals(20, clock.time(), EPS);
    }

    @Test
    void gotoAnUnknownSectionCarriesOn() {
        var clock = clock(30, section("A", 0).then(TimelineSection.Then.GOTO).target("gone"), section("B", 10));
        new Driver(clock).ticks(11);
        assertEquals("B", clock.current());
    }

    // ---- requests ------------------------------------------------------------------------------------------------

    @Test
    void aJumpTakesEffectAtTheNextTick() {
        var clock = clock(90, section("Appear", 0), section("Idle", 20).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        var driver = new Driver(clock).ticks(35);
        assertTrue(clock.jumpTo("Vanish"));
        assertEquals("Vanish", clock.current(), "reported at once");
        assertEquals(35, clock.time(), EPS, "but the clock has not moved yet");
        driver.ticks(1);
        assertEquals(60, driver.evaluated.getLast(), EPS);
        assertFalse(clock.jumpTo("nope"));
    }

    @Test
    void aQueuedSectionWinsOverRepeating() {
        var clock = clock(90, section("Idle", 0).repeat(TimelineSection.FOREVER), section("LoopB", 40).repeat(TimelineSection.FOREVER), section("End", 80));
        var driver = new Driver(clock).ticks(15);
        assertTrue(clock.queue("LoopB"));
        driver.ticks(24);
        assertEquals("Idle", clock.current(), "the pass in progress plays out");
        driver.ticks(1);
        assertEquals("LoopB", clock.current());
        assertEquals(40, clock.time(), EPS);
    }

    @Test
    void queueingTheCurrentSectionStartsItOver() {
        var clock = clock(20, section("A", 0).repeat(5), section("B", 10));
        var driver = new Driver(clock).ticks(25);
        assertEquals(2, clock.pass());
        clock.queue("A");
        driver.ticks(5);
        assertEquals("A", clock.current());
        assertEquals(0, clock.pass(), "a queued restart counts passes from the start");
    }

    @Test
    void aJumpDropsWhatWasQueuedButAQueueAfterItStands() {
        var clock = clock(40, section("A", 0).repeat(TimelineSection.FOREVER), section("B", 10), section("C", 20), section("D", 30));
        var driver = new Driver(clock).ticks(5);
        clock.queue("D");
        clock.jumpTo("B");
        driver.ticks(10);
        assertEquals("C", clock.current(), "the jump dropped the queued D: B carried on linearly into C");
        clock = clock(40, section("A", 0).repeat(TimelineSection.FOREVER), section("B", 10).repeat(TimelineSection.FOREVER), section("C", 20), section("D", 30));
        driver = new Driver(clock).ticks(5);
        clock.jumpTo("B");
        clock.queue("D");
        driver.ticks(1);
        assertEquals("B", clock.current());
        driver.ticks(10);
        assertEquals("D", clock.current(), "the queue made after the jump applies at B's seam");
    }

    // ---- release --------------------------------------------------------------------------------------------------

    private static SectionClock circle(boolean now) {
        return clock(90, "Vanish", now, section("Appear", 0), section("Idle", 20).repeat(TimelineSection.FOREVER), section("Vanish", 60));
    }

    @Test
    void anImmediateReleaseJumpsToTheReleaseSection() {
        var clock = circle(true);
        var driver = new Driver(clock).ticks(35);
        assertFalse(clock.isReleased());
        assertTrue(clock.release());
        assertTrue(clock.isReleased());
        assertFalse(clock.release(), "once");
        driver.ticks(1);
        assertEquals("Vanish", clock.current());
        assertTrue(clock.isReleased());
    }

    @Test
    void aReleaseAtTheSectionEndWaitsForTheSeam() {
        var clock = circle(false);
        var driver = new Driver(clock).ticks(35);
        clock.release();
        driver.ticks(24);
        assertEquals("Idle", clock.current());
        driver.ticks(1);
        assertEquals("Vanish", clock.current());
        assertEquals(60, clock.time(), EPS);
    }

    @Test
    void reachingTheReleaseSectionCountsAsReleased() {
        var clock = clock(90, "Vanish", true, section("Appear", 0), section("Idle", 20), section("Vanish", 60));
        new Driver(clock).ticks(61);
        assertTrue(clock.isReleased());
        assertFalse(clock.release());
    }

    @Test
    void jumpingAwayTakesTheReleaseBack() {
        var clock = circle(false);
        var driver = new Driver(clock).ticks(35);
        clock.release();
        assertTrue(clock.jumpTo("Idle"));
        assertFalse(clock.isReleased(), "the owner took it back");
        driver.ticks(1);
        assertTrue(clock.release(), "and can release again");
        clock = circle(true);
        driver = new Driver(clock).ticks(35);
        clock.release();
        driver.ticks(5);
        clock.jumpTo("Idle");
        driver.ticks(1);
        assertFalse(clock.isReleased(), "summoned back from the release section");
        assertTrue(clock.release());
    }

    @Test
    void windingDownPlaysTheReleaseAndAcceptsNothingElse() {
        var clock = circle(true);
        var driver = new Driver(clock).ticks(35);
        assertTrue(clock.beginWindDown());
        assertFalse(clock.beginWindDown(), "once");
        assertFalse(clock.jumpTo("Idle"));
        assertFalse(clock.queue("Idle"));
        assertFalse(clock.advance());
        assertFalse(clock.release());
        driver.ticks(1);
        assertEquals("Vanish", clock.current());
        driver.ticks(40);
        assertTrue(clock.time() > 90, "Vanish is the last section: the timeline ends as usual");
    }

    @Test
    void windingDownAfterAJumpKeepsTheJumpThenReleases() {
        var clock = clock(100, "Vanish", false, section("Fall", 0).repeat(TimelineSection.FOREVER), section("Impact", 20), section("Vanish", 40), section("Gone", 60));
        var driver = new Driver(clock).ticks(5);
        clock.jumpTo("Impact");
        assertTrue(clock.beginWindDown(), "the entity died in the same tick");
        driver.ticks(1);
        assertEquals("Impact", clock.current());
        driver.ticks(20);
        assertEquals("Vanish", clock.current(), "the release queued at Impact's seam still plays");
    }

    @Test
    void windingDownPlaysAForeverSectionOnceAndEndsOnGoingBack() {
        var clock = clock(90, "Vanish", false,
                section("Idle", 0).repeat(TimelineSection.FOREVER),
                section("Vanish", 40).then(TimelineSection.Then.GOTO).target("Idle"),
                section("Spare", 80));
        var driver = new Driver(clock).ticks(5);
        clock.beginWindDown();
        driver.ticks(35);
        assertEquals("Vanish", clock.current(), "the release waited for the seam");
        driver.ticks(41);
        assertTrue(clock.ended(), "going back into Idle on the way out ends it");
    }

    @Test
    void windingDownPlaysAForeverSectionReachedOnTheWayOutOnce() {
        var clock = clock(80, "Vanish", true,
                section("Idle", 0).repeat(TimelineSection.FOREVER),
                section("Vanish", 20).then(TimelineSection.Then.GOTO).target("Linger"),
                section("Linger", 40).repeat(TimelineSection.FOREVER),
                section("After", 60));
        var driver = new Driver(clock).ticks(5);
        clock.beginWindDown();
        driver.ticks(1 + 20);
        assertEquals("Linger", clock.current());
        driver.ticks(20);
        assertEquals("After", clock.current(), "a forever section on the way out plays once");
    }

    @Test
    void windingDownKeepsFiniteRepeats() {
        var clock = clock(90, "Vanish", true, section("Idle", 0).repeat(TimelineSection.FOREVER), section("Vanish", 40).repeat(2), section("Gone", 50));
        var driver = new Driver(clock).ticks(5);
        clock.beginWindDown();
        driver.ticks(1);
        assertEquals("Vanish", clock.current());
        driver.ticks(10);
        assertEquals("Vanish", clock.current());
        assertEquals(1, clock.pass(), "the second pass, not a re-entry");
        driver.ticks(10);
        assertEquals("Gone", clock.current());
    }

    @Test
    void windingDownWithoutAReleaseSectionIsRefused() {
        var clock = clock(90, section("Idle", 0).repeat(TimelineSection.FOREVER));
        assertFalse(clock.beginWindDown());
        assertFalse(clock.release());
    }

    // ---- signals -------------------------------------------------------------------------------------------------

    @Test
    void eachPassFiresItsSignalsAndNotTheNextSectionsStart() {
        var clock = clock(90, section("Appear", 0), section("Idle", 20).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        var driver = new Driver(clock, 0, 20, 40, 60).ticks(20 + 40 * 3);
        assertEquals(List.of(0.0, 20.0, 40.0, 20.0, 40.0, 20.0, 40.0), driver.fired);
        clock.advance();
        driver.ticks(45);
        assertEquals(60.0, driver.fired.getLast(), "Vanish's start fires once it is reached");
        assertEquals(1, driver.fired.stream().filter(t -> t == 60.0).count());
    }

    @Test
    void signalsBetweenFractionalTicksAndTheSeamStillFire() {
        var clock = clock(90, section("Idle", 0).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        var driver = new Driver(clock, 59.9).ticks(200, 0.75);
        assertTrue(driver.fired.size() >= 2, "59.9 fires on every pass: " + driver.fired);
    }

    @Test
    void theLastSectionsEndIsPartOfItsPass() {
        var clock = clock(60, section("Idle", 20).repeat(TimelineSection.FOREVER));
        var driver = new Driver(clock, 60).ticks(20 + 40 * 3 + 1);
        assertEquals(3, driver.fired.size(), "nothing starts at 60, so its signal belongs to each pass");
    }

    @Test
    void jumpingAwayDropsTheRestOfThePass() {
        var clock = clock(90, section("Idle", 0).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        var driver = new Driver(clock, 30, 60).ticks(10);
        clock.jumpTo("Vanish");
        driver.ticks(5);
        assertEquals(List.of(60.0), driver.fired);
    }

    @Test
    void anEndFiresTheRestOfItsPassButNotTheSeam() {
        var clock = clock(40, section("Fizzle", 0).then(TimelineSection.Then.END), section("Blast", 10));
        var driver = new Driver(clock, 9.5, 10).ticks(20, 0.75);
        assertEquals(List.of(9.5), driver.fired);
    }

    // ---- reporting -----------------------------------------------------------------------------------------------

    @Test
    void framesStopShortOfASeamTheClockJumpsAt() {
        var clock = clock(90, section("Appear", 0), section("Idle", 20).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        assertEquals(Double.POSITIVE_INFINITY, clock.frameCap(), "Appear just carries on");
        var driver = new Driver(clock).ticks(25);
        assertEquals(Math.nextDown(60.0), clock.frameCap(), "Idle wraps at 60");
        clock.follow(false);
        driver.ticks(1);
        assertEquals(Double.POSITIVE_INFINITY, clock.frameCap());
    }

    @Test
    void framesAfterAJumpStopAtTheSeamOfTheSectionTheyInterpolateIn() {
        var clock = clock(80, section("Idle", 20), section("Impact", 60).then(TimelineSection.Then.GOTO).target("Idle"));
        new Driver(clock).ticks(80);
        assertEquals("Idle", clock.current(), "Impact jumped back");
        assertEquals(Math.nextDown(80.0), clock.frameCap(), "the frames until the next tick still show Impact's end");
    }

    @Test
    void framesStopWhereAWindDownEndsOnASectionAlreadyPlayed() {
        var clock = new SectionClock(List.of(section("Out", 0), section("Loop", 10).repeat(TimelineSection.FOREVER)), 20, "Out", true);
        clock.begin("Loop");
        var driver = new Driver(clock).ticks(3);
        assertTrue(clock.beginWindDown());
        driver.ticks(10);
        assertTrue(clock.endPending(), "Out runs on into Loop, already played on the way out");
        assertEquals(Math.nextDown(10.0), clock.frameCap());
        driver.ticks(1);
        assertTrue(clock.ended());
    }

    @Test
    void notFollowingPlaysSectionsInTimeOrder() {
        var clock = clock(90, section("Appear", 0), section("Idle", 20).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        clock.follow(false);
        assertFalse(clock.jumpTo("Vanish"));
        var driver = new Driver(clock).ticks(70);
        assertEquals("Vanish", clock.current());
        for (int i = 0; i < driver.evaluated.size(); i++) assertEquals(i, driver.evaluated.get(i), EPS);
    }

    @Test
    void emptySectionsAreLeftOut() {
        var clock = new SectionClock(List.of(section("A", 0), section("Twin", 0), section("B", 10), section("Late", 50)), 50, null, true);
        assertEquals(List.of("A", "B"), clock.names());
        assertEquals(List.of("Twin", "Late"), clock.skipped());
    }

    @Test
    void theJumpListenerHearsWrapsAndJumps() {
        var clock = clock(90, section("Idle", 0).repeat(TimelineSection.FOREVER), section("Vanish", 60));
        var heard = new ArrayList<double[]>();
        clock.jumpListener((from, to) -> heard.add(new double[]{from, to}));
        var driver = new Driver(clock).ticks(61);
        assertEquals(1, heard.size());
        assertArrayEquals(new double[]{60, 0}, heard.getFirst(), EPS);
        clock.jumpTo("Vanish");
        driver.ticks(1);
        assertEquals(60, heard.getLast()[1], EPS);
    }
}
