package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
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
public final class PhotonMeshCache implements ResourceManagerReloadListener {
    public static final PhotonMeshCache INSTANCE = new PhotonMeshCache();

    /** JSON model geometry, keyed by the model id. */
    public record JsonKey(Identifier model) {
    }

    /** Runtime-parsed OBJ geometry; parser options are part of the key. */
    public record ObjKey(Identifier location, boolean flipV) {
    }

    /** Runtime-parsed glTF ({@code .glb}/{@code .gltf}) geometry; parser options are part of the key. */
    public record GltfKey(Identifier location, boolean flipV) {
    }

    private record FileStamp(File file, long lastModified) {
    }

    private final ConcurrentHashMap<Object, PhotonMesh> cache = new ConcurrentHashMap<>();
    /**
     * Parsed models with their skins and animations, for the sources that need more than geometry.
     * Separate from {@link #cache} rather than replacing it because most sources produce no skeleton at
     * all and should not be made to carry a wrapper for one — but invalidation is shared, so a reload or
     * an edit drops both under the same key.
     */
    private final ConcurrentHashMap<Object, SkinnedModel> models = new ConcurrentHashMap<>();
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

    /**
     * Cached lookup of a parsed model, with the same {@code null} = "cannot load right now, retry"
     * contract as {@link #get}.
     */
    public SkinnedModel getModel(Object key, Function<Object, @Nullable SkinnedModel> loader) {
        var model = models.computeIfAbsent(key, loader);
        return model == null ? SkinnedModel.EMPTY : model;
    }

    /** Watch the disk file backing {@code key}; {@link #pollFileChanges()} invalidates the entry when it changes. */
    public void trackFile(Object key, File file) {
        fileStamps.put(key, new FileStamp(file, file.lastModified()));
    }

    public void invalidate(Object key) {
        cache.remove(key);
        models.remove(key);
        fileStamps.remove(key);
        // Poses are keyed by the model instance they were deformed from, so dropping a model would
        // otherwise leave its poses holding the only reference to it. They cost one frame to rebuild, so
        // clearing all of them is cheaper than tracking which belong to this key.
        AnimatedPose.clear();
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
        models.clear();
        fileStamps.clear();
        AnimatedPose.clear();
    }

    @Override
    public void onResourceManagerReload(@NotNull ResourceManager resourceManager) {
        clear();
    }
}
