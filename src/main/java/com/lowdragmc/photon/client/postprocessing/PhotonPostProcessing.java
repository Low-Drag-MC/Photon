package com.lowdragmc.photon.client.postprocessing;


/**
 * M0 stub (original in git history, 1.21 branch). Was the builtin mip-chain bloom: bright pass →
 * down-sample chain → additive up-sample → final scatter combine, drawn as fullscreen quads with
 * the PhotonShaders bloom ShaderInstances into LDLib2 HDRTargets — all removed APIs.
 * <p>
 * TODO(M3): rebuild as Photon frame passes (FrameGraphSetupEvent) or a vanilla
 * {@code PostChain} json (assets/photon/post_effect/bloom.json) composed via
 * {@code PostChain.addToFrame}; mip targets from the frame-graph allocator.
 */
public class PhotonPostProcessing {
}
