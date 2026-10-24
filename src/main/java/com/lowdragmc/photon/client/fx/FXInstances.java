package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.instance.InstanceBaker;
import com.lowdragmc.photon.client.fx.instance.InstanceTemplates;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sources FX instances are placed from, shared by every effect loaded through {@link FXHelper} and every
 * project the editor opens. A source is read through the resource manager like any effect (so a pack can
 * override it), baked once with its own instances placed, and forgotten together with everything that places
 * it when {@link FXHelper#invalidate} or {@link FXHelper#clearCache} runs.
 * <p>
 * Baking reads objects, which may compile shaders: render thread only, as {@link FXHelper#getFX} is.
 */
@OnlyIn(Dist.CLIENT)
public final class FXInstances {
    private record Prefetched(CompoundTag fxData, int generation) {
    }

    /** Sources read ahead on a worker thread by a preload, so baking on the render thread reads no file. */
    private static final Map<ResourceLocation, Prefetched> PREFETCHED = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final InstanceTemplates TEMPLATES =
            new InstanceTemplates(FXInstances::read, FXInstances::canonical, FXInstances::report);

    private FXInstances() {
    }

    public static InstanceTemplates templates() {
        return TEMPLATES;
    }

    /**
     * Place the instances of {@code fxData} (an FX's {@code {fxObjects, timeline}}); {@code as} are the ids the FX
     * is known by, so an instance leading back to one of them is a cycle rather than a recursion.
     */
    public static InstanceBaker.Result bake(Collection<ResourceLocation> as, CompoundTag fxData) {
        var result = TEMPLATES.bake(as, fxData);
        var named = as.isEmpty() ? "" : as.iterator().next() + ": ";
        result.problems().forEach(problem -> report(named + problem));
        return result;
    }

    /** Read the sources {@code fxRoot} (a {@code .fx} file's root tag) places, at any depth, ahead of baking it. Any thread. */
    public static void prefetchSources(CompoundTag fxRoot) {
        var generation = FXHelper.generation();
        var queue = new ArrayDeque<>(InstanceBaker.directSources(fxRoot.getCompound("fxData")));
        var seen = new HashSet<ResourceLocation>();
        while (!queue.isEmpty()) {
            var id = queue.poll();
            if (!seen.add(id) || TEMPLATES.cached(id) != null) continue;
            var prefetched = PREFETCHED.get(id);
            if (prefetched != null && prefetched.generation() == generation) continue;
            var tag = FXHelper.readTag(id);
            if (tag == null || !tag.contains("fxData", Tag.TAG_COMPOUND)) continue;
            var fxData = tag.getCompound("fxData");
            PREFETCHED.put(id, new Prefetched(fxData, generation));
            queue.addAll(InstanceBaker.directSources(fxData));
        }
    }

    static Set<ResourceLocation> invalidate(Collection<ResourceLocation> ids) {
        ids.forEach(PREFETCHED::remove);
        return TEMPLATES.invalidate(ids);
    }

    static void clear() {
        TEMPLATES.clear();
        PREFETCHED.clear();
        REPORTED.clear();
    }

    @Nullable
    private static CompoundTag read(ResourceLocation id) {
        var prefetched = PREFETCHED.remove(id);
        if (prefetched != null && prefetched.generation() == FXHelper.generation()) {
            return prefetched.fxData();
        }
        var tag = FXHelper.readTag(id);
        return tag == null || !tag.contains("fxData", Tag.TAG_COMPOUND) ? null : tag.getCompound("fxData");
    }

    /** As the current code saves it, or the tag itself when it cannot be read back. */
    private static CompoundTag canonical(CompoundTag object) {
        try {
            var read = IFXObject.deserializeWrapper(object);
            var written = read == null ? null : read.serializeWrapper();
            return written == null ? object : written;
        } catch (Exception e) {
            report("an object of type '" + object.getString("type") + "' could not be read back: " + e);
            return object;
        }
    }

    private static void report(String problem) {
        if (REPORTED.add(problem)) {
            Photon.LOGGER.warn("[fx instance] {}", problem);
        }
    }
}
