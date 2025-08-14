package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import static com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import static com.mojang.blaze3d.platform.GlStateManager.SourceFactor;

@OnlyIn(Dist.CLIENT)
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

    public void apply() {
        if (!this.enableBlend) {
            RenderSystem.disableBlend();
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.blendEquation(this.blendFunc.op);
        RenderSystem.blendFuncSeparate(this.srcColorFactor, this.dstColorFactor, this.srcAlphaFactor, this.dstAlphaFactor);
    }

    public void reset() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

}

