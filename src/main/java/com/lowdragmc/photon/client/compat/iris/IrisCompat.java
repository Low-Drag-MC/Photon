package com.lowdragmc.photon.client.compat.iris;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Photon's single entry point to Iris.
 *
 * <p>Everything Iris-shaped goes through here; the implementation lives behind {@link IrisBridge} so
 * that no {@code net.irisshaders} class is ever referenced from a class that loads without Iris.
 */
public final class IrisCompat {
    public static final String MOD_ID = "iris";

    private static final IrisBridge BRIDGE = createBridge();

    /**
     * Live override of the composite strategy, driven by {@code /photon_iris mode}. {@code null}
     * means "let the resolver decide".
     */
    private static @Nullable IrisCompositeMode modeOverride = null;

    /**
     * Capabilities Photon cannot reproduce faithfully under the active pack. Reported once per
     * (pack, code) to the log, and surfaced in {@code /photon_iris status} and the editor so a
     * degraded result never looks like a bug.
     */
    private static final Map<String, String> DEGRADATIONS = new LinkedHashMap<>();
    private static String degradationPack = "";

    private IrisCompat() {
    }

    private static IrisBridge createBridge() {
        if (!LDLib2.isModLoaded(MOD_ID)) {
            return IrisBridge.NOOP;
        }
        try {
            return (IrisBridge) Class
                    .forName("com.lowdragmc.photon.client.compat.iris.internal.IrisBridgeImpl")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Throwable t) {
            Photon.LOGGER.warn("Iris is present but the Photon bridge failed to load; "
                    + "FX will render on the unshaded path", t);
            return IrisBridge.NOOP;
        }
    }

    public static boolean isModInstalled() {
        return BRIDGE.isModInstalled();
    }

    public static boolean isUsingShaderPack() {
        return BRIDGE.isUsingShaderPack();
    }

    public static boolean isShadowPass() {
        return BRIDGE.isShadowPass();
    }

    public static String packName() {
        return BRIDGE.packName();
    }

    public static String irisVersion() {
        return BRIDGE.irisVersion();
    }

    /**
     * The pack's particle-stage colour layout for this draw, or {@code null} to use the plain path.
     */
    public static @Nullable IrisFrameTarget resolveFrameTarget(boolean translucentQueue) {
        var target = BRIDGE.resolveFrameTarget(translucentQueue, effectiveMode());
        if (target != null) {
            trackPack();
        }
        return target;
    }

    /**
     * What to show in a report. Prefers what the last real particle pass resolved — a command runs
     * outside {@code LevelRenderer.renderLevel}, so a live probe there can only ever say "not
     * rendering the world" and would hide the answer we actually want.
     */
    public static @Nullable IrisFrameTarget diagnosticsTarget() {
        var inRender = BRIDGE.lastInRenderTarget();
        return inRender != null ? inRender : BRIDGE.resolveForDiagnostics(true, effectiveMode());
    }

    public static boolean hasInRenderTarget() {
        return BRIDGE.lastInRenderTarget() != null;
    }

    /** Why the last resolve produced nothing, or null if it succeeded. */
    public static @Nullable String lastFailure() {
        return BRIDGE.lastFailure();
    }

    /** The command override wins over the config; {@code null} means "let the resolver decide". */
    private static @Nullable IrisCompositeMode effectiveMode() {
        if (modeOverride != null) return modeOverride;
        var configured = PhotonConfig.INSTANCE.irisCompositeMode.get();
        return configured == IrisCompositeMode.AUTO ? null : configured;
    }

    public static int compositeFramebuffer(IrisFrameTarget target) {
        return BRIDGE.compositeFramebuffer(target);
    }

    public static void invalidate() {
        BRIDGE.invalidate();
        DEGRADATIONS.clear();
    }

    public static boolean isDepthColorLocked() {
        return BRIDGE.isDepthColorLocked();
    }

    public static boolean isBlendLocked() {
        return BRIDGE.isBlendLocked();
    }

    public static void unlockDepthColorIfLocked() {
        BRIDGE.unlockDepthColorIfLocked();
    }

    public static void setModeOverride(@Nullable IrisCompositeMode mode) {
        if (modeOverride != mode) {
            modeOverride = mode;
            BRIDGE.invalidate();
        }
    }

    /// Degradations

    /**
     * Record a capability Photon cannot reproduce under the active pack. Logged the first time each
     * code is seen for a pack; "once per pack" is expressed by the map itself, which
     * {@link #trackPack()} clears when the pack changes.
     */
    public static void degrade(String code, String message) {
        // switching packs must drop stale entries before the early-out, or the new pack inherits them
        trackPack();
        // called from draw paths — nothing beyond a map lookup once a code has been recorded
        if (DEGRADATIONS.put(code, message) == null) {
            Photon.LOGGER.info("[Iris compat] {} — {}: {}", degradationPack, code, message);
        }
    }

    public static Map<String, String> degradations() {
        return DEGRADATIONS;
    }

    private static void trackPack() {
        var name = BRIDGE.packName();
        if (!name.equals(degradationPack)) {
            degradationPack = name;
            DEGRADATIONS.clear();
        }
    }
}
