package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.PhotonConfig;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The per-particle part of a frame's bake spread over worker threads. A {@link Sink} stands in for the job list a bake
 * writes into: a job added to it keeps its place, and a {@link Unit} deferred into it generates its geometry on any
 * thread, then adds its jobs on the render thread in the place it was deferred at, so the jobs come out in the order a
 * bake on one thread makes. Render thread only, but for {@link Unit#generate}.
 */
public final class PhotonBakeWork {
    /** Below this many particles a frame's units run on the render thread: handing them out would cost more. */
    private static final int MIN_PARALLEL_WEIGHT = 4096;
    private static final int WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
    private static ExecutorService pool;

    private PhotonBakeWork() {
    }

    /** Geometry generated off the render thread. */
    public interface Unit {
        /** About how many particles it generates, for scheduling. */
        int weight();

        /**
         * On any thread: the geometry, into the unit's own buffers. It may read the particles, its emitter and the
         * camera, and use the thread's own scratch; nothing that needs the render thread.
         */
        void generate();

        /** On the render thread, in order: the unit's jobs. */
        void finish(List<PhotonWorldRenderState.DrawJob> out);

        /** On the render thread, in place of {@link #finish} when the bake fails: frees what was generated. */
        void discard();
    }

    /** The bake's job list: the jobs added and the units deferred, in the order they came. */
    public static final class Sink extends AbstractList<PhotonWorldRenderState.DrawJob> {
        private final List<Object> slots = new ArrayList<>();
        private final List<Unit> units = new ArrayList<>();
        private long weight;

        @Override
        public boolean add(PhotonWorldRenderState.DrawJob job) {
            slots.add(job);
            return true;
        }

        @Override
        public PhotonWorldRenderState.DrawJob get(int index) {
            if (slots.get(index) instanceof PhotonWorldRenderState.DrawJob job) {
                return job;
            }
            throw new UnsupportedOperationException("a deferred unit has no job yet");
        }

        @Override
        public int size() {
            return slots.size();
        }

        public void defer(Unit unit) {
            slots.add(unit);
            units.add(unit);
            weight += unit.weight();
        }

        /** The bake failed before its units ran: what they hold is freed. */
        public void discard() {
            units.forEach(Unit::discard);
        }

        /** Generates the deferred units, on workers when there is enough to share, then fills {@code out} in order. */
        public void drainInto(List<PhotonWorldRenderState.DrawJob> out) {
            drainInto(out, units.size() > 1 && weight >= MIN_PARALLEL_WEIGHT && WORKERS > 1
                    && PhotonConfig.INSTANCE.parallelBake.get());
        }

        void drainInto(List<PhotonWorldRenderState.DrawJob> out, boolean parallel) {
            var failure = generate(parallel);
            if (failure != null) {
                units.forEach(Unit::discard);
                throw rethrow(failure);
            }
            var finished = 0;
            try {
                for (var slot : slots) {
                    if (slot instanceof PhotonWorldRenderState.DrawJob job) {
                        out.add(job);
                    } else {
                        ((Unit) slot).finish(out);
                        finished++;
                    }
                }
            } catch (RuntimeException | Error e) {
                for (int i = finished + 1; i < units.size(); i++) {
                    units.get(i).discard();
                }
                throw e;
            }
        }

        private Throwable generate(boolean parallel) {
            if (units.isEmpty()) {
                return null;
            }
            if (!parallel) {
                for (var unit : units) {
                    try {
                        unit.generate();
                    } catch (RuntimeException | Error e) {
                        return e;
                    }
                }
                return null;
            }
            // the heaviest first, pulled by the workers and the render thread alike
            var queue = new ArrayList<>(units);
            queue.sort(Comparator.comparingInt(Unit::weight).reversed());
            var next = new AtomicInteger();
            var failure = new AtomicReference<Throwable>();
            Runnable pull = () -> {
                for (int i = next.getAndIncrement(); i < queue.size() && failure.get() == null;
                     i = next.getAndIncrement()) {
                    try {
                        queue.get(i).generate();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }
            };
            var helpers = new ArrayList<Future<?>>();
            for (int i = 0, count = Math.min(WORKERS, queue.size() - 1); i < count; i++) {
                helpers.add(pool().submit(pull));
            }
            pull.run();
            for (var helper : helpers) {
                try {
                    helper.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, e);
                } catch (ExecutionException e) {
                    failure.compareAndSet(null, e.getCause());
                }
            }
            return failure.get();
        }
    }

    private static ExecutorService pool() {
        if (pool == null) {
            var index = new AtomicInteger();
            pool = Executors.newFixedThreadPool(WORKERS, task -> {
                var thread = new Thread(task, "Photon Bake #" + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return pool;
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException e) {
            return e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        return new IllegalStateException("Photon's bake failed", failure);
    }
}
