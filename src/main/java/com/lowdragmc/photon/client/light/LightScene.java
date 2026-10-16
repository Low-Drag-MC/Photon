package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.client.postfx.runtime.FormatTarget;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** One level's lights and the GPU state its pass keeps between frames: the client world, or an editor scene. */
@OnlyIn(Dist.CLIENT)
final class LightScene {
    // weak: an FX object dropped without being removed (a level change does that) must not leak
    final Set<LightProvider> providers = Collections.newSetFromMap(new WeakHashMap<>());
    final Set<FogProvider> fogProviders = Collections.newSetFromMap(new WeakHashMap<>());
    final VoxelWorld voxels = new VoxelWorld();
    /** Vanilla light where a light sits, per block: it barely changes between frames and is slow to read. */
    final Long2FloatOpenHashMap ambient = new Long2FloatOpenHashMap();
    int ambientAge;
    @Nullable
    FormatTarget snapshot;
    @Nullable
    FormatTarget lightBuffer;
    @Nullable
    FormatTarget blurBuffer;
    @Nullable
    FormatTarget volumeBuffer;
    @Nullable
    FormatTarget visibilityAtlas;
    long lastLitTick;

    void release() {
        if (snapshot != null) snapshot.destroyBuffers();
        if (lightBuffer != null) lightBuffer.destroyBuffers();
        if (blurBuffer != null) blurBuffer.destroyBuffers();
        if (volumeBuffer != null) volumeBuffer.destroyBuffers();
        if (visibilityAtlas != null) visibilityAtlas.destroyBuffers();
        snapshot = lightBuffer = blurBuffer = volumeBuffer = visibilityAtlas = null;
        voxels.release();
        ambient.clear();
    }
}
