package com.lowdragmc.photon.client.compat.iris.internal;

import com.lowdragmc.photon.client.compat.iris.IrisBridge;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.lowdragmc.photon.client.compat.iris.IrisFrameTarget;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.Blaze3dRenderTargetExt;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * The only Photon class allowed to import {@code net.irisshaders.**}. Instantiated reflectively by
 * {@code IrisCompat} once, and only when Iris is on the classpath.
 */
public final class IrisBridgeImpl implements IrisBridge {
    // the TAAU packs (Kappa, Nostalgia): their gbuffer vertex shaders squeeze the world into this share of the screen
    private static final String RESOLUTION_SCALE = "ResolutionScale";

    private final IrisTargetResolver resolver = new IrisTargetResolver();

    @Override
    public boolean isModInstalled() {
        return true;
    }

    @Override
    public boolean isUsingShaderPack() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    @Override
    public boolean isShadowPass() {
        return ShadowRenderer.ACTIVE;
    }

    @Override
    public String packName() {
        var name = Iris.getCurrentPackName();
        return name == null ? "" : name;
    }

    @Override
    public float gbufferRenderScale() {
        if (!isUsingShaderPack()) return 1f;
        var pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) return 1f;
        var values = pack.getShaderPackOptions().getOptionValues();
        if (!values.getOptionSet().getStringOptions().containsKey(RESOLUTION_SCALE)) return 1f;
        try {
            float scale = Float.parseFloat(values.getStringValueOrDefault(RESOLUTION_SCALE));
            return scale >= 0.1f && scale < 1f ? scale : 1f;
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    @Override
    public String irisVersion() {
        var version = Iris.getVersion();
        return version == null ? "" : version;
    }

    @Override
    public @Nullable IrisFrameTarget resolveFrameTarget(boolean translucentQueue, IrisCompositeMode modeOverride) {
        return resolver.resolve(translucentQueue, modeOverride, true);
    }

    @Override
    public @Nullable IrisFrameTarget resolveForDiagnostics(boolean translucentQueue, IrisCompositeMode modeOverride) {
        return resolver.resolve(translucentQueue, modeOverride, false);
    }

    @Override
    public @Nullable IrisFrameTarget lastInRenderTarget() {
        return resolver.lastInRender();
    }

    @Override
    public @Nullable String lastFailure() {
        return resolver.lastFailure();
    }

    @Override
    public int compositeFramebuffer(IrisFrameTarget target) {
        return IrisCompositeFbo.get(target.primaryTexture());
    }

    @Override
    public void invalidate() {
        resolver.invalidate();
    }

    @Override
    public boolean isDepthColorLocked() {
        return DepthColorStorage.isDepthColorLocked();
    }

    @Override
    public boolean isBlendLocked() {
        return BlendModeStorage.isBlendLocked();
    }

    @Override
    public ShaderPackSelection shaderPackSelection() {
        var config = Iris.getIrisConfig();
        return new ShaderPackSelection(config.getShaderPackName().orElse(null), config.areShadersEnabled());
    }

    @Override
    public boolean selectShaderPack(ShaderPackSelection selection) {
        try {
            var config = Iris.getIrisConfig();
            if (selection.name() != null) {
                config.setShaderPackName(selection.name());
            }
            config.setShadersEnabled(selection.enabled());
            config.save();
            Iris.reload();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void unlockDepthColorIfLocked() {
        if (DepthColorStorage.isDepthColorLocked()) {
            DepthColorStorage.unlockDepthColor();
        }
    }

}
