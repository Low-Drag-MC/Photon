package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigPropertyType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
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
@OnlyIn(Dist.CLIENT)
public final class FXPreloader {
    private static final long FRAME_BUDGET_NANOS = 4_000_000L;
    private static final Queue<Runnable> RENDER_JOBS = new ConcurrentLinkedQueue<>();
    private static final Set<ResourceLocation> KEPT = ConcurrentHashMap.newKeySet();
    private static volatile boolean reloaded = false;

    private FXPreloader() {
    }

    static CompletableFuture<FX> preload(ResourceLocation fxLocation) {
        var batch = new Batch();
        batch.effects.add(fxLocation);
        var result = batch.load(fxLocation);
        batch.seal();
        return batch.done.thenCompose(ignored -> batch.stale()
                ? preload(fxLocation)
                : CompletableFuture.completedFuture(result.getNow(null)));
    }

    static CompletableFuture<Void> preload(Collection<ResourceLocation> fxLocations) {
        var batch = new Batch();
        fxLocations.forEach(batch::fx);
        batch.seal();
        return batch.done.thenCompose(ignored -> batch.stale()
                ? preload(fxLocations)
                : CompletableFuture.completedFuture(null));
    }

    @Nullable
    static FX preloadNow(ResourceLocation fxLocation) {
        RenderSystem.assertOnRenderThread();
        var sink = new SyncSink();
        sink.effects.add(fxLocation);
        return sink.load(fxLocation);
    }

    static void keep(Collection<ResourceLocation> fxLocations) {
        var added = new ArrayList<ResourceLocation>();
        for (var fxLocation : fxLocations) {
            if (KEPT.add(fxLocation)) added.add(fxLocation);
        }
        if (reloaded && !added.isEmpty()) {
            preload(added);
        }
    }

    /** Called by Photon's reload listener right after the effect cache is cleared: re-warms the kept effects. */
    public static void onResourcesReloaded() {
        reloaded = true;
        if (KEPT.isEmpty()) return;
        var start = System.nanoTime();
        var sink = new SyncSink();
        for (var fxLocation : List.copyOf(KEPT)) {
            sink.fx(fxLocation);
        }
        Photon.LOGGER.info("Preloaded {} effects in {} ms", sink.effects.size(), (System.nanoTime() - start) / 1_000_000);
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
        for (var track : fx.getFxData().timeline().leafTracks(false)) {
            if (track.mute() || !(track instanceof AnimationTrack animation) || animation.targetId() == null) continue;
            for (var property : animation.properties()) {
                if (property.type() instanceof ConfigPropertyType config) {
                    animated.computeIfAbsent(animation.targetId(), id -> new HashSet<>()).add(config.storeKey());
                }
            }
        }
        var context = new EffectContext(sink, animated);
        for (var object : fx.getFxData().objects()) {
            try {
                object.preload(context);
            } catch (Throwable e) {
                Photon.LOGGER.warn("Failed to preload {} of fx {}", object.getName(), fx.getFxLocation(), e);
            }
        }
    }

    private interface Sink {
        void texture(ResourceLocation location);

        void render(Runnable job);

        void fx(ResourceLocation fxLocation);
    }

    private record EffectContext(Sink sink, Map<UUID, Set<String>> animated) implements PreloadContext {
        @Override
        public void texture(ResourceLocation location) {
            sink.texture(location);
        }

        @Override
        public void onRenderThread(Runnable job) {
            sink.render(job);
        }

        @Override
        public void fx(ResourceLocation fxLocation) {
            sink.fx(fxLocation);
        }

        @Override
        public boolean isAnimated(IFXObject object, String path) {
            var paths = animated.get(object.id());
            return paths != null && paths.contains(path);
        }
    }

    private static final class SyncSink implements Sink {
        private final Set<ResourceLocation> effects = new HashSet<>();

        @Override
        public void texture(ResourceLocation location) {
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
        public void fx(ResourceLocation fxLocation) {
            if (effects.add(fxLocation)) {
                load(fxLocation);
            }
        }

        @Nullable
        private FX load(ResourceLocation fxLocation) {
            var fx = FXHelper.getFX(fxLocation);
            if (fx != null) walk(fx, this);
            return fx;
        }
    }

    private static final class Batch implements Sink {
        private final int generation = FXHelper.generation();
        private final Set<ResourceLocation> effects = ConcurrentHashMap.newKeySet();
        private final Set<ResourceLocation> textures = new HashSet<>();
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
        public void fx(ResourceLocation fxLocation) {
            if (effects.add(fxLocation)) {
                load(fxLocation);
            }
        }

        private CompletableFuture<FX> load(ResourceLocation fxLocation) {
            var result = new CompletableFuture<FX>();
            pending.incrementAndGet();
            var cached = FXHelper.getCachedFX(fxLocation);
            var read = cached != null
                    ? CompletableFuture.<CompoundTag>completedFuture(null)
                    : CompletableFuture.supplyAsync(() -> FXHelper.readTag(fxLocation), Util.backgroundExecutor());
            read.whenComplete((tag, error) -> RENDER_JOBS.add(() -> {
                FX fx = null;
                try {
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
        public void texture(ResourceLocation location) {
            var textureManager = Minecraft.getInstance().getTextureManager();
            if (textureManager.getTexture(location, null) != null || !textures.add(location)) return;
            pending.incrementAndGet();
            var resources = Minecraft.getInstance().getResourceManager();
            CompletableFuture.supplyAsync(() -> new DecodedTexture(location, resources), Util.backgroundExecutor())
                    .whenComplete((texture, error) -> RENDER_JOBS.add(() -> {
                        try {
                            if (texture == null) return;
                            if (stale() || textureManager.getTexture(location, null) != null) {
                                texture.discard();
                            } else {
                                textureManager.register(location, texture);
                            }
                        } catch (Throwable e) {
                            Photon.LOGGER.warn("Failed to preload texture {}", location, e);
                        } finally {
                            release();
                        }
                    }));
        }
    }

    /** Registered only once uploaded: vanilla's PreloadedTexture sits in the map half-loaded and binds black meanwhile. */
    private static final class DecodedTexture extends SimpleTexture {
        @Nullable
        private TextureImage image;

        private DecodedTexture(ResourceLocation location, ResourceManager resources) {
            super(location);
            this.image = TextureImage.load(resources, location);
        }

        @Override
        protected TextureImage getTextureImage(ResourceManager resourceManager) {
            var decoded = image;
            image = null;
            return decoded != null ? decoded : super.getTextureImage(resourceManager);
        }

        private void discard() {
            if (image != null) {
                image.close();
                image = null;
            }
        }
    }
}
