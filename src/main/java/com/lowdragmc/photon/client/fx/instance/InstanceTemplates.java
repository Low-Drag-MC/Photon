package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baked sources by id, each baked once (its own instances placed first) and kept until invalidated. A source
 * already being baked further out is a cycle: the instance that closes it gets {@link InstanceBaker.Cyclic},
 * the cycle's members are not cached (what they bake to depends on where the cycle was entered), and every
 * source holding the cycle is cached as usual. Each cycle is reported once.
 */
public final class InstanceTemplates implements InstanceBaker.Templates {

    @FunctionalInterface
    public interface Source {
        /** A source's FX data tag ({@code {fxObjects, timeline}}), already datafixed; null when it cannot be read. */
        @Nullable
        CompoundTag read(Identifier id);
    }

    @FunctionalInterface
    public interface Canonicalizer {
        /** The object as the current code saves it; {@code object} may be returned as is. */
        CompoundTag apply(CompoundTag object);
    }

    @FunctionalInterface
    public interface Reporter {
        void report(String problem);
    }

    private final Source source;
    private final Canonicalizer canonicalizer;
    private final Reporter reporter;
    private final Map<Identifier, InstanceTemplate> cache = new HashMap<>();
    /** source → who places it directly (templates and FX definitions alike) */
    private final Map<Identifier, Set<Identifier>> dependents = new HashMap<>();
    private final List<Identifier> baking = new ArrayList<>();
    private final Set<Identifier> onCycle = new HashSet<>();
    private final Set<String> reported = new HashSet<>();

    public InstanceTemplates(Source source, Canonicalizer canonicalizer, Reporter reporter) {
        this.source = source;
        this.canonicalizer = canonicalizer;
        this.reporter = reporter;
    }

    @Override
    public synchronized InstanceBaker.Lookup lookup(Identifier id) {
        int at = baking.indexOf(id);
        if (at >= 0) {
            var cycle = new ArrayList<>(baking.subList(at, baking.size()));
            onCycle.addAll(cycle);
            cycle.add(id);
            return new InstanceBaker.Cyclic(List.copyOf(cycle));
        }
        var cached = cache.get(id);
        if (cached != null) {
            // baked on its own, it may still lead back to what is being baked now
            for (int i = 0; i < baking.size(); i++) {
                if (cached.dependencies().contains(baking.get(i))) {
                    var cycle = new ArrayList<>(baking.subList(i, baking.size()));
                    onCycle.addAll(cycle);
                    cycle.add(id);
                    cycle.add(baking.get(i));
                    return new InstanceBaker.Cyclic(List.copyOf(cycle));
                }
            }
            return new InstanceBaker.Found(cached);
        }
        var raw = source.read(id);
        if (raw == null) {
            return InstanceBaker.MISSING;
        }
        baking.add(id);
        InstanceBaker.Result baked;
        try {
            baked = InstanceBaker.bake(raw, this);
        } finally {
            baking.removeLast();
        }
        baked.problems().forEach(problem -> report(id + ": " + problem));
        noteDependencies(id, InstanceBaker.directSources(raw));
        var objects = new ArrayList<CompoundTag>();
        for (var object : InstanceTags.objects(baked.fxData())) {
            objects.add(canonicalizer.apply(object));
        }
        var timeline = raw.getCompound(InstanceTags.TIMELINE).isPresent() ? raw.getCompoundOrEmpty(InstanceTags.TIMELINE).copy() : null;
        var template = new InstanceTemplate(id, objects, timeline, baked.dependencies());
        if (!onCycle.remove(id)) {
            cache.put(id, template);
        }
        return new InstanceBaker.Found(template);
    }

    /**
     * Bake an FX that is not a template here, e.g. a definition being loaded or a project being opened.
     * {@code as} are the ids it is known by: an instance in it that leads back to one of them is a cycle.
     */
    public synchronized InstanceBaker.Result bake(Collection<Identifier> as, CompoundTag fxData) {
        var pushed = new ArrayList<Identifier>();
        for (var id : as) {
            if (!baking.contains(id)) {
                baking.add(id);
                pushed.add(id);
            }
        }
        try {
            return InstanceBaker.bake(fxData, this);
        } finally {
            baking.removeAll(pushed);
            pushed.forEach(onCycle::remove);
            for (var id : as) {
                noteDependencies(id, InstanceBaker.directSources(fxData));
            }
        }
    }

    /** Remember that {@code dependent} places {@code sources}, so invalidating one of them drops it too. */
    public synchronized void noteDependencies(Identifier dependent, Collection<Identifier> sources) {
        for (var source : sources) {
            dependents.computeIfAbsent(source, key -> new LinkedHashSet<>()).add(dependent);
        }
    }

    /**
     * Forget {@code ids} and everything that places them, at any depth.
     *
     * @return every id forgotten, {@code ids} included, so the caller can drop what it built from them
     */
    public synchronized Set<Identifier> invalidate(Collection<Identifier> ids) {
        var closure = new LinkedHashSet<Identifier>();
        var queue = new ArrayDeque<>(ids);
        while (!queue.isEmpty()) {
            var id = queue.poll();
            if (closure.add(id)) {
                queue.addAll(dependents.getOrDefault(id, Set.of()));
            }
        }
        closure.forEach(cache::remove);
        return closure;
    }

    public synchronized void clear() {
        cache.clear();
        dependents.clear();
        reported.clear();
    }

    @Nullable
    public synchronized InstanceTemplate cached(Identifier id) {
        return cache.get(id);
    }

    /** Every source {@code id} places, at any depth: what a template knows, or else what is saved (nothing is baked). */
    public Set<Identifier> closure(Identifier id) {
        var out = new LinkedHashSet<Identifier>();
        var queue = new ArrayDeque<Identifier>();
        queue.add(id);
        while (!queue.isEmpty()) {
            var next = queue.poll();
            var template = cached(next);
            if (template != null) {
                out.addAll(template.dependencies());
                continue;
            }
            var raw = source.read(next);
            if (raw == null) continue;
            for (var placed : InstanceBaker.directSources(raw)) {
                if (out.add(placed)) queue.add(placed);
            }
        }
        return out;
    }

    private void report(String problem) {
        if (reported.add(problem)) {
            reporter.report(problem);
        }
    }
}
