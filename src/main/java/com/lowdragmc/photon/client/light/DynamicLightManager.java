package com.lowdragmc.photon.client.light;

import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Where lights come from. FX objects register with the level they live in, so an editor scene and the world
 * each light only their own; {@link PhotonLights}' lights and providers show in whatever level the client is
 * in. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class DynamicLightManager {
    private static final Set<DynamicLight> LIGHTS = new LinkedHashSet<>();
    private static final Set<LightProvider> GLOBAL_PROVIDERS = new LinkedHashSet<>();
    private static final Map<Level, LightScene> SCENES = new WeakHashMap<>();
    private static final List<DynamicLight> POOL = new ArrayList<>();
    private static int poolUsed;
    private static final LightSink SINK = () -> {
        if (poolUsed == POOL.size()) {
            POOL.add(new DynamicLight());
        }
        return POOL.get(poolUsed++).reset();
    };

    private DynamicLightManager() {
    }

    /** Held weakly: the caller keeps the provider alive and removes it by identity. */
    public static void addProvider(Level level, LightProvider provider) {
        scene(level).providers.add(provider);
    }

    public static void removeProvider(Level level, LightProvider provider) {
        var scene = SCENES.get(level);
        if (scene != null) scene.providers.remove(provider);
    }

    /** The voxel copy of {@code level}'s blocks, if anything has lit it yet. */
    @Nullable
    public static VoxelWorld voxels(Level level) {
        var scene = SCENES.get(level);
        return scene == null ? null : scene.voxels;
    }

    public static void blockChanged(Level level, int x, int y, int z) {
        var scene = SCENES.get(level);
        if (scene != null) scene.voxels.markDirty(x, y, z);
    }

    public static void chunkLoaded(Level level, int chunkX, int chunkZ) {
        var scene = SCENES.get(level);
        if (scene != null) scene.voxels.markColumnDirty(chunkX, chunkZ);
    }

    /** Every block of {@code level} may have changed, as when an editor scene is rebuilt. */
    public static void blocksReplaced(Level level) {
        var scene = SCENES.get(level);
        if (scene != null) scene.voxels.clear();
    }

    static DynamicLight add(DynamicLight light) {
        LIGHTS.add(light);
        return light;
    }

    static boolean remove(DynamicLight light) {
        return LIGHTS.remove(light);
    }

    static void addGlobalProvider(LightProvider provider) {
        GLOBAL_PROVIDERS.add(provider);
    }

    static void removeGlobalProvider(LightProvider provider) {
        GLOBAL_PROVIDERS.remove(provider);
    }

    static LightScene scene(Level level) {
        return SCENES.computeIfAbsent(level, key -> new LightScene());
    }

    @Nullable
    static LightScene removeScene(Level level) {
        return SCENES.remove(level);
    }

    static boolean isEmpty(LightScene scene, boolean global) {
        return scene.providers.isEmpty() && (!global || LIGHTS.isEmpty() && GLOBAL_PROVIDERS.isEmpty());
    }

    static List<DynamicLight> collect(LightScene scene, boolean global, float partialTick, List<DynamicLight> out) {
        poolUsed = 0;
        out.clear();
        if (global) {
            for (var provider : List.copyOf(GLOBAL_PROVIDERS)) {
                provider.submitLights(SINK, partialTick);
            }
            out.addAll(LIGHTS);
        }
        for (var provider : List.copyOf(scene.providers)) {
            provider.submitLights(SINK, partialTick);
        }
        for (int i = 0; i < poolUsed; i++) {
            out.add(POOL.get(i));
        }
        return out;
    }
}
