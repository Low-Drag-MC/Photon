package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigPropertyType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.render.PhotonFramePasses;
import com.mojang.blaze3d.systems.RenderSystem;
import com.lowdragmc.photon.core.mixins.accessor.TextureManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the preloads behind {@link FXHelper#preload}, {@link FXHelper#preloadNow} and
 * {@link FXHelper#keepPreloaded}. Render-thread work is queued and drained at the start of each frame within a
 * time budget.
 */
public final class FXPreloader {
    private static final long FRAME_BUDGET_NANOS = 4_000_000L;
    private static final Queue<Runnable> RENDER_JOBS = new ConcurrentLinkedQueue<>();
    private static final Set<Identifier> KEPT = ConcurrentHashMap.newKeySet();
    private static volatile boolean reloaded = false;

    private FXPreloader() {
    }

    static CompletableFuture<FX> preload(Identifier fxLocation) {
        var batch = new Batch();
        batch.render(PhotonFramePasses::preload);
        batch.effects.add(fxLocation);
        var result = batch.load(fxLocation);
        batch.seal();
        return batch.done.thenCompose(ignored -> batch.stale()
                ? preload(fxLocation)
                : CompletableFuture.completedFuture(result.getNow(null)));
    }

    static CompletableFuture<Void> preload(Collection<Identifier> fxLocations) {
        var batch = new Batch();
        batch.render(PhotonFramePasses::preload);
        fxLocations.forEach(batch::fx);
        batch.seal();
        return batch.done.thenCompose(ignored -> batch.stale()
                ? preload(fxLocations)
                : CompletableFuture.completedFuture(null));
    }

    @Nullable
    static FX preloadNow(Identifier fxLocation) {
        RenderSystem.assertOnRenderThread();
        var sink = new SyncSink();
        sink.render(PhotonFramePasses::preload);
        sink.effects.add(fxLocation);
        return sink.load(fxLocation);
    }

    static void keep(Collection<Identifier> fxLocations) {
        var added = new ArrayList<Identifier>();
        for (var fxLocation : fxLocations) {
            if (KEPT.add(fxLocation)) added.add(fxLocation);
        }
        if (reloaded && !added.isEmpty()) {
            preload(added);
        }
    }

    /**
     * Called by Photon's last reload listener, behind the loading screen: compiles Photon's own frame passes again (the
     * reload dropped every compiled pipeline) and re-warms the kept effects.
     */
    public static void onResourcesReloaded() {
        reloaded = true;
        var sink = new SyncSink();
        sink.render(PhotonFramePasses::preload);
        if (KEPT.isEmpty()) return;
        var start = System.nanoTime();
        for (var fxLocation : List.copyOf(KEPT)) {
            sink.fx(fxLocation);
        }
        Photon.LOGGER.info("Preloaded {} effects in {} ms", sink.effects.size(), (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Called by {@link FXHelper#invalidate}: every kept effect is walked again, not only the forgotten ones
     * among them, since a kept effect can spawn a forgotten one as a sub-emitter. What is still warm costs
     * the walk only.
     */
    static void onInvalidated() {
        if (reloaded && !KEPT.isEmpty()) {
            preload(List.copyOf(KEPT));
        }
    }

    /** Called at the start of every frame: runs queued render-thread work within the budget, at least one job. */
    public static void onFrameStart() {
        if (RENDER_JOBS.isEmpty() || Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) return;
        var deadline = System.nanoTime() + FRAME_BUDGET_NANOS;
        Runnable job;
        while ((job = RENDER_JOBS.poll()) != null) {
            job.run();
            if (System.nanoTime() >= deadline) break;
        }
    }

    /** How many render-thread jobs are still queued. */
    public static int pendingJobs() {
        return RENDER_JOBS.size();
    }

    private static void walk(FX fx, Sink sink) {
        var animated = new HashMap<UUID, Set<String>>();
        var timelines = new ArrayList<Timeline>();
        timelines.add(fx.getFxData().timeline());
        // an FX instance's own timeline drives what the instance placed
        for (var object : fx.getFxData().objects()) {
            if (object instanceof FXInstanceObject instance) timelines.add(instance.getTimeline());
        }
        for (var timeline : timelines) {
            for (var track : timeline.leafTracks(false)) {
                if (track.mute() || !(track instanceof AnimationTrack animation) || animation.targetId() == null) continue;
                for (var property : animation.properties()) {
                    if (property.type() instanceof ConfigPropertyType config) {
                        animated.computeIfAbsent(animation.targetId(), id -> new HashSet<>()).add(config.storeKey());
                    }
                }
            }
        }
        var context = new EffectContext(sink, animated);
        var byId = new HashMap<UUID, IFXObject>();
        fx.getFxData().objects().forEach(object -> byId.put(object.id(), object));
        for (var object : fx.getFxData().objects()) {
            // switched off for good: no timeline can bring it back
            if (!isEnabled(object, byId)) continue;
            try {
                object.preload(context);
            } catch (Throwable e) {
                Photon.LOGGER.warn("Failed to preload {} of fx {}", object.getName(), fx.getFxLocation(), e);
            }
        }
    }

    /** Whether {@code object} and every object it hangs under are switched on (the definition's transforms are not linked). */
    private static boolean isEnabled(IFXObject object, Map<UUID, IFXObject> byId) {
        var at = object;
        for (int depth = 0; at != null && depth <= byId.size(); depth++) {
            if (!at.isEnabled()) return false;
            at = byId.get(at.transform()._getInternalParentID());
        }
        return true;
    }

    private interface Sink {
        void texture(Identifier location);

        void render(Runnable job);

        void fx(Identifier fxLocation);
    }

    private record EffectContext(Sink sink, Map<UUID, Set<String>> animated) implements PreloadContext {
        @Override
        public void texture(Identifier location) {
            sink.texture(location);
        }

        @Override
        public void onRenderThread(Runnable job) {
            sink.render(job);
        }

        @Override
        public void fx(Identifier fxLocation) {
            sink.fx(fxLocation);
        }

        @Override
        public boolean isAnimated(IFXObject object, String path) {
            var paths = animated.get(object.id());
            return paths != null && paths.contains(path);
        }
    }

    private static final class SyncSink implements Sink {
        private final Set<Identifier> effects = new HashSet<>();

        @Override
        public void texture(Identifier location) {
            render(() -> Minecraft.getInstance().getTextureManager().getTexture(location));
        }

        @Override
        public void render(Runnable job) {
            try {
                job.run();
            } catch (Throwable e) {
                Photon.LOGGER.warn("Failed to preload an fx resource", e);
            }
        }

        @Override
        public void fx(Identifier fxLocation) {
            if (effects.add(fxLocation)) {
                load(fxLocation);
            }
        }

        @Nullable
        private FX load(Identifier fxLocation) {
            var fx = FXHelper.getFX(fxLocation);
            if (fx != null) walk(fx, this);
            return fx;
        }
    }

    private static final class Batch implements Sink {
        private final int generation = FXHelper.generation();
        private final Set<Identifier> effects = ConcurrentHashMap.newKeySet();
        private final Set<Identifier> textures = new HashSet<>();
        // held by the caller until seal(), so the batch cannot complete while it is still being filled
        private final AtomicInteger pending = new AtomicInteger(1);
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        private void seal() {
            RENDER_JOBS.add(this::release);
        }

        private boolean stale() {
            return generation != FXHelper.generation();
        }

        private void release() {
            if (pending.decrementAndGet() == 0) {
                done.complete(null);
            }
        }

        @Override
        public void render(Runnable job) {
            pending.incrementAndGet();
            RENDER_JOBS.add(() -> {
                try {
                    if (!stale()) job.run();
                } catch (Throwable e) {
                    Photon.LOGGER.warn("Failed to preload an fx resource", e);
                } finally {
                    release();
                }
            });
        }

        @Override
        public void fx(Identifier fxLocation) {
            if (effects.add(fxLocation)) {
                load(fxLocation);
            }
        }

        private CompletableFuture<FX> load(Identifier fxLocation) {
            var result = new CompletableFuture<FX>();
            pending.incrementAndGet();
            var cached = FXHelper.getCachedFX(fxLocation);
            var read = cached != null
                    ? CompletableFuture.<CompoundTag>completedFuture(null)
                    : CompletableFuture.supplyAsync(() -> {
                        var tag = FXHelper.readTag(fxLocation);
                        // the sources its FX instances place too, so placing them reads no file on the render thread
                        if (tag != null) FXInstances.prefetchSources(tag);
                        return tag;
                    }, Util.backgroundExecutor());
            read.whenComplete((tag, error) -> RENDER_JOBS.add(() -> {
                FX fx = null;
                try {
                    if (error != null) Photon.LOGGER.warn("Failed to read fx {} to preload", fxLocation, error);
                    if (!stale()) {
                        fx = cached != null ? cached : tag != null ? FXHelper.publish(fxLocation, tag) : null;
                        if (fx != null) walk(fx, this);
                    }
                } catch (Throwable e) {
                    Photon.LOGGER.warn("Failed to preload fx {}", fxLocation, e);
                } finally {
                    result.complete(fx);
                    release();
                }
            }));
            return result;
        }

        @Override
        public void texture(Identifier location) {
            var textureManager = Minecraft.getInstance().getTextureManager();
            if (isLoaded(textureManager, location) || !textures.add(location)) return;
            pending.incrementAndGet();
            var resources = Minecraft.getInstance().getResourceManager();
            CompletableFuture.supplyAsync(() -> decode(resources, location), Util.backgroundExecutor())
                    .whenComplete((contents, error) -> RENDER_JOBS.add(() -> {
                        try {
                            if (contents == null) return;
                            if (stale() || isLoaded(textureManager, location)) {
                                contents.close();
                            } else {
                                upload(textureManager, location, contents);
                            }
                        } catch (Throwable e) {
                            Photon.LOGGER.warn("Failed to preload texture {}", location, e);
                        } finally {
                            release();
                        }
                    }));
        }
    }

    /** Whether the texture manager holds {@code location} already. Asking it the vanilla way would load it. */
    private static boolean isLoaded(TextureManager textureManager, Identifier location) {
        return ((TextureManagerAccessor) textureManager).photon$byPath().containsKey(location);
    }

    /**
     * The image, decoded off-thread. One that cannot be read becomes the missing texture, as {@code TextureManager}
     * makes it on a first bind: the first play then finds it loaded, and nothing retries it before the next reload.
     */
    private static TextureContents decode(ResourceManager resources, Identifier location) {
        try {
            return TextureContents.load(resources, location);
        } catch (FileNotFoundException e) {
            // as TextureManager says it: an absent file is no failure worth a stack trace
            if (!TextureManager.INTENTIONAL_MISSING_TEXTURE.equals(location)) {
                Photon.LOGGER.warn("Missing texture {}", location);
            }
            return TextureContents.createMissing();
        } catch (Exception e) {
            Photon.LOGGER.error("Failed to load texture {}", location, e);
            return TextureContents.createMissing();
        }
    }

    /**
     * What {@code TextureManager.getTexture} does on a miss, with the decoding already done: a plain SimpleTexture,
     * registered once uploaded, so a resource reload re-reads it like any other.
     */
    private static void upload(TextureManager textureManager, Identifier location, TextureContents contents) {
        var texture = new SimpleTexture(location);
        try {
            texture.apply(contents);
        } catch (Throwable e) {
            // the GPU texture may exist already; nothing would ever close one that was not registered
            texture.close();
            throw e;
        }
        textureManager.register(location, texture);
    }
}
