package com.lowdragmc.photon.client.compat.iris;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable dump of the resolved shader-pack layout.
 *
 * <p>This is the tool for the pack-compatibility matrix: because resolution no longer has to happen
 * inside the particle draw, a whole pack can be characterised with no FX on screen at all.
 */
public final class IrisDiagnostics {

    private IrisDiagnostics() {
    }

    public static List<String> report(@Nullable IrisFrameTarget target) {
        List<String> lines = new ArrayList<>();
        if (!IrisCompat.isModInstalled()) {
            lines.add("Iris is not installed — Photon is on its plain render path.");
            return lines;
        }
        lines.add("iris %s   pack \"%s\"   inUse=%s   shadowPass=%s"
                .formatted(orUnknown(IrisCompat.irisVersion()), orUnknown(IrisCompat.packName()),
                        IrisCompat.isUsingShaderPack(), IrisCompat.isShadowPass()));
        if (target == null) {
            lines.add("no frame target resolved — Photon renders on the plain path");
            var reason = IrisCompat.lastFailure();
            if (reason != null) lines.add("reason         " + reason);
            appendDegradations(lines);
            return lines;
        }

        lines.add("source         %s".formatted(IrisCompat.hasInRenderTarget()
                ? "the last world particle pass" : "a live probe (no particle pass has run yet)"));
        lines.add("program        %s -> %s   (pack program \"%s\")"
                .formatted(target.shaderKey(), target.shaderKind(), target.programName()));
        lines.add("stage          isBeforeTranslucent=%s".formatted(target.isBeforeTranslucent()));
        lines.add("framebuffer    id=%d   drawBuffers=%s"
                .formatted(target.fboId(), formatDrawBuffers(target)));
        lines.add("attachments");
        var textures = target.attachmentTextures();
        for (int i = 0; i < textures.length; i++) {
            if (textures[i] == 0) continue;
            int colortex = target.colortexIndices()[i];
            lines.add("  ATT%-2d tex=%-5d %-16s %-18s%s".formatted(
                    i,
                    textures[i],
                    colortex < 0 ? "(unmapped)" : "colortex%d (%s)".formatted(colortex,
                            target.attachmentIsAlt()[i] ? "alt" : "main"),
                    i == target.primaryAttachment() ? formatFormat(target.internalFormat()) : "",
                    i == target.primaryAttachment() ? "  <= PRIMARY (draw buffer 0)" : ""));
        }
        lines.add("primary        tex=%d   %dx%d   %s"
                .formatted(target.primaryTexture(), target.width(), target.height(),
                        formatFormat(target.internalFormat())));
        lines.add("depth          tex=%d   %s   version=%d"
                .formatted(target.depthTexture(),
                        target.depthStencil() ? "DEPTH_STENCIL" : "DEPTH",
                        target.depthBufferVersion()));
        lines.add("classification floatFormat=%s  accumulates=%s  drawBuffers=%d  (all three must hold "
                .formatted(target.floatFormat(), target.accumulates(), target.drawBufferCount())
                + "to composite in place)");
        lines.add("scene colour   tex=%d   primaryIsSceneColor=%s"
                .formatted(target.sceneColorTexture(), target.primaryIsSceneColor() ? "YES" : "NO"));
        lines.add("composite      %s   %s".formatted(target.compositeMode(), switch (target.compositeMode()) {
            case PREMULTIPLIED_ACCUM, SCENE_REPLACE ->
                    "ONE / ONE_MINUS_SRC_ALPHA into the pack target, alphaWrite="
                            + !target.primaryIsSceneColor();
            case AFTER_PACK -> "held back, blended onto the finished frame after the pack's passes";
            case DISABLED -> "FX are not drawn under this pack";
            case AUTO -> "(unreachable — AUTO is a configured value, never a resolved one)";
        }));
        lines.add("buffers        renderTargets %dx%d".formatted(target.bufferWidth(), target.bufferHeight()));
        lines.add("locks          depthColor=%s   blend=%s".formatted(
                IrisCompat.isDepthColorLocked() ? "LOCKED" : "unlocked",
                IrisCompat.isBlendLocked() ? "LOCKED" : "unlocked"));
        appendDegradations(lines);
        return lines;
    }

    /** Two lines for the persistent overlay. */
    public static List<String> compact(@Nullable IrisFrameTarget target) {
        if (target == null) {
            return List.of("Photon/Iris: plain path (pack \"%s\")".formatted(orUnknown(IrisCompat.packName())));
        }
        return List.of(
                "Photon/Iris: %s -> %s".formatted(IrisCompat.packName(),
                        target.primaryColortexIndex() < 0 ? "att" + target.primaryAttachment()
                                : "colortex" + target.primaryColortexIndex()),
                "  %s  scene=%s  %dx%d  degraded=%d".formatted(target.compositeMode(),
                        target.primaryIsSceneColor() ? "yes" : "no",
                        target.width(), target.height(), IrisCompat.degradations().size()));
    }

    private static void appendDegradations(List<String> lines) {
        var degradations = IrisCompat.degradations();
        if (degradations.isEmpty()) return;
        lines.add("degradations");
        degradations.forEach((code, message) -> lines.add("  [%s] %s".formatted(code, message)));
    }

    private static String formatDrawBuffers(IrisFrameTarget target) {
        var builder = new StringBuilder("[");
        boolean first = true;
        for (int buffer : target.drawBuffers()) {
            if (buffer == GL30.GL_NONE) continue;   // GL_NONE == 0
            if (!first) builder.append(", ");
            builder.append("ATT").append(buffer - GL30.GL_COLOR_ATTACHMENT0);
            first = false;
        }
        return builder.append(']').toString();
    }

    private static String formatFormat(int glFormat) {
        return switch (glFormat) {
            case GL30.GL_RGBA8 -> "RGBA8";
            case GL30.GL_RGBA16 -> "RGBA16";
            case GL30.GL_RGBA16F -> "RGBA16F";
            case GL30.GL_RGBA32F -> "RGBA32F";
            case GL30.GL_RGB8 -> "RGB8";
            case GL30.GL_RGB16F -> "RGB16F";
            case GL30.GL_RG16F -> "RG16F";
            case GL30.GL_R8 -> "R8";
            case GL30.GL_R16F -> "R16F";
            case GL30.GL_R32F -> "R32F";
            case GL30.GL_R11F_G11F_B10F -> "R11F_G11F_B10F";
            default -> "0x%X".formatted(glFormat);
        };
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? "?" : value;
    }
}
