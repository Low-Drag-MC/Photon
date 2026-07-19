package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * M0 stub (original in git history, 1.21 branch). Was the abstract base for shader-driven
 * materials: {@code getShader(MaterialContext)} + {@code setupUniform} + a live preview quad drawn
 * with the material's ShaderInstance — all removed 26.1 APIs.
 * <p>
 * TODO(M2): becomes the base for pipeline-driven materials (RenderPipeline + std140 uniforms);
 * live preview returns with the material pipeline path.
 */
@ParametersAreNonnullByDefault
public abstract class ShaderInstanceMaterial implements IMaterial {

    @Override
    public IGuiTexture preview() {
        // TODO(M2): live shader preview (was a fullscreen quad drawn with the material shader)
        return IGuiTexture.MISSING_TEXTURE;
    }
}
