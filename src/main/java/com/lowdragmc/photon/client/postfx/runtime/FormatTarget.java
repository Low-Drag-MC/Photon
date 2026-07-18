package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.lowdragmc.photon.client.postfx.graph.TargetFormat;
import com.mojang.blaze3d.platform.GlStateManager;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL30;

/**
 * A pooled post-effect target with a non-RGBA16F pixel format. {@code HDRTarget.createBuffers}
 * hardcodes RGBA16F, so this re-specifies the color texture's storage right after creation/resize —
 * the FBO attachment references the texture object, which survives a respec. (The transient double
 * allocation only happens on create/resize; pooled targets are long-lived.)
 */
@OnlyIn(Dist.CLIENT)
public class FormatTarget extends HDRTarget {

    @Getter
    private final TargetFormat format;

    public FormatTarget(int width, int height, int filterMode, TargetFormat format) {
        this(width, height, filterMode, format, false);
    }

    /** {@code useDepth} variant — the CustomMask target is an R8 color WITH its own depth. */
    public FormatTarget(int width, int height, int filterMode, TargetFormat format, boolean useDepth) {
        super(width, height, filterMode, useDepth);
        this.format = format;
        respecColor();
    }

    @Override
    public void resize(int width, int height, boolean clearError) {
        super.resize(width, height, clearError);
        if (format != null) { // null while the super constructor's initial resize runs
            respecColor();
        }
    }

    private void respecColor() {
        if (format == TargetFormat.RGBA16F) return; // already what HDRTarget allocated
        GlStateManager._bindTexture(this.colorTextureId);
        switch (format) {
            case RGBA8 -> GlStateManager._texImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA8,
                    this.width, this.height, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null);
            case RG16F -> GlStateManager._texImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RG16F,
                    this.width, this.height, 0, GL30.GL_RG, GL30.GL_FLOAT, null);
            case R16F -> GlStateManager._texImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_R16F,
                    this.width, this.height, 0, GL30.GL_RED, GL30.GL_FLOAT, null);
            case R8 -> GlStateManager._texImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_R8,
                    this.width, this.height, 0, GL30.GL_RED, GL30.GL_UNSIGNED_BYTE, null);
            default -> { }
        }
        GlStateManager._bindTexture(0);
        this.clear(Minecraft.ON_OSX);
    }
}
