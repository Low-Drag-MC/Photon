package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.mojang.blaze3d.vertex.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote IPhotonParticleRenderType
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public abstract class PhotonFXRenderPass {
    public boolean isParallel() {
        return false;
    }

    /**
     * setup opengl environment, setup shaders, uniforms.
     */
    public void prepareStatus(RenderPassPipeline pipeline) {

    }

    public abstract BufferBuilder begin(Tesselator tesselator);


    /**
     * restore opengl environment.
     */
    public void releaseStatus(RenderPassPipeline pipeline) {

    }

    public int layerOrder() {
        return 0;
    }
}
