package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

/**
 * 26.1 note: blend state is no longer applied imperatively (RenderSystem.blendFuncSeparate is gone);
 * it is a {@link BlendFunction} baked into a {@code RenderPipeline}. This class stays the serialized
 * blend configuration; {@link #toBlendFunction()} feeds the M2 pipeline-variant cache.
 * <p>
 * TODO(M2): vanilla 26.1 has no blend-equation concept (our SUB/REVERSE_SUB/MIN/MAX modes) —
 * needs a decision: NeoForge pipeline extension, custom pass state, or dropping those modes.
 */
@Getter @Setter
@EqualsAndHashCode
public class BlendMode {
    public enum BlendFuc {
        ADD(32774),
        SUB(32778),
        REVERSE_SUB(32779),
        MIN(32775),
        MAX(32776);
        public final int op;

        BlendFuc(int op) {
            this.op = op;
        }
    }

    @Configurable(name = "BlendMode.enableBlend")
    private boolean enableBlend;
    @Configurable(name = "BlendMode.srcColorFactor")
    private SourceFactor srcColorFactor;
    @Configurable(name = "BlendMode.dstColorFactor")
    private DestFactor dstColorFactor;
    @Configurable(name = "BlendMode.srcAlphaFactor")
    private SourceFactor srcAlphaFactor;
    @Configurable(name = "BlendMode.dstAlphaFactor")
    private DestFactor dstAlphaFactor;
    @Configurable(name = "BlendMode.blendFunc")
    private BlendFuc blendFunc;

    private BlendMode(boolean enableBlend, SourceFactor srcColorFactor, DestFactor dstColorFactor, SourceFactor srcAlphaFactor, DestFactor dstAlphaFactor, BlendFuc blendFunc) {
        this.srcColorFactor = srcColorFactor;
        this.dstColorFactor = dstColorFactor;
        this.srcAlphaFactor = srcAlphaFactor;
        this.dstAlphaFactor = dstAlphaFactor;
        this.enableBlend = enableBlend;
        this.blendFunc = blendFunc;
    }

    public BlendMode() {
        this(true, SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ZERO, BlendFuc.ADD);
    }

    public BlendMode(SourceFactor srcFactor, DestFactor dstFactor, BlendFuc blendFunc) {
        this(true, srcFactor, dstFactor, srcFactor, dstFactor, blendFunc);
    }

    public BlendMode(SourceFactor srcColorFactor, DestFactor dstColorFactor, SourceFactor srcAlphaFactor, DestFactor dstAlphaFactor, BlendFuc blendFunc) {
        this(true, srcColorFactor, dstColorFactor, srcAlphaFactor, dstAlphaFactor, blendFunc);
    }

    /** Pipeline-side blend state; null = blending disabled (opaque pipeline variant). */
    @Nullable
    public BlendFunction toBlendFunction() {
        if (!enableBlend) {
            return null;
        }
        return new BlendFunction(srcColorFactor, dstColorFactor, srcAlphaFactor, dstAlphaFactor);
    }
}
