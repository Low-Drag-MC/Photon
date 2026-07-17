package com.lowdragmc.photon.client.postfx.shadergraph;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;

/**
 * The fullscreen compile target: node semantics identical to KilaGraph's compiler, over a bare
 * {@code in vec3 Position} NDC quad (see {@code FullscreenPositionBlock} for the pass-through
 * {@code gl_Position}). The one retarget is UV: every uv read routes through the
 * {@value #FS_UV} varying ({@code Position.xy * 0.5 + 0.5}) — <b>resolution-independent</b>, so a
 * half-res pass samples its inputs correctly, which window-relative {@code gl_FragCoord / ScreenSize}
 * would not.
 */
public class PhotonFullscreenCompiler extends ShaderGraphCompiler {

    /** The fullscreen 0..1 uv varying every uv/screen-uv read resolves through. */
    public static final String FS_UV = "photon_fs_uv";

    /** The {@code #define} the runtime builds the single shader variant under (defines-qualified
     *  program cache key — see {@code ShaderGraphRuntime.BASE_VARIANT_DEFINE} for why). */
    public static final String DEFINE = "PHOTON_FULLSCREEN";

    /** Suffix of the per-sampler size uniform ({@code vec4(w, h, 1/w, 1/h)}), set by the effect
     *  executor for every render-target it binds; see {@code TexelSizeNode}. */
    public static final String TEXEL_SIZE_SUFFIX = "_TexelSize";

    public PhotonFullscreenCompiler(FullscreenShaderGraph graph) {
        super(graph);
    }

    /** The fullscreen uv: quad NDC mapped to 0..1 in the vertex stage, interpolated across the pass. */
    ShaderExpr fullscreenUv() {
        return varyingInput(FS_UV, GlslType.VEC2,
                () -> new ShaderExpr("(Position.xy * 0.5 + 0.5)", GlslType.VEC2),
                new ShaderExpr("vUv", GlslType.VEC2));
    }

    /** Every uv channel is the fullscreen uv — there are no UV attributes on the quad. */
    @Override
    protected ShaderExpr meshUv(RenderTypeGraphTypes.UvChannel channel) {
        return fullscreenUv();
    }

    /** Screen uv == the fullscreen uv (the pass output IS the screen), and stays correct for
     *  sub-screen-sized pass targets where {@code gl_FragCoord / ScreenSize} would be wrong. */
    @Override
    protected ShaderExpr screenUv() {
        return fullscreenUv();
    }
}
