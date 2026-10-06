package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The GL state a material can ask its {@code MaterialSetting} to draw with. {@code backFacesFirst} draws the
 * geometry twice, back faces then front ones: a closed transparent mesh — a shield, an orb — then sorts itself.
 */
@OnlyIn(Dist.CLIENT)
public record MaterialRenderState(BlendMode blendMode, boolean cull, boolean depthTest, boolean depthMask,
                                  boolean backFacesFirst) {
    public MaterialRenderState(BlendMode blendMode, boolean cull, boolean depthTest, boolean depthMask) {
        this(blendMode, cull, depthTest, depthMask, false);
    }
}
