package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared cache of {@link PhotonMesh} geometry so emitters referencing the same model reuse one
 * instance. Keys are per-source records (loader options belong in the key). Entries are dropped on
 * resource reload (registered as a reload listener in {@code PhotonClientProxy}), by explicit
 * {@link #invalidate}, or via {@link #pollFileChanges()} for entries backed by an editable disk
 * file. Consumers detect invalidation by instance identity, so a fresh load must produce a fresh
 * {@link PhotonMesh}. Thread-safe: loads run per-key-atomic under {@code computeIfAbsent}
 * (the parallel particle sim and the render thread both call {@link #get}).
 */
@OnlyIn(Dist.CLIENT)
public final class PhotonMeshCache implements ResourceManagerReloadListener {
    public static final PhotonMeshCache INSTANCE = new PhotonMeshCache();

    /** JSON model geometry, keyed by the model id. */
    public record JsonKey(ResourceLocation model) {
    }

    /** Runtime-parsed OBJ geometry; parser options are part of the key. */
    public record ObjKey(ResourceLocation location, boolean flipV) {
    }

    /** Runtime-parsed glTF ({@code .glb}/{@code .gltf}) geometry; parser options are part of the key. */
    public record GltfKey(ResourceLocation location, boolean flipV) {
    }

    private record FileStamp(File file, long lastModified) {
    }

    private final ConcurrentHashMap<Object, PhotonMesh> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, FileStamp> fileStamps = new ConcurrentHashMap<>();

    private PhotonMeshCache() {
    }

    /**
     * Cached lookup. A {@code null} from the loader means "cannot load right now" (e.g. during the
     * loading overlay): {@link PhotonMesh#EMPTY} is returned but nothing is cached, so the next
     * call retries.
     */
    public PhotonMesh get(Object key, Function<Object, @Nullable PhotonMesh> loader) {
        var mesh = cache.computeIfAbsent(key, loader);
        return mesh == null ? PhotonMesh.EMPTY : mesh;
    }

    /** Watch the disk file backing {@code key}; {@link #pollFileChanges()} invalidates the entry when it changes. */
    public void trackFile(Object key, File file) {
        fileStamps.put(key, new FileStamp(file, file.lastModified()));
    }

    public void invalidate(Object key) {
        cache.remove(key);
        fileStamps.remove(key);
    }

    /** Invalidate every entry whose tracked disk file changed (or vanished) since it was loaded. */
    public void pollFileChanges() {
        for (var entry : fileStamps.entrySet()) {
            if (entry.getValue().file.lastModified() != entry.getValue().lastModified) {
                invalidate(entry.getKey());
            }
        }
    }

    public void clear() {
        cache.clear();
        fileStamps.clear();
    }

    @Override
    public void onResourceManagerReload(@NotNull ResourceManager resourceManager) {
        clear();
    }
}
