// Soft particles: fade a fragment out as it approaches the opaque surface behind it, so a quad
// intersecting the ground stops showing the hard line where it slices through. Unity's Soft Particles /
// Unreal's DepthFade, and the same expression the shadergraph's photon_depth_fade node compiles to.
//
// ⚠️ Everything hangs off SoftParticleParams.w, which SoftParticles.apply writes on every draw — one
// program serves every material of a kind, so an OFF material that skipped it would inherit an ON one's
// fade. The branch is uniform across the draw, so the depth fetch never runs when it is off.

uniform sampler2D SamplerSceneDepth;
uniform mat4 U_InverseProjectionMatrix;
// x = fade distance in blocks, y = exponent, z = 1 fades alpha only, w = 0 disables
uniform vec4 SoftParticleParams;

// Clip-space NDC z (-1..1) -> positive eye-space distance from the camera. Reconstructed through the
// inverse projection rather than a near/far pair, so reverse-Z and infinite-far projections come out
// right — MIRRORS kilagraph:kg_scene.glsl's kg_eye_from_ndcz, which is what the DepthFade node uses.
float photon_eye_from_ndcz(float ndcZ) {
    vec4 view = U_InverseProjectionMatrix * vec4(0.0, 0.0, ndcZ, 1.0);
    return -(view.z / view.w);
}

/**
 * ⚠️ Apply this AFTER the fog, not before. linear_fog mixes rgb toward FogColor, so a fragment faded to
 * nothing first comes back out of the fog as FogColor * fogValue — an additive particle would leave a
 * glowing seam exactly where the fade was supposed to remove one.
 */
vec4 photon_soft_particle(vec4 color) {
    if (SoftParticleParams.w < 0.5) return color;
    // gl_FragCoord is window-relative and the capture is the main target's size, so the texture's own
    // dimensions ARE the space the fragment coordinate lives in — no ScreenSize uniform to keep in sync,
    // and correct in the editor's sub-viewport too.
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(SamplerSceneDepth, 0));
    float sceneEye = photon_eye_from_ndcz(texture(SamplerSceneDepth, uv).r * 2.0 - 1.0);
    float fragEye = photon_eye_from_ndcz(gl_FragCoord.z * 2.0 - 1.0);
    float fade = clamp((sceneEye - fragEye) / max(SoftParticleParams.x, 1e-5), 0.0, 1.0);
    fade = pow(fade, max(SoftParticleParams.y, 1e-5));
    // Alpha-only suits SRC_ALPHA/ONE_MINUS_SRC_ALPHA; scaling the whole vec4 suits additive and the
    // premultiplied late-composite layer. The material cannot tell which it is — BlendMode lives on the
    // pass — so the author picks.
    return SoftParticleParams.z > 0.5 ? vec4(color.rgb, color.a * fade) : color * fade;
}
