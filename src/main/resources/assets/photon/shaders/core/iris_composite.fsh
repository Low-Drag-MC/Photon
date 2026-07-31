#version 150

// Hands Photon's finished FX image to the active shader pack.
//
// The input is a PREMULTIPLIED accumulation buffer: rgb already carries the colour weighted by
// coverage, a is the coverage itself. The caller blends with ONE / ONE_MINUS_SRC_ALPHA, which is
// exactly what a pack's own translucent program does (e.g. Photon/SixthSurge declares
// `blend.gbuffers_textured.colortex13 = ONE ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA`), so the
// pack composites our FX the same way it composites water.
uniform sampler2D DiffuseSampler;

// Coverage source, kept separate from the colour source so bloom can run in between: the bloom
// chain ends on an opaque alpha, which would destroy the coverage the composite depends on. When
// no bloom runs the caller binds the same texture to both and this is a no-op.
uniform sampler2D AlphaSampler;

// rgb = colour-space / tint correction, a = exposure. Identity by default; the seam for a per-pack
// profile (packs do not agree on a working colour space — Photon/SixthSurge is linear Rec.2020).
uniform vec4 ColorModulate;

out vec4 fragColor;

void main() {
    // texelFetch, not a varying: source and destination are the same attachment size by
    // construction, so this stays 1:1 regardless of the viewport the pack left set (render scale,
    // TAAU, or the editor's sub-viewport).
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec3 color = texelFetch(DiffuseSampler, texel, 0).rgb;
    float coverage = texelFetch(AlphaSampler, texel, 0).a;
    fragColor = vec4(color * ColorModulate.rgb * ColorModulate.a, coverage);
}
