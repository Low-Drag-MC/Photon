#ifndef PHOTON_OIT_GLSL
#define PHOTON_OIT_GLSL

// Photon's fragment output. The caller declares none of its own and ends with photon_write(color), the colour as its
// blend reads it. Under improved transparency its pipeline's OIT variants (PhotonOit) define vanilla's phase, plus
// how the blend reads the colour:
//   PHOTON_OIT_PREMULTIPLIED  (ONE, ONE_MINUS_SRC_ALPHA): rgb already weighted by coverage
//   PHOTON_OIT_ADDITIVE_ONE   (ONE, ONE): the whole colour adds
//   OIT_ADDITIVE (vanilla)    (SRC_ALPHA, ONE) and (ONE, ONE): adds light, hides nothing

#ifdef OIT
#include <minecraft:oit.glsl>
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 photon_FragColor;
#endif

void photon_write(vec4 color) {
#if defined(OIT_ALPHA_ONLY)
    float alpha = clamp(color.a, 0.0, 1.0);
    #ifdef OIT_DEPTH_BOUNDS
        #if defined(PHOTON_OIT_ADDITIVE_ONE) || defined(PHOTON_OIT_PREMULTIPLIED)
    bool addsLight = any(greaterThan(color.rgb, vec3(0.0)));
        #else
    bool addsLight = false;
        #endif
    if (alpha <= 0.0 && !addsLight) {
        discard;
    }
    // vanilla's phase drops what is fainter, which the accumulation still adds: the composite would then write the
    // far plane where it is the only surface
    alpha = max(alpha, 0.01);
    #endif
    #ifdef OIT_ADDITIVE
    // never an opaque bound, like vanilla's additive surfaces
    alpha = min(alpha, 0.99);
    #endif
    executeAlphaOnlyPhase(gl_FragCoord.z, alpha);
#else
    #ifdef OIT_ACCUMULATE
        #if defined(PHOTON_OIT_PREMULTIPLIED)
    // weighted by what lies in front of it, without the accumulation's own premultiply: a premultiplied colour may
    // carry light at zero coverage, which dividing it back out would lose
    float coverage = clamp(color.a, 0.0, 1.0);
            #ifdef RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE
    gl_FragDepth = gl_FragCoord.z;
            #endif
    color = vec4(color.rgb, coverage)
            * sampleTransmittance(ivec2(gl_FragCoord.xy), normalizeDepth(gl_FragCoord.z), 1.0 - coverage);
        #else
            #ifdef PHOTON_OIT_ADDITIVE_ONE
    color.a = 1.0;
            #endif
    color.a = clamp(color.a, 0.0, 1.0);
    color = sampleColorForAccumulation(color);
        #endif
    #endif
    photon_FragColor = color;
#endif
}

#endif
