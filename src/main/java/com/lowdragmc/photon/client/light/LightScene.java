package com.lowdragmc.photon.client.light;

import com.lowdragmc.photon.client.render.PhotonTextureCopy;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** One level's lights and the GPU state its pass keeps between frames: the client world, or an editor scene. */
final class LightScene {
    // weak: an FX object dropped without being removed (a level change does that) must not leak
    final Set<LightProvider> providers = Collections.newSetFromMap(new WeakHashMap<>());
    final Set<FogProvider> fogProviders = Collections.newSetFromMap(new WeakHashMap<>());
    final VoxelWorld voxels = new VoxelWorld();
    /** Packed vanilla light (block + 16·sky) at each light, cached per block; the pass looks the colour up in the
     *  GPU lightmap. */
    final Long2FloatOpenHashMap ambient = new Long2FloatOpenHashMap();
    int ambientAge;
    /** The frame's colour and depth as they stood before the lights went on. */
    final PhotonTextureCopy snapshotColor = new PhotonTextureCopy("Photon light snapshot");
    final PhotonTextureCopy snapshotDepth = new PhotonTextureCopy("Photon light snapshot depth");
    /** Under a pack that renders its world scaled down: the snapshot depth stretched back over the screen. */
    final PhotonTextureCopy scaledDepth = new PhotonTextureCopy("Photon light scaled depth");
    final LightTarget lightBuffer = new LightTarget("Photon light irradiance", LightPipelines.BUFFER_FORMAT);
    final LightTarget blurBuffer = new LightTarget("Photon light blur", LightPipelines.BUFFER_FORMAT);
    final LightTarget volumeBuffer = new LightTarget("Photon light volume", LightPipelines.BUFFER_FORMAT);
    final LightTarget visibilityAtlas = new LightTarget("Photon light visibility", LightPipelines.VISIBILITY_FORMAT);
    long lastLitTick;

    void release() {
        snapshotColor.close();
        snapshotDepth.close();
        scaledDepth.close();
        lightBuffer.release();
        blurBuffer.release();
        volumeBuffer.release();
        visibilityAtlas.release();
        voxels.release();
        ambient.clear();
    }
}
