package com.lowdragmc.photon.client.compat.iris;

/**
 * A resolved snapshot of where the active shader pack expects particle-stage colour to go.
 *
 * <p>Deliberately made of primitives only: it crosses the class-loading firewall between
 * {@link IrisCompat} and {@code internal.IrisBridgeImpl}, so no {@code net.irisshaders} type may
 * appear here.
 *
 * <p>The layout is discovered by <b>probing the framebuffer with GL</b> rather than by trusting
 * Iris internals, then cross-referenced against {@code RenderTargets} purely to recover the
 * human-meaningful colortex numbers. That matters because the whole class of bugs this fixes came
 * from assuming attachment 0 is the scene: on a deferred pack it is usually a cleared, premultiplied
 * translucent accumulator instead.
 *
 * @param fboId               the pack gbuffer framebuffer for the resolved program
 * @param attachmentTextures  index = {@code GL_COLOR_ATTACHMENT0 + i}, value = GL texture (0 = none)
 * @param colortexIndices     index = attachment, value = colortex number, or -1 if unmapped
 * @param attachmentIsAlt     index = attachment, true when it is that colortex's ping-pong ALT buffer
 * @param drawBuffers         raw {@code GL_DRAW_BUFFERi} values
 * @param primaryAttachment   the attachment {@code GL_DRAW_BUFFER0} points at
 * @param primaryColortexIndex colortex number of the primary attachment, or -1
 * @param primaryTexture      GL texture of the primary attachment — where our composite lands
 * @param internalFormat      GL internal format of the primary attachment
 * @param width               primary attachment width  (NOT the main render target's)
 * @param height              primary attachment height
 * @param bufferWidth         {@code RenderTargets.getCurrentWidth()}
 * @param bufferHeight        {@code RenderTargets.getCurrentHeight()}
 * @param depthTexture        the framebuffer's depth (or depth-stencil) texture
 * @param depthStencil        true when it is attached as {@code GL_DEPTH_STENCIL_ATTACHMENT}
 * @param depthBufferVersion  {@code Blaze3dRenderTargetExt#iris$getDepthBufferVersion()} at resolve time
 * @param primaryIsSceneColor primary == the pack's current scene-colour texture
 * @param sceneColorTexture   the pack's scene colour (colortex0 by default), flip already resolved
 * @param sceneDepthTexture   depthtex0
 * @param isBeforeTranslucent {@code IrisRenderingPipeline.isBeforeTranslucent} at resolve time
 * @param shaderKind          "ExtendedShader" / "FallbackShader" / "default-framebuffer"
 * @param shaderKey           "PARTICLES" or "PARTICLES_TRANS"
 * @param programName         the pack program the key resolved to, best effort
 * @param floatFormat         primary is a floating-point format, i.e. can hold HDR scene colour
 * @param accumulates         the pack blends into the primary rather than replacing it
 * @param drawBufferCount     how many colour targets the program writes at once
 * @param compositeMode       how {@code PhotonWorldRenderState} should hand the image back
 */
public record IrisFrameTarget(
        int fboId,
        int[] attachmentTextures,
        int[] colortexIndices,
        boolean[] attachmentIsAlt,
        int[] drawBuffers,
        int primaryAttachment,
        int primaryColortexIndex,
        int primaryTexture,
        int internalFormat,
        int width,
        int height,
        int bufferWidth,
        int bufferHeight,
        int depthTexture,
        boolean depthStencil,
        int depthBufferVersion,
        boolean primaryIsSceneColor,
        int sceneColorTexture,
        int sceneDepthTexture,
        boolean isBeforeTranslucent,
        String shaderKind,
        String shaderKey,
        String programName,
        boolean floatFormat,
        boolean accumulates,
        int drawBufferCount,
        IrisCompositeMode compositeMode
) {
    public boolean canComposite() {
        return compositeMode != IrisCompositeMode.DISABLED && primaryTexture != 0;
    }
}
