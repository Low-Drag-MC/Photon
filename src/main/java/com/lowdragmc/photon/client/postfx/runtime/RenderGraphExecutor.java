package com.lowdragmc.photon.client.postfx.runtime;


/**
 * M0 stub (original in git history, 1.21 branch). Executed one {@code CompiledEffect} invocation:
 * per pass — acquire a pooled target, stage builtins + blended params + texture inputs (with
 * {@code _TexelSize} companions), dispatch a fullscreen blit (ShaderInstance / KGMaterialValues /
 * HDRTarget — all removed APIs), release inputs at last use.
 * <p>
 * TODO(M3): rebuilt on RenderPass fullscreen draws with std140 UBOs; pass targets from the frame
 * graph allocator; the CompiledEffect/graph model (postfx.graph.*) is untouched and stays the
 * authoring format.
 */
public final class RenderGraphExecutor {

    private RenderGraphExecutor() {}
}
