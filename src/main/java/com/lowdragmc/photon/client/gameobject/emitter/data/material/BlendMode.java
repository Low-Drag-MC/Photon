package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

/** The serialized blend configuration of a material slot, baked into the pipeline by {@link #toBlendFunction()}. */
@Getter @Setter
@EqualsAndHashCode
public class BlendMode {
    /** Serialized by name. */
    public enum BlendFuc {
        ADD(BlendOp.ADD),
        SUB(BlendOp.SUBTRACT),
        REVERSE_SUB(BlendOp.REVERSE_SUBTRACT),
        MIN(BlendOp.MIN),
        MAX(BlendOp.MAX);
        public final BlendOp op;

        BlendFuc(BlendOp op) {
            this.op = op;
        }
    }

    /** The blends nearly every effect uses, by name. */
    public enum Preset {
        ALPHA,
        ADDITIVE,
        /** {@code ONE / ONE_MINUS_SRC_ALPHA}: the shader premultiplies, and alpha 0 is purely additive. */
        PREMULTIPLIED,
        MULTIPLY,
        OPAQUE;

        public BlendMode create() {
            return switch (this) {
                case ALPHA -> new BlendMode();
                case ADDITIVE -> new BlendMode(BlendFactor.SRC_ALPHA, BlendFactor.ONE,
                        BlendFactor.ONE, BlendFactor.ZERO, BlendFuc.ADD);
                case PREMULTIPLIED -> new BlendMode(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA,
                        BlendFactor.ONE, BlendFactor.ZERO, BlendFuc.ADD);
                case MULTIPLY -> new BlendMode(BlendFactor.DST_COLOR, BlendFactor.ZERO,
                        BlendFactor.ONE, BlendFactor.ZERO, BlendFuc.ADD);
                case OPAQUE -> new BlendMode(false, BlendFactor.ONE, BlendFactor.ZERO,
                        BlendFactor.ONE, BlendFactor.ZERO, BlendFuc.ADD);
            };
        }

        public String langKey() {
            return "photon.blend_preset." + name().toLowerCase();
        }

        // private: a BlendMode is mutable, so these never leave this class
        private static final BlendMode[] REFERENCE = new BlendMode[values().length];

        static {
            for (var preset : values()) REFERENCE[preset.ordinal()] = preset.create();
        }

        /** The preset {@code mode} is, judged by what it does to colour; null when it is none of them. */
        @javax.annotation.Nullable
        public static Preset of(BlendMode mode) {
            if (!mode.isEnableBlend()) return OPAQUE;
            if (mode.getBlendFunc() != BlendFuc.ADD) return null;
            for (var preset : values()) {
                if (preset == OPAQUE) continue;
                var candidate = REFERENCE[preset.ordinal()];
                if (candidate.getSrcColorFactor() == mode.getSrcColorFactor()
                        && candidate.getDstColorFactor() == mode.getDstColorFactor()) {
                    return preset;
                }
            }
            return null;
        }
    }

    @Configurable(name = "BlendMode.enableBlend")
    private boolean enableBlend;
    @Configurable(name = "BlendMode.srcColorFactor")
    private BlendFactor srcColorFactor;
    @Configurable(name = "BlendMode.dstColorFactor")
    private BlendFactor dstColorFactor;
    @Configurable(name = "BlendMode.srcAlphaFactor")
    private BlendFactor srcAlphaFactor;
    @Configurable(name = "BlendMode.dstAlphaFactor")
    private BlendFactor dstAlphaFactor;
    @Configurable(name = "BlendMode.blendFunc")
    private BlendFuc blendFunc;

    private BlendMode(boolean enableBlend, BlendFactor srcColorFactor, BlendFactor dstColorFactor, BlendFactor srcAlphaFactor, BlendFactor dstAlphaFactor, BlendFuc blendFunc) {
        this.srcColorFactor = srcColorFactor;
        this.dstColorFactor = dstColorFactor;
        this.srcAlphaFactor = srcAlphaFactor;
        this.dstAlphaFactor = dstAlphaFactor;
        this.enableBlend = enableBlend;
        this.blendFunc = blendFunc;
    }

    public BlendMode() {
        this(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO, BlendFuc.ADD);
    }

    public BlendMode(BlendFactor srcFactor, BlendFactor dstFactor, BlendFuc blendFunc) {
        this(true, srcFactor, dstFactor, srcFactor, dstFactor, blendFunc);
    }

    public BlendMode(BlendFactor srcColorFactor, BlendFactor dstColorFactor, BlendFactor srcAlphaFactor, BlendFactor dstAlphaFactor, BlendFuc blendFunc) {
        this(true, srcColorFactor, dstColorFactor, srcAlphaFactor, dstAlphaFactor, blendFunc);
    }

    /**
     * Pipeline-side blend state; null = blending disabled (opaque pipeline variant).
     * <p>
     * The ALPHA channel always uses coverage semantics {@code (ONE, ONE_MINUS_SRC_ALPHA, ADD)} — the
     * configured alpha factors (1.21 default {@code (ONE, ZERO)}) never affected on-screen color,
     * but they would overwrite the render target's alpha/coverage channel, which the editor's
     * premultiplied PIP composite (and vanilla's translucency stages) trust. Matches vanilla's
     * {@code BlendFunction.TRANSLUCENT} alpha behavior; the serialized fields stay untouched.
     * The authored operation applies to colour only.
     */
    @Nullable
    public BlendFunction toBlendFunction() {
        if (!enableBlend) {
            return null;
        }
        var op = blendFunc == null ? BlendOp.ADD : blendFunc.op;
        return new BlendFunction(new BlendEquation(srcColorFactor, dstColorFactor, op),
                new BlendEquation(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendOp.ADD));
    }
}
