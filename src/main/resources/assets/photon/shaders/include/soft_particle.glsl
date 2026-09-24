// Soft particles: fade a fragment out as it approaches the opaque surface behind it, so a quad
// intersecting the ground stops showing the hard line where it slices through. Unity's Soft Particles /
// Unreal's DepthFade, and the same expression the shadergraph's photon_depth_fade node compiles to.
//
// ⚠️ The whole thing hangs off PHOTON_SOFT, which PhotonPipelines adds only for a material whose
// SoftParticles group is enabled. Without the define this file declares NO sampler — which is the point:
// a declared sampler must be bound at every draw, and what binds it is the drain taking a full-screen
// depth copy that frame. See PhotonPipelines.SOFT_PARTICLE_DEFINE.

#ifdef PHOTON_SOFT

#moj_import <photon:engine.glsl>

uniform sampler2D SamplerSceneDepth;

/**
 * ⚠️ Apply this AFTER the fog, not before. apply_fog mixes rgb toward FogColor, so a fragment faded to
 * nothing first comes back out of the fog as FogColor * fogValue — an additive particle would leave a
 * glowing seam exactly where the fade was supposed to remove one.
 *
 * params = SoftParticleParams from the PhotonMaterial block:
 *   x = fade distance in blocks, y = exponent, z = 1 fades alpha only, w = 0 disables
 */
vec4 photon_soft_particle(vec4 color, vec4 params) {
    if (params.w < 0.5) return color;
    // gl_FragCoord is relative to the target being drawn into, and so is the capture, so the texture's own
    // dimensions ARE the space the fragment coordinate lives in — no viewport uniform to keep in sync, and
    // correct in the editor's PIP sub-viewport too.
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(SamplerSceneDepth, 0));
    float sceneEye = photon_eye_depth(texture(SamplerSceneDepth, uv).r);
    float fragEye = photon_eye_depth(gl_FragCoord.z);
    float fade = clamp((sceneEye - fragEye) / max(params.x, 1e-5), 0.0, 1.0);
    fade = pow(fade, max(params.y, 1e-5));
    // Alpha-only suits SRC_ALPHA/ONE_MINUS_SRC_ALPHA; scaling the whole vec4 suits additive and the
    // premultiplied late-composite layer. The material cannot tell which it is — BlendMode lives on the
    // pass — so the author picks.
    return params.z > 0.5 ? vec4(color.rgb, color.a * fade) : color * fade;
}

#else

/** No fade compiled in: the call sites stay identical and the optimiser drops the whole thing. */
vec4 photon_soft_particle(vec4 color, vec4 params) {
    return color;
}

#endif
