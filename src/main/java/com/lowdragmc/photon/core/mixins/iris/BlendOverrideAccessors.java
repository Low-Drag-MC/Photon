package com.lowdragmc.photon.core.mixins.iris;

import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the blend directives a pack declared for a program.
 *
 * <p>These are how Photon tells apart "draw buffer 0 is a colour target I may blend my FX into"
 * from "draw buffer 0 is packed gbuffer data the pack overwrites and decodes later" — without
 * knowing anything about the individual pack.
 */
public final class BlendOverrideAccessors {

    private BlendOverrideAccessors() {
    }

    @Mixin(BlendModeOverride.class)
    public interface BlendModeOverrideAccessor {
        /** Null exactly for {@link BlendModeOverride#OFF}, i.e. "blending disabled for this program". */
        @Accessor("blendMode")
        BlendMode photon$blendMode();
    }

    @Mixin(BufferBlendOverride.class)
    public interface BufferBlendOverrideAccessor {
        /** The draw-buffer slot this override applies to (not the colortex number). */
        @Accessor("drawBuffer")
        int photon$drawBuffer();

        @Accessor("blendMode")
        BlendMode photon$blendMode();
    }
}
