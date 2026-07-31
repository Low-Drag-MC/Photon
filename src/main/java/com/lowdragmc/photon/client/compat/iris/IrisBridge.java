package com.lowdragmc.photon.client.compat.iris;

import org.jetbrains.annotations.Nullable;

/**
 * The class-loading firewall between Photon and Iris.
 *
 * <p>Only {@code com.lowdragmc.photon.client.compat.iris.internal.IrisBridgeImpl} may import
 * {@code net.irisshaders.**}. Everything else talks to Iris through this interface, whose signatures
 * are restricted to primitives and Photon's own records — so a class implementing or calling it can
 * be verified and loaded on an installation without Iris.
 *
 * <p>The previous arrangement (Iris types referenced inline in {@code Photon} and
 * {@code RenderPassPipeline}) only survives because HotSpot resolves constant-pool entries lazily,
 * at first <i>execution</i>; that is luck, not design, and it gets more fragile with each reference
 * added.
 */
public interface IrisBridge {

    IrisBridge NOOP = new IrisBridge() {};

    default boolean isModInstalled() {
        return false;
    }

    default boolean isUsingShaderPack() {
        return false;
    }

    default boolean isShadowPass() {
        return false;
    }

    /** Name of the active shader pack, or {@code ""}. */
    default String packName() {
        return "";
    }

    /** Iris' own version string, or {@code ""}. */
    default String irisVersion() {
        return "";
    }

    /**
     * Resolve the pack's particle-stage colour layout, or {@code null} when Photon should use its
     * plain (no shader pack) path. Only valid on the render thread, inside the world particle phase.
     */
    default @Nullable IrisFrameTarget resolveFrameTarget(boolean translucentQueue, IrisCompositeMode modeOverride) {
        return null;
    }

    /** Same, minus the "we are inside the world particle pass" gate — for diagnostics. */
    default @Nullable IrisFrameTarget resolveForDiagnostics(boolean translucentQueue, IrisCompositeMode modeOverride) {
        return null;
    }

    /** What the last real particle pass resolved. Null before the first one, or if it failed. */
    default @Nullable IrisFrameTarget lastInRenderTarget() {
        return null;
    }

    /** Why the last resolve produced nothing, or null if it succeeded. */
    default @Nullable String lastFailure() {
        return "Iris is not installed";
    }

    /**
     * A cached framebuffer with exactly one colour attachment (the target's primary texture) and no
     * depth. Writing through it is what keeps the pack's other draw buffers untouched — a fullscreen
     * quad with a single fragment output into a multi-attachment FBO leaves attachments 1..n
     * undefined across the whole screen.
     */
    default int compositeFramebuffer(IrisFrameTarget target) {
        return 0;
    }

    /** Drop every cached framebuffer / resolved layout. Safe to call off a pack reload. */
    default void invalidate() {
    }

    /** Iris masks colour+depth while a shader it does not manage is applied; if that lock leaks, every Photon draw silently writes nothing. */
    default boolean isDepthColorLocked() {
        return false;
    }

    /** The analogous blend lock — while held, every {@code MaterialSetting.pre()} no-ops. */
    default boolean isBlendLocked() {
        return false;
    }

    default void unlockDepthColorIfLocked() {
    }

}
