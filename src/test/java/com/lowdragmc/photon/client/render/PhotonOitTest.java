package com.lowdragmc.photon.client.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which blends can join the OIT passes, and how the accumulation reads them. */
class PhotonOitTest {

    private static RenderPipeline pipeline(@Nullable BlendFunction blend) {
        return RenderPipeline.builder()
                .withLocation("test/oit_blend")
                .withVertexShader("core/particle")
                .withFragmentShader("core/particle")
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.ofNullable(blend), GpuFormat.RGBA16_FLOAT,
                        ColorTargetState.WRITE_ALL))
                .build();
    }

    @Test
    void expressibleBlendsMapByTheirColourHalf() {
        assertEquals(PhotonOit.Blend.ALPHA, PhotonOit.blendOf(pipeline(BlendFunction.TRANSLUCENT)));
        assertEquals(PhotonOit.Blend.PREMULTIPLIED,
                PhotonOit.blendOf(pipeline(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)));
        assertEquals(PhotonOit.Blend.ADDITIVE, PhotonOit.blendOf(pipeline(BlendFunction.LIGHTNING)));
        assertEquals(PhotonOit.Blend.ADDITIVE_ONE, PhotonOit.blendOf(pipeline(BlendFunction.ADDITIVE)));
        // a LATE draw's alpha half is rewritten for its layer; the colour half decides
        assertEquals(PhotonOit.Blend.ALPHA, PhotonOit.blendOf(pipeline(new BlendFunction(
                BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE))));
    }

    @Test
    void blendsThatReadTheDestinationOrDoNotBlendStayOut() {
        assertNull(PhotonOit.blendOf(pipeline(null)));
        assertNull(PhotonOit.blendOf(pipeline(new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO))));
        assertNull(PhotonOit.blendOf(pipeline(BlendFunction.MAX)));
        assertNull(PhotonOit.blendOf(pipeline(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE,
                BlendOp.REVERSE_SUBTRACT))));
        assertNull(PhotonOit.blendOf(pipeline(BlendFunction.INVERT)));
    }

    @Test
    void onlyAdditiveBlendsAddLightWithoutCoverage() {
        assertTrue(PhotonOit.addsLight(pipeline(BlendFunction.LIGHTNING)));
        assertTrue(PhotonOit.addsLight(pipeline(BlendFunction.ADDITIVE)));
        assertFalse(PhotonOit.addsLight(pipeline(BlendFunction.TRANSLUCENT)));
        assertFalse(PhotonOit.addsLight(pipeline(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)));
        assertFalse(PhotonOit.addsLight(pipeline(null)));
    }
}
