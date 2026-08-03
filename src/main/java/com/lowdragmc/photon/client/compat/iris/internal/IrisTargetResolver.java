package com.lowdragmc.photon.client.compat.iris.internal;

import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.lowdragmc.photon.client.compat.iris.IrisFrameTarget;
import com.lowdragmc.photon.client.render.PhotonFramebufferBlit;
import com.lowdragmc.photon.core.mixins.iris.BlendOverrideAccessors;
import com.lowdragmc.photon.core.mixins.iris.ExtendedShaderAccessor;
import com.lowdragmc.photon.core.mixins.iris.FallbackShaderAccessor;
import com.lowdragmc.photon.core.mixins.iris.IrisRenderingPipelineAccessor;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.Blaze3dRenderTargetExt;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;

/**
 * Works out where the active shader pack wants particle-stage colour written.
 *
 * <p>The layout is discovered by querying the framebuffer with GL, not by reading Iris' notion of
 * the program's {@code RENDERTARGETS} directive. The probe is authoritative and survives Iris
 * refactors; {@code RenderTargets} is consulted only to translate raw texture ids back into colortex
 * numbers for the diagnostics report.
 */
final class IrisTargetResolver {

    /** Everything that can invalidate a resolved layout, in one comparable value. */
    private record CacheKey(IrisRenderingPipeline pipeline, int fbo, ShaderKey shaderKey,
                            int colorVersion, int depthVersion, int bufferWidth, int bufferHeight,
                            boolean beforeTranslucent, @Nullable IrisCompositeMode modeOverride) {}

    /** The layout the last real particle pass resolved, and why it failed if it did. */
    private @Nullable IrisFrameTarget lastInRender;
    private @Nullable String lastFailure = "no in-render resolve yet";

    private @Nullable CacheKey cacheKey;
    private @Nullable IrisFrameTarget cached;

    void invalidate() {
        cacheKey = null;
        cached = null;
        lastInRender = null;
        IrisTextureBridge.invalidate();
    }

    @Nullable IrisFrameTarget lastInRender() {
        return lastInRender;
    }

    @Nullable String lastFailure() {
        return lastFailure;
    }

    private @Nullable IrisFrameTarget fail(String reason) {
        lastFailure = reason;
        return null;
    }

    @Nullable IrisFrameTarget resolve(boolean translucentQueue,
                                      @Nullable IrisCompositeMode modeOverride,
                                      boolean inRender) {
        var target = resolveInternal(translucentQueue, modeOverride, inRender);
        if (inRender) {
            // remember what the actual particle pass saw, so /photon_iris reports reality rather
            // than whatever a command run outside the render loop can observe
            lastInRender = target;
        }
        return target;
    }

    private @Nullable IrisFrameTarget resolveInternal(boolean translucentQueue,
                                                      @Nullable IrisCompositeMode modeOverride,
                                                      boolean inRender) {
        if (!IrisApi.getInstance().isShaderPackInUse()) return fail("no shader pack in use");
        // The shadow pass re-runs geometry into a different framebuffer at a different resolution
        // with a different projection; nothing we resolve here would be valid there.
        if (ShadowRenderer.ACTIVE) return fail("shadow pass is active");
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            return fail("no IrisRenderingPipeline (pack still loading, or vanilla pipeline)");
        }
        var accessor = (IrisRenderingPipelineAccessor) pipeline;
        if (inRender) {
            // Note: we deliberately do NOT gate on WorldRenderingPhase.PARTICLES. Iris' own
            // MixinParticleEngine does not set that phase on this NeoForge build — measured NONE
            // inside ParticleEngine.render — and Iris' particle shader override does not depend on
            // it either. isRenderingWorld plus "no editor scene manager" is the precise test for
            // "this build is the world particle pass".
            if (!accessor.photon$isRenderingWorld()) return fail("not rendering the world");
            if (PhotonParticleManager.isEditorSceneRendering()) {
                return fail("the editor scene is rendering; using the plain path");
            }
        }

        var key = translucentQueue && usesTranslucentProgram() ? ShaderKey.PARTICLES_TRANS : ShaderKey.PARTICLES;
        GlProgram shader = null;
        if (pipeline instanceof ShaderRenderingPipeline shaderPipeline) {
            var map = shaderPipeline.getShaderMap();
            if (map != null) {
                shader = map.getShader(key);
                if (shader == null && key != ShaderKey.PARTICLES) {
                    key = ShaderKey.PARTICLES;
                    shader = map.getShader(key);
                }
            }
        }

        var fbo = framebufferOf(shader, pipeline, accessor);
        if (fbo == null) return fail("Iris has no framebuffer for " + key);
        lastFailure = null;

        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        int colorVersion = -1;
        int depthVersion = -1;
        if (mainTarget instanceof Blaze3dRenderTargetExt ext) {
            colorVersion = ext.iris$getColorBufferVersion();
            depthVersion = ext.iris$getDepthBufferVersion();
        }
        var renderTargets = accessor.photon$renderTargets();
        int bufferWidth = renderTargets == null ? mainTarget.width : renderTargets.getCurrentWidth();
        int bufferHeight = renderTargets == null ? mainTarget.height : renderTargets.getCurrentHeight();

        var freshKey = new CacheKey(pipeline, fbo.getId(), key, colorVersion, depthVersion,
                bufferWidth, bufferHeight, pipeline.isBeforeTranslucent, modeOverride);
        // The probe costs ~30 synchronous GL queries; none of the key's members ticks per frame, so
        // in steady state this returns without touching the driver at all.
        if (cached != null && freshKey.equals(cacheKey)) return cached;

        cached = probe(pipeline, accessor, renderTargets, fbo, shader, key,
                bufferWidth, bufferHeight, depthVersion, modeOverride);
        cacheKey = freshKey;
        return cached;
    }

    private static boolean usesTranslucentProgram() {
        // Off by default: on NeoForge 1.21.1 Iris does not split the vanilla particle pass at all
        // (mixins.iris.fantastic.json registers no ParticleEngine/LevelRenderer mixin), so
        // PARTICLES_TRANS is never what the engine itself would have used.
        return PhotonConfig.INSTANCE.irisUseTranslucentParticleProgram.get();
    }

    private static @Nullable GlFramebuffer framebufferOf(@Nullable GlProgram shader,
                                                         IrisRenderingPipeline pipeline,
                                                         IrisRenderingPipelineAccessor accessor) {
        boolean before = pipeline.isBeforeTranslucent;
        if (shader instanceof ExtendedShaderAccessor extended) {
            return before ? extended.getWritingToBeforeTranslucent() : extended.getWritingToAfterTranslucent();
        }
        if (shader instanceof FallbackShaderAccessor fallback) {
            return before ? fallback.getWritingToBeforeTranslucent() : fallback.getWritingToAfterTranslucent();
        }
        // Whatever Iris would bind for an unmanaged shader: a single-attachment framebuffer on the
        // pack's fallback (scene colour) target.
        return before ? accessor.photon$defaultFB() : accessor.photon$defaultFBAlt();
    }

    private IrisFrameTarget probe(IrisRenderingPipeline pipeline,
                                  IrisRenderingPipelineAccessor accessor,
                                  @Nullable RenderTargets renderTargets,
                                  GlFramebuffer fbo,
                                  @Nullable GlProgram shader,
                                  ShaderKey key,
                                  int bufferWidth,
                                  int bufferHeight,
                                  int depthVersion,
                                  @Nullable IrisCompositeMode modeOverride) {
        // Re-probing means something about the layout changed — a pack reload, a resize, a flip. Any
        // wrapper built against the old textures is stale, and GL reuses deleted names, so a stale one
        // could silently point at somebody else's texture. Dropped here rather than on a timer because
        // this is exactly the moment the old answer stopped being true.
        IrisTextureBridge.invalidate();

        int maxAttachments = Math.min(GL30.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS), 16);
        int maxDrawBuffers = Math.min(GL30.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS), 16);

        int[] attachmentTextures = new int[maxAttachments];
        int[] colortexIndices = new int[maxAttachments];
        boolean[] attachmentIsAlt = new boolean[maxAttachments];
        int[] drawBuffers = new int[maxDrawBuffers];
        Arrays.fill(colortexIndices, -1);

        int saved = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.getId());
        try {
            for (int i = 0; i < maxAttachments; i++) {
                int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                        GL30.GL_COLOR_ATTACHMENT0 + i, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                attachmentTextures[i] = type == GL30.GL_NONE ? 0
                        : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                        GL30.GL_COLOR_ATTACHMENT0 + i, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            }
            for (int i = 0; i < maxDrawBuffers; i++) {
                drawBuffers[i] = GL30.glGetInteger(GL30.GL_DRAW_BUFFER0 + i);
            }
            int depthType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int depthTexture = depthType == GL30.GL_NONE ? 0
                    : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            boolean depthStencil = false;
            if (depthType == GL30.GL_NONE) {
                int stencilType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                        GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                if (stencilType != GL30.GL_NONE) {
                    depthStencil = true;
                    depthTexture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                            GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                }
            }

            // attachment -> colortex, both ping-pong halves
            if (renderTargets != null) {
                for (int n = 0; n < renderTargets.getRenderTargetCount(); n++) {
                    var renderTarget = renderTargets.get(n);
                    if (renderTarget == null) continue; // lazily created targets read back as null
                    int main = renderTarget.getMainTexture();
                    int alt = renderTarget.getAltTexture();
                    for (int i = 0; i < maxAttachments; i++) {
                        if (attachmentTextures[i] == 0) continue;
                        if (attachmentTextures[i] == main) {
                            colortexIndices[i] = n;
                            attachmentIsAlt[i] = false;
                        } else if (attachmentTextures[i] == alt) {
                            colortexIndices[i] = n;
                            attachmentIsAlt[i] = true;
                        }
                    }
                }
            }

            int primaryAttachment = drawBuffers[0] == GL30.GL_NONE ? 0
                    : drawBuffers[0] - GL30.GL_COLOR_ATTACHMENT0;
            if (primaryAttachment < 0 || primaryAttachment >= maxAttachments) primaryAttachment = 0;
            int primaryTexture = attachmentTextures[primaryAttachment];
            int primaryColortex = colortexIndices[primaryAttachment];

            var sceneFbo = pipeline.isBeforeTranslucent ? accessor.photon$defaultFB() : accessor.photon$defaultFBAlt();
            int sceneColorTexture = sceneFbo == null ? 0 : sceneFbo.getColorAttachment(0);
            boolean primaryIsSceneColor = primaryTexture != 0 && primaryTexture == sceneColorTexture;

            int width = bufferWidth;
            int height = bufferHeight;
            if (renderTargets != null && primaryColortex >= 0) {
                var renderTarget = renderTargets.get(primaryColortex);
                if (renderTarget != null) {
                    width = renderTarget.getWidth();
                    height = renderTarget.getHeight();
                }
            }
            // Ask GL rather than RenderTargets: the format decides whether this target can hold
            // colour at all, and the attachment does not have to map back to a known colortex.
            int internalFormat = queryInternalFormat(primaryTexture);

            int drawBufferCount = 0;
            for (int buffer : drawBuffers) {
                if (buffer != GL30.GL_NONE) drawBufferCount++;   // GL_NONE == 0
            }
            boolean accumulates = accumulatesIntoPrimary(shader);
            var mode = resolveMode(modeOverride, primaryTexture, sceneColorTexture,
                    primaryIsSceneColor, accumulates, drawBufferCount, internalFormat);
            if (mode == IrisCompositeMode.SCENE_REPLACE && sceneColorTexture != 0) {
                primaryTexture = sceneColorTexture;
                primaryIsSceneColor = true;
            }

            reportDegradations(width, height, bufferWidth, bufferHeight, depthTexture, mode);

            return new IrisFrameTarget(fbo.getId(), attachmentTextures, colortexIndices,
                    attachmentIsAlt, drawBuffers, primaryAttachment, primaryColortex, primaryTexture,
                    internalFormat, width, height, bufferWidth, bufferHeight, depthTexture, depthStencil,
                    depthVersion, primaryIsSceneColor, sceneColorTexture,
                    renderTargets == null ? depthTexture
                            : PhotonFramebufferBlit.glId(renderTargets.getDepthTexture()),
                    pipeline.isBeforeTranslucent, shaderKindOf(shader), key.name(),
                    key.getProgram() == null ? "?" : key.getProgram().getSourceName(),
                    isFloatFormat(internalFormat), accumulates, drawBufferCount, mode);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, saved);
        }
    }

    /**
     * Which of the three shader-pack architectures we are looking at. The signal is the pack's own
     * {@code blend.<program>} declaration, which Iris has already parsed — no pack is special-cased.
     *
     * <ul>
     *   <li>draw buffer 0 <b>is</b> the scene colour (BSL, Complementary, Solas) → composite now.</li>
     *   <li>not the scene colour, but the pack declares a blend function for it (Photon/SixthSurge's
     *       {@code blend.gbuffers_textured.colortex13 = ONE ONE_MINUS_SRC_ALPHA}) → it is a
     *       premultiplied translucent accumulator → composite now, the pack merges it later.</li>
     *   <li>not the scene colour, and the pack <b>replaces</b> rather than accumulates (Kappa's
     *       {@code blend.gbuffers_textured_lit = off}, Sundial's {@code blend.gbuffers_textured =
     *       ONE ZERO ONE ZERO}) or writes several targets at once (Sundial's
     *       {@code DRAWBUFFERS:012}) → the pack overwrites packed material data there and decodes
     *       it later. Nothing we write is interpretable → hold the layer back to after the pack's
     *       composites.</li>
     * </ul>
     */
    private static IrisCompositeMode resolveMode(@Nullable IrisCompositeMode override,
                                                 int primaryTexture, int sceneColorTexture,
                                                 boolean primaryIsSceneColor, boolean accumulatesIntoPrimary,
                                                 int drawBufferCount, int internalFormat) {
        if (override == IrisCompositeMode.DISABLED) return IrisCompositeMode.DISABLED;
        if (override == IrisCompositeMode.AFTER_PACK) return IrisCompositeMode.AFTER_PACK;
        if (override == IrisCompositeMode.SCENE_REPLACE) {
            return sceneColorTexture == 0 ? IrisCompositeMode.DISABLED : IrisCompositeMode.SCENE_REPLACE;
        }
        if (primaryTexture == 0) {
            // A program with no draw buffer at all: writing anywhere would be a guess.
            IrisCompat.degrade("TARGET", "the pack's particle program has no colour draw buffer; "
                    + "FX are composited after the pack instead");
            return IrisCompositeMode.AFTER_PACK;
        }
        if (override == IrisCompositeMode.PREMULTIPLIED_ACCUM) return IrisCompositeMode.PREMULTIPLIED_ACCUM;

        var notColour = whyNotAColourTarget(primaryIsSceneColor, accumulatesIntoPrimary,
                drawBufferCount, internalFormat);
        if (notColour != null) {
            IrisCompat.degrade("ENCODED_TARGET", "this pack's particle program writes packed gbuffer "
                    + "data rather than colour (" + notColour + "), so FX are composited after the "
                    + "pack's own passes — they will not receive its bloom, DOF or fog");
            return IrisCompositeMode.AFTER_PACK;
        }
        return IrisCompositeMode.PREMULTIPLIED_ACCUM;
    }

    /**
     * Why draw buffer 0 cannot be treated as a colour buffer, or null if it can.
     *
     * <p>Three independent signals, each of which alone means "packed data, not colour":
     *
     * <ul>
     *   <li><b>Not a floating-point format.</b> A pack cannot store HDR scene radiance in RGBA8, and
     *       it would not store albedo in a float buffer. BSL and Complementary declare
     *       {@code colortex0Format = R11F_G11F_B10F}; iterationT declares nothing, so colortex0 is
     *       RGBA8 — it is the albedo the pack's lighting pass reads, not the picture. Compositing
     *       there is invisible wherever the lighting pass does not run, which is exactly why FX
     *       against the sky vanished under it ({@code depth == 1.0} skips shading).</li>
     *   <li><b>The program replaces instead of blending</b> ({@code blend.<program> = off}, or a
     *       declared function whose destination factor is {@code ZERO}).</li>
     *   <li><b>It writes several targets</b> while draw buffer 0 is not the pack's scene-colour
     *       target — a gbuffer split across attachments.</li>
     * </ul>
     */
    private static @Nullable String whyNotAColourTarget(boolean primaryIsSceneColor,
                                                        boolean accumulatesIntoPrimary,
                                                        int drawBufferCount, int internalFormat) {
        if (!isFloatFormat(internalFormat)) {
            return "the target is a fixed-point format, so it cannot hold HDR scene colour";
        }
        if (!accumulatesIntoPrimary) {
            return "the program replaces its target instead of blending into it";
        }
        if (drawBufferCount > 1 && !primaryIsSceneColor) {
            return drawBufferCount + " draw buffers, none of them the pack's scene colour";
        }
        return null;
    }

    private static int queryInternalFormat(int texture) {
        if (texture == 0) return 0;
        int previous = GlStateManager._getInteger(GL30.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture);
        int format = GlStateManager._getTexLevelParameter(GL30.GL_TEXTURE_2D, 0,
                GL30.GL_TEXTURE_INTERNAL_FORMAT);
        GlStateManager._bindTexture(previous);
        return format;
    }

    /** HDR colour buffers are float; packed gbuffer channels are fixed-point. */
    private static boolean isFloatFormat(int internalFormat) {
        return switch (internalFormat) {
            case GL30.GL_R16F, GL30.GL_R32F, GL30.GL_RG16F, GL30.GL_RG32F,
                 GL30.GL_RGB16F, GL30.GL_RGB32F, GL30.GL_RGBA16F, GL30.GL_RGBA32F,
                 GL30.GL_R11F_G11F_B10F, GL30.GL_RGB9_E5 -> true;
            default -> false;
        };
    }

    /**
     * Whether the pack <b>accumulates</b> into draw buffer 0 rather than overwriting it.
     *
     * <p>The test is the destination factor, not merely "is a blend function declared". Sundial
     * declares {@code blend.gbuffers_textured = ONE ZERO ONE ZERO}, which is a blend function that
     * replaces — semantically identical to {@code off}, and equally a sign that the target holds
     * data the pack overwrites rather than colour it composites into. Only a non-{@code ZERO}
     * destination factor means "the existing contents survive and mine are added to them".
     *
     * <p>A per-buffer {@code blend.<program>.colortexN} override on slot 0 wins over the
     * program-wide directive: {@code off} globally plus an accumulating override on one buffer is
     * exactly how a separate translucent layer is declared.
     */
    private static boolean accumulatesIntoPrimary(@Nullable GlProgram shader) {
        if (shader instanceof ExtendedShaderAccessor extended) {
            var bufferOverrides = extended.getBufferBlendOverrides();
            if (bufferOverrides != null) {
                for (var bufferOverride : bufferOverrides) {
                    var accessor = (BlendOverrideAccessors.BufferBlendOverrideAccessor) bufferOverride;
                    if (accessor.photon$drawBuffer() == 0) {
                        return accumulates(accessor.photon$blendMode());
                    }
                }
            }
            return accumulates(extended.getBlendModeOverride());
        }
        if (shader instanceof FallbackShaderAccessor fallback) {
            return accumulates(fallback.getBlendModeOverride());
        }
        return true;
    }

    /** No directive at all means the pack left vanilla alpha blending in place. */
    private static boolean accumulates(@Nullable BlendModeOverride override) {
        if (override == null) return true;
        return accumulates(((BlendOverrideAccessors.BlendModeOverrideAccessor) override).photon$blendMode());
    }

    /** Null blend mode = {@link BlendModeOverride#OFF}. {@code dstRgb == GL_ZERO} = replace. */
    private static boolean accumulates(@Nullable BlendMode blendMode) {
        return blendMode != null && blendMode.dstRgb() != GL30.GL_ZERO;
    }

    private static void reportDegradations(int width, int height, int bufferWidth, int bufferHeight,
                                           int depthTexture, IrisCompositeMode mode) {
        if (mode == IrisCompositeMode.DISABLED) return;
        if (width != bufferWidth || height != bufferHeight) {
            IrisCompat.degrade("SIZE", "the pack's particle target is %dx%d while the frame is %dx%d "
                    .formatted(width, height, bufferWidth, bufferHeight)
                    + "(render scale or size.buffer.colortexN); FX may not line up");
        }
        var mainDepthView = Minecraft.getInstance().getMainRenderTarget().getDepthTextureView();
        int mainDepth = mainDepthView == null ? 0
                : PhotonFramebufferBlit.glId(mainDepthView.texture());
        if (depthTexture != 0 && mainDepth != 0 && depthTexture != mainDepth) {
            IrisCompat.degrade("DEPTH", "the pack's depth attachment (%d) is not MC's main depth (%d); "
                    .formatted(depthTexture, mainDepth) + "FX occlusion follows the pack's buffer");
        }
    }

    private static String shaderKindOf(@Nullable GlProgram shader) {
        if (shader instanceof ExtendedShaderAccessor) return "ExtendedShader";
        if (shader instanceof FallbackShaderAccessor) return "FallbackShader";
        return "default-framebuffer";
    }
}
