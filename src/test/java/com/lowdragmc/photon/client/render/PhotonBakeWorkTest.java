package com.lowdragmc.photon.client.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotonBakeWorkTest {

    private static PhotonWorldRenderState.Job job(int order) {
        return new PhotonWorldRenderState.Job(null, null, null, PhotonStage.LAST, order, 0, null, false, null, false);
    }

    private static final class TestUnit implements PhotonBakeWork.Unit {
        private final List<PhotonWorldRenderState.DrawJob> jobs;
        private final RuntimeException failure;
        private final Set<String> threads;
        volatile boolean generated;
        boolean finished;
        boolean discarded;

        TestUnit(Set<String> threads, RuntimeException failure, PhotonWorldRenderState.DrawJob... jobs) {
            this.threads = threads;
            this.failure = failure;
            this.jobs = List.of(jobs);
        }

        @Override
        public int weight() {
            return 1000;
        }

        @Override
        public void generate() {
            threads.add(Thread.currentThread().getName());
            if (failure != null) throw failure;
            generated = true;
        }

        @Override
        public void finish(List<PhotonWorldRenderState.DrawJob> out) {
            assertTrue(generated, "finished before it was generated");
            out.addAll(jobs);
            finished = true;
        }

        @Override
        public void discard() {
            discarded = true;
        }
    }

    @Test
    void jobsComeOutInTheOrderTheyWereAddedOrDeferred() {
        for (var parallel : new boolean[]{false, true}) {
            var threads = ConcurrentHashMap.<String>newKeySet();
            var a = job(0);
            var b = job(1);
            var c = job(2);
            var d = job(3);
            var e = job(4);
            var sink = new PhotonBakeWork.Sink();
            sink.add(a);
            sink.defer(new TestUnit(threads, null, b, c));
            sink.add(d);
            for (int i = 0; i < 16; i++) {
                sink.defer(new TestUnit(threads, null));
            }
            sink.defer(new TestUnit(threads, null, e));
            var out = new ArrayList<PhotonWorldRenderState.DrawJob>();
            sink.drainInto(out, parallel);
            assertEquals(List.of(a, b, c, d, e), out, "parallel: " + parallel);
            if (!parallel) {
                assertEquals(Set.of(Thread.currentThread().getName()), threads);
            }
        }
    }

    @Test
    void aFailedGenerationFreesEveryUnitAndRethrows() {
        for (var parallel : new boolean[]{false, true}) {
            var threads = ConcurrentHashMap.<String>newKeySet();
            var failure = new IllegalStateException("broken particle");
            var units = new ArrayList<TestUnit>();
            var sink = new PhotonBakeWork.Sink();
            for (int i = 0; i < 8; i++) {
                var unit = new TestUnit(threads, i == 5 ? failure : null, job(i));
                units.add(unit);
                sink.defer(unit);
            }
            var out = new ArrayList<PhotonWorldRenderState.DrawJob>();
            var thrown = assertThrows(IllegalStateException.class, () -> sink.drainInto(out, parallel));
            assertSame(failure, thrown);
            assertTrue(out.isEmpty());
            for (var unit : units) {
                assertTrue(unit.discarded, "a unit was not freed (parallel: " + parallel + ")");
                assertFalse(unit.finished);
            }
        }
    }

    @Test
    void onlyADrainSharedWithTheWorkersIsCounted() {
        for (var parallel : new boolean[]{false, true}) {
            var sink = new PhotonBakeWork.Sink();
            for (int i = 0; i < 4; i++) {
                sink.defer(new TestUnit(ConcurrentHashMap.newKeySet(), null, job(i)));
            }
            var before = PhotonBakeWork.parallelDrains();
            sink.drainInto(new ArrayList<>(), parallel);
            assertEquals(before + (parallel ? 1 : 0), PhotonBakeWork.parallelDrains(), "parallel: " + parallel);
        }
        var before = PhotonBakeWork.parallelDrains();
        new PhotonBakeWork.Sink().drainInto(new ArrayList<>(), true);
        assertEquals(before, PhotonBakeWork.parallelDrains(), "a drain with nothing deferred");
    }

    @Test
    void aBakeThatFailedFreesItsDeferredUnits() {
        var unit = new TestUnit(ConcurrentHashMap.newKeySet(), null, job(0));
        var sink = new PhotonBakeWork.Sink();
        sink.defer(unit);
        sink.discard();
        assertTrue(unit.discarded);
        assertFalse(unit.generated);
    }
}
