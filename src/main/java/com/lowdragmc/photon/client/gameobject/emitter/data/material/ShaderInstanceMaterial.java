package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.photon.client.render.MaterialPreviewRenderer;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Abstract base for the shader-driven materials (texture / sprite / custom). On 26.1 the render
 * seam is a {@code RenderType} built from a {@link com.mojang.blaze3d.pipeline.RenderPipeline} +
 * std140 uniforms: standard materials go through the shared value cache in
 * {@link MaterialRenderTypes}; {@code CustomShaderMaterial} owns its own RenderTypes.
 * <p>
 * TODO: live in-editor preview (1.21 drew a fullscreen quad with the material's shader). It needs an
 * off-screen material render pass and returns with the editor-preview work; until then the base
 * shows the missing texture.
 */
@ParametersAreNonnullByDefault
public abstract class ShaderInstanceMaterial implements IMaterial {

    @Override
    public IGuiTexture preview() {
        return MaterialPreviewRenderer.previewOf(this);
    }

    @Override
    public IGuiTexture previewLive() {
        return MaterialPreviewRenderer.livePreviewOf(this);
    }
}
