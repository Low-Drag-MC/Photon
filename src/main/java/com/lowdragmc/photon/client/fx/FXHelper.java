package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.fixer.PhotonFXProjectDataFixer;
import com.lowdragmc.photon.gui.editor.FXProject;
import net.minecraft.nbt.NbtAccounter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads and caches {@link FX} definitions from {@code assets/<ns>/fx/<path>.fx} resources (resource
 * packs, mod jars, or the injected {@code <gameDir>/ldlib2} pack the editor exports into).
 * <p>
 * The id passed to {@link #getFX} omits the {@code fx/} prefix and {@code .fx} suffix:
 * {@code photon:example} → {@code assets/photon/fx/example.fx}.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class FXHelper {
    // concurrent: sub-emitter spawns may query the cache while other threads do (never mutate mid-load;
    // loadFX does not re-enter getFX, so computeIfAbsent cannot recurse)
    private final static Map<ResourceLocation, FX> CACHE = new ConcurrentHashMap<>();
    // volatile: listAllFX is called from search worker threads as well as the game thread
    @Nullable
    private static volatile List<ResourceLocation> idCache = null;
    private static final AtomicInteger generation = new AtomicInteger();
    public static final String FX_PATH = "fx/";

    public static int clearCache() {
        var count = CACHE.size();
        CACHE.clear();
        FXInstances.clear();
        idCache = null;
        generation.incrementAndGet();
        return count;
    }

    /**
     * Forget these effects, e.g. after the pack providing them was rewritten, so the next play loads them
     * again. Unlike {@link #clearCache()} every other loaded effect stays cached; preloads under way start
     * over, so none of them publishes what it read before, and kept effects are preloaded again in the
     * background.
     */
    public static void invalidate(Collection<ResourceLocation> fxLocations) {
        // and every effect that places one of them as an FX instance, at any depth
        FXInstances.invalidate(fxLocations).forEach(CACHE::remove);
        fxLocations.forEach(CACHE::remove);
        idCache = null;
        generation.incrementAndGet();
        FXPreloader.onInvalidated();
    }

    /** The resource an fx id is read from: {@code photon:example} → {@code photon:fx/example.fx}. */
    public static ResourceLocation fileLocation(ResourceLocation fxLocation) {
        return fxLocation.withPath(path -> FX_PATH + path + FX.SUFFIX);
    }

    /** Bumped whenever loaded effects are forgotten (a reload, {@link #invalidate}, {@link #clearCache}). */
    public static int generation() {
        return generation.get();
    }

    /**
     * Preload an effect in the background, so its first play does not stall on loading. Warms the shader
     * variants it draws with, its textures and the effects its sub-emitters spawn: file reads and image
     * decoding run on worker threads, GL work runs on the render thread a few milliseconds per frame.
     * <p>
     * Callable from any thread. The future completes on the render thread with the cached definition, or null
     * if the effect cannot be loaded. Never {@code join()} it on the render thread; use {@link #preloadNow} there.
     */
    public static CompletableFuture<FX> preload(ResourceLocation fxLocation) {
        return FXPreloader.preload(fxLocation);
    }

    /** {@link #preload(ResourceLocation)} for several effects; completes once all of them are warm. */
    public static CompletableFuture<Void> preload(Collection<ResourceLocation> fxLocations) {
        return FXPreloader.preload(fxLocations);
    }

    /**
     * {@link #preload(ResourceLocation)} done synchronously: everything is warm when it returns. Render thread
     * only, e.g. behind a loading screen of your own.
     *
     * @return the cached definition, or null if the effect cannot be loaded
     */
    @Nullable
    public static FX preloadNow(ResourceLocation fxLocation) {
        return FXPreloader.preloadNow(fxLocation);
    }

    /**
     * Keep effects warm for the whole session. A resource reload drops every loaded effect, so these are
     * preloaded again during each reload, behind the loading screen. Once the game has loaded, newly kept
     * effects are also preloaded right away in the background. Callable from any thread, including mod
     * construction and setup.
     */
    public static void keepPreloaded(ResourceLocation... fxLocations) {
        FXPreloader.keep(List.of(fxLocations));
    }

    /** {@link #keepPreloaded(ResourceLocation...)} for a collection. */
    public static void keepPreloaded(Collection<ResourceLocation> fxLocations) {
        FXPreloader.keep(fxLocations);
    }

    /** The cached definition of an effect, or null if it has not been loaded. Never loads it. */
    @Nullable
    public static FX getCachedFX(ResourceLocation fxLocation) {
        return CACHE.get(fxLocation);
    }

    @Nullable
    public static FX getFX(ResourceLocation fxLocation) {
        return getFX(fxLocation, true);
    }

    /**
     * Every fx id currently loadable through {@link #getFX} — from mod jars, resource packs and
     * mounted {@code .fxpack}s alike (anything providing {@code assets/<ns>/fx/<name>.fx}).
     * <p>
     * Cached, because walking every pack is far too expensive to repeat per keystroke of a search box
     * and the answer only changes when the packs do: {@link #clearCache()} drops it, and Photon's
     * reload listener calls that. The returned list is immutable and shared.
     */
    public static List<ResourceLocation> listAllFX() {
        var cached = idCache;
        if (cached != null) {
            return cached;
        }
        var result = new ArrayList<ResourceLocation>();
        Minecraft.getInstance().getResourceManager()
                .listResources("fx", location -> location.getPath().endsWith(FX.SUFFIX))
                .forEach((location, resource) -> {
                    var path = location.getPath(); // fx/<name>.fx
                    result.add(ResourceLocation.fromNamespaceAndPath(location.getNamespace(),
                            path.substring(FX_PATH.length(), path.length() - FX.SUFFIX.length())));
                });
        // by namespace first, so the list reads the way it is displayed ("ns:path") and every effect from
        // one pack sits together. ResourceLocation's own order is path-first, which interleaves packs.
        result.sort(Comparator.comparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath));
        var listing = Collections.unmodifiableList(result);
        idCache = listing;
        return listing;
    }


    @Nullable
    public static FX getFX(ResourceLocation fxLocation, boolean useCache) {
        return useCache ? CACHE.computeIfAbsent(fxLocation, location -> loadFX(fxLocation)) : loadFX(fxLocation);
    }

    @Nullable
    private static FX loadFX(ResourceLocation fxLocation) {
        var tag = readTag(fxLocation);
        return tag == null ? null : deserialize(fxLocation, tag);
    }

    @Nullable
    public static CompoundTag readTag(ResourceLocation fxLocation) {
        var resourceLocation = fileLocation(fxLocation);
        try (var inputStream = Minecraft.getInstance().getResourceManager().open(resourceLocation)) {
            var tag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
            tag = applyVersionFixes(tag);
            if (tag.contains("resources", Tag.TAG_COMPOUND)) {
                // short-lived dev format that embedded assets in the .fx — superseded by .fxpack
                Photon.LOGGER.warn("fx {} carries an embedded 'resources' section, which is no longer "
                        + "supported — re-export it (assets travel in .fxpack files now)", fxLocation);
            }
            return tag;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load fx {} ({})", fxLocation, resourceLocation, e);
            return null;
        }
    }

    /** Render thread only: a custom shader material compiles while it deserializes. */
    @Nullable
    static FX publish(ResourceLocation fxLocation, CompoundTag tag) {
        var cached = CACHE.get(fxLocation);
        if (cached != null) return cached;
        var fx = deserialize(fxLocation, tag);
        if (fx == null) return null;
        var existing = CACHE.putIfAbsent(fxLocation, fx);
        return existing != null ? existing : fx;
    }

    @Nullable
    private static FX deserialize(ResourceLocation fxLocation, CompoundTag tag) {
        try {
            var fx = new FX();
            fx.setFxLocation(fxLocation);
            fx.deserializeNBT(Platform.getFrozenRegistry(), tag);
            return fx;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load fx {}", fxLocation, e);
            return null;
        }
    }

    /**
     * Run the project datafixer on a versioned {@code .fx} root tag. Files without a {@code version}
     * (everything exported before versioning) pass through untouched: they span all historical
     * versions and the deserializers' inline legacy fallbacks already handle them — running the
     * V1→current fixers on data of unknown true version would risk double-migration.
     */
    public static CompoundTag applyVersionFixes(CompoundTag tag) {
        if (!tag.contains("version", Tag.TAG_INT)) {
            return tag;
        }
        int version = tag.getInt("version");
        if (version >= FXProject.VERSION) {
            return tag;
        }
        // the fixers navigate the project "data" shape {fx: {fxData: ...}} — wrap, fix, unwrap
        var wrapped = new CompoundTag();
        wrapped.put("fx", tag);
        var fixed = PhotonFXProjectDataFixer.INSTANCE.applyFixes(version, FXProject.VERSION, wrapped).getCompound("fx");
        fixed.putInt("version", FXProject.VERSION);
        return fixed;
    }

}
