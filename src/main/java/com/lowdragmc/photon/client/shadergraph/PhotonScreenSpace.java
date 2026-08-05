package com.lowdragmc.photon.client.shadergraph;

import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;

/**
 * The one place Photon's two screen spaces are converted between — used by every node that crosses them, so
 * they can never drift apart.
 *
 * <ul>
 *   <li><b>screen uv</b> ({@code screenUv()}) is <b>frame</b>-relative: {@code gl_FragCoord.xy / ScreenSize}
 *       for a particle draw, the quad uv for a fullscreen pass. It is the space the scene capture is sampled
 *       in, because the capture covers the whole frame.</li>
 *   <li><b>viewport uv</b> is relative to the rect actually being projected into, and it is the space
 *       <b>NDC</b> is measured in ({@code ndc = viewportUv * 2 - 1}) — the rasteriser maps NDC through the
 *       viewport, not through the window.</li>
 * </ul>
 *
 * <p>The two coincide in-world (the level fills the frame) and diverge in the editor, whose scene renders
 * into a sub-viewport — which is exactly why hand-rolled {@code uv * 2 - 1} reconstruction looks
 * plausibly <i>scaled</i> there instead of obviously broken.</p>
 *
 * <p>{@code U_ViewPort} is engine-bound ({@code ShaderGraphMaterial} from the GL viewport, {@code PostFXCamera}
 * from the pass target), but a context that doesn't know the name — a KilaGraph node preview, which binds
 * only via {@code KGBuiltinUniforms} — leaves it at its zeroed manifest default. Both helpers therefore fall
 * back to the identity on a degenerate viewport instead of dividing by zero (a NaN would poison the whole
 * preview thumbnail).</p>
 */
public final class PhotonScreenSpace {

    private PhotonScreenSpace() {}

    /** Frame-relative screen uv &rarr; viewport uv, the space to build NDC from. */
    public static ShaderExpr toViewportUv(ShaderCompileContext ctx, ShaderExpr screenUv) {
        String viewport = ctx.useBuiltinUniform(PhotonShaderCompiler.VIEWPORT, GlslType.VEC4);
        String screenSize = ctx.useBuiltinUniform("ScreenSize", GlslType.VEC2);
        // hoisted: the uv appears twice below, and it may be an arbitrarily large sub-expression
        String uv = ctx.temp(GlslType.VEC2, screenUv.code()).code();
        return new ShaderExpr("(" + bound(viewport) + " ? ((" + uv + " * " + screenSize + " - "
                + viewport + ".xy) / " + viewport + ".zw) : " + uv + ")", GlslType.VEC2);
    }

    /**
     * Viewport uv &rarr; frame-relative screen uv — the exact inverse of {@link #toViewportUv}, so a
     * project/un-project round trip is the identity in every context.
     *
     * @param viewportUv a GLSL expression evaluating to a 0..1 position in the viewport. It is referenced
     *                   twice, so pass a variable ({@code ctx.temp(...)}), not a compound expression.
     */
    public static ShaderExpr toScreenUv(ShaderCompileContext ctx, String viewportUv) {
        String viewport = ctx.useBuiltinUniform(PhotonShaderCompiler.VIEWPORT, GlslType.VEC4);
        String screenSize = ctx.useBuiltinUniform("ScreenSize", GlslType.VEC2);
        return new ShaderExpr("(" + bound(viewport) + " ? ((" + viewport + ".xy + " + viewportUv + " * "
                + viewport + ".zw) / " + screenSize + ") : " + viewportUv + ")", GlslType.VEC2);
    }

    /** Whether the viewport uniform actually got bound (a zero-sized rect means "nobody set it"). */
    private static String bound(String viewport) {
        return "all(greaterThan(" + viewport + ".zw, vec2(0.0)))";
    }
}
