#version 150

#moj_import <fog.glsl>
#moj_import <photon:kila_lib.glsl>
#if defined(KILA_DEPTH) || defined(KILA_SCREEN_DEPTH) || defined(KILA_PROJECTION) || defined(KILA_VOLUME_CLIP)
#moj_import <photon:soft_particle.glsl>
#endif
#ifdef KILA_LIT
#moj_import <photon:lit_particle.glsl>
#endif

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec2 ScreenSize;

// rgb = HDR colour (intensity premultiplied), a = alpha
uniform vec4 KilaTint;
uniform vec4 KilaBackTint;
uniform vec4 KilaEmission;
// x = alpha clip, y = alpha sharpen, z = world light, w = fog
uniform vec4 KilaSurface;
// x = vertex colour mode, y = BlendMode.Preset ordinal — ⚠️ both MIRRORED FROM KilaMaterial
uniform ivec4 KilaSurfaceModes;
// x = how additive the premultiplied preset is
uniform vec4 KilaOutput;
// x = what to show instead of the result — ⚠️ MIRRORED FROM KilaMaterial.DebugView (0 = the result)
uniform ivec4 KilaDebug;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec4 lightColor;
in vec3 kilaViewPos;
in vec3 kilaViewNormal;
#if defined(KILA_WORLD_UV) || defined(KILA_REL_POS)
in vec3 kilaRelPos;
#endif
#ifdef KILA_WORLD_UV
uniform vec3 KilaCameraPos;
#endif

// ⚠️ slots MIRRORED FROM KilaParam
#ifdef KILA_P0
in vec4 kilaP0;
#endif
#ifdef KILA_P1
in vec4 kilaP1;
#endif
#ifdef KILA_P2
in vec4 kilaP2;
#endif
#ifdef KILA_P3
in vec4 kilaP3;
#endif
#ifdef KILA_P4
in vec4 kilaP4;
#endif
#ifdef KILA_P5
in vec4 kilaP5;
#endif
#ifdef KILA_P6
in vec4 kilaP6;
#endif
#define KILA_ALPHA kilaP0.x
#define KILA_INTENSITY kilaP0.y
#define KILA_MAIN_OFFSET kilaP0.zw
#define KILA_MAIN_FRAME kilaP1.x
#define KILA_LAYER_STRENGTH kilaP1.y
#define KILA_LAYER_OFFSET kilaP1.zw
#define KILA_MASK1_OFFSET kilaP2.xy
#define KILA_MASK2_OFFSET kilaP2.zw
#define KILA_DISSOLVE_PROGRESS kilaP3.x
#define KILA_DISSOLVE_OFFSET kilaP3.yz
#define KILA_DISSOLVE_EDGE kilaP3.w
#define KILA_DISTORT_STRENGTH kilaP4.x
#define KILA_RAMP_OFFSET kilaP4.y
#define KILA_FRESNEL_STRENGTH kilaP4.z
#define KILA_SCREEN_STRENGTH kilaP5.x
#define KILA_DISPERSION_AMOUNT kilaP5.y
#define KILA_TWIRL_STRENGTH kilaP5.z
#define KILA_WAVE_AMPLITUDE kilaP5.w
#define KILA_CONTACT_STRENGTH kilaP6.x
#define KILA_CONTACT_WIDTH kilaP6.y
#define KILA_FLIPBOOK_SEED kilaP6.z
#define KILA_LAYER2_STRENGTH kilaP6.w

// distortion targets — ⚠️ MIRRORED FROM KilaDistortion.Target
#define KILA_FLOW_MAIN 1
#define KILA_FLOW_LAYER 2
#define KILA_FLOW_MASK1 4
#define KILA_FLOW_MASK2 8
#define KILA_FLOW_DISSOLVE 16

out vec4 fragColor;

// where every slot starts from, set once at the top of main()
vec2 kilaMeshUv;
vec2 kilaScreenUv;
vec3 kilaWorldPos;

// view space, out of the surface
vec3 kilaNormal;

vec2 kila_base(int flags) {
    return kila_base_uv(flags, kilaMeshUv, kilaScreenUv, kilaWorldPos);
}

#if defined(KILA_PROJECTION) || defined(KILA_SHADING) || defined(KILA_VOLUME)
// The quad's axes at pos: t along u, b along v, each as long as the quad; false edge-on.
// Takes derivatives: call it in uniform control flow.
bool kila_quad_frame(vec3 pos, out vec3 t, out vec3 b) {
    vec3 dpx = dFdx(pos);
    vec3 dpy = dFdy(pos);
    vec2 dux = dFdx(texCoord0);
    vec2 duy = dFdy(texCoord0);
    float det = dux.x * duy.y - duy.x * dux.y;
    if (abs(det) < 1e-12) {
        t = vec3(1.0, 0.0, 0.0);
        b = vec3(0.0, -1.0, 0.0);
        return false;
    }
    t = (dpx * duy.y - dpy * dux.y) / det;
    b = (dpy * dux.x - dpx * duy.x) / det;
    return length(cross(t, b)) > 1e-8;
}
#endif

#ifdef KILA_FLOWMAP
// x = flow cycles per second
uniform vec4 KilaFlowMap;
// two reads half a cycle apart: x, y = each copy's phase, z = the second copy's weight
vec3 kilaFlowPhase;
// whether the slot being read follows the flow: set per slot, so the reads stay in uniform control flow
bool kilaFlowing = false;
#endif

float kila_sample_scalar(sampler2D tex, vec4 st, vec4 anim, vec4 polar, ivec4 opts, vec4 channel, vec2 offset, vec2 flow) {
    vec2 uv = kila_uv(kila_base(opts.x), st, anim, polar, opts.x, offset, vec2(0.0));
#ifdef KILA_FLOWMAP
    if (kilaFlowing) {
        return mix(dot(texture(tex, uv - flow * kilaFlowPhase.x), channel),
                dot(texture(tex, uv - flow * kilaFlowPhase.y), channel), kilaFlowPhase.z) * kila_clip(uv, opts.x);
    }
#else
    uv += flow;
#endif
    return dot(texture(tex, uv), channel) * kila_clip(uv, opts.x);
}

float kila_noise_scalar(vec4 st, vec4 anim, vec4 polar, ivec4 opts, vec4 noise, vec2 offset, vec2 flow) {
    vec2 uv = kila_uv(kila_base(opts.x), st, anim, polar, opts.x, offset, vec2(0.0));
#ifdef KILA_FLOWMAP
    if (kilaFlowing) {
        return mix(kila_noise(uv - flow * kilaFlowPhase.x, noise), kila_noise(uv - flow * kilaFlowPhase.y, noise),
                kilaFlowPhase.z) * kila_clip(uv, opts.x);
    }
#else
    uv += flow;
#endif
    return kila_noise(uv, noise) * kila_clip(uv, opts.x);
}

#ifdef KILA_PROJECTION
// x = box half-height in blocks, y = where in that it starts fading, z = 1 keeps only faces square to up,
// w = 1 while the scene depth is bound
uniform vec4 KilaProjection;
// x = orientation (⚠️ MIRRORED FROM KilaProjection.Orientation: 0 ground, 1 the quad's own plane),
// y = the decal's width as a share of the quad's
uniform vec4 KilaProjectionShape;

// the surface the decal paints, camera-relative: what world-space uvs follow
vec3 kilaSurfaceRel;

// The decal uv of the scene surface behind the fragment, in the box the quad sets; keep = how much is inside it.
vec2 kila_project(out float keep) {
    keep = 1.0;
    kilaSurfaceRel = kilaRelPos;
    if (KilaProjection.w < 0.5) return texCoord0;
    vec2 depthUv = gl_FragCoord.xy / vec2(textureSize(SamplerSceneDepth, 0));
    float sceneEye = photon_eye_from_ndcz(texture(SamplerSceneDepth, depthUv).r * 2.0 - 1.0);
    vec3 scene = kilaRelPos * (sceneEye / -kilaViewPos.z);
    kilaSurfaceRel = scene;
    // every derivative before the early return
    vec3 face = cross(dFdx(scene), dFdy(scene));
    vec3 t;
    vec3 b;
    if (!kila_quad_frame(kilaRelPos, t, b)) {
        keep = 0.0;
        return texCoord0;
    }
    vec3 n = cross(t, b);
    float area = length(n);
    float share = max(KilaProjectionShape.y, 1e-3);
    vec3 d = scene - (kilaRelPos + t * (0.5 - texCoord0.x) + b * (0.5 - texCoord0.y));
    vec3 up;
    vec2 uv;
    if (KilaProjectionShape.x > 0.5) {
        up = n / area;
        uv = vec2(dot(d, t) / dot(t, t), dot(d, b) / dot(b, b)) / share + 0.5;
    } else {
        up = vec3(0.0, 1.0, 0.0);
        uv = d.xz / (length(t) * share) + 0.5;
    }
    float h = abs(dot(d, up)) / KilaProjection.x;
    bool inside = all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0))) && h <= 1.0;
    keep = inside ? 1.0 - smoothstep(min(KilaProjection.y, 0.999), 1.0, h) : 0.0;
    float faceArea = length(face);
    if (KilaProjection.z > 0.5 && (faceArea <= 0.0 || abs(dot(face, up)) < 0.9 * faceArea)) keep = 0.0;
    return uv;
}
#endif

#ifdef KILA_UV_EFFECTS
// xy = centre of the twirl and the shear
uniform vec4 KilaTwirl;
// xy = radial shear strength per axis
uniform vec4 KilaShear;
// x = wave frequency, y = wave speed (both radians), z = 1 bends v along u, else u along v
uniform vec4 KilaWave;
// x = ripple amplitude, y = rings per uv unit, z = rings per second (both radians)
uniform vec4 KilaRipple;

vec2 kila_uv_effects(vec2 uv) {
    vec2 d = uv - KilaTwirl.xy;
    float angle = KILA_TWIRL_STRENGTH * length(d);
    float s = sin(angle);
    float c = cos(angle);
    uv = vec2(c * d.x - s * d.y, s * d.x + c * d.y) + KilaTwirl.xy;
    d = uv - KilaTwirl.xy;
    uv += vec2(d.y, -d.x) * dot(d, d) * KilaShear.xy;
    bool alongU = KilaWave.z > 0.5;
    float wave = sin((alongU ? uv.x : uv.y) * KilaWave.x + kila_time() * KilaWave.y) * KILA_WAVE_AMPLITUDE;
    uv = alongU ? vec2(uv.x, uv.y + wave) : vec2(uv.x + wave, uv.y);
    d = uv - KilaTwirl.xy;
    float r = length(d);
    return uv + d / max(r, 1e-4) * sin(r * KilaRipple.y - kila_time() * KilaRipple.z) * KilaRipple.x;
}
#endif

#ifdef KILA_DISTORT
uniform vec4 KilaDistortST;
uniform vec4 KilaDistortAnim;
uniform vec4 KilaDistortPolar;
// x = uv flags, z = targets, w = strength mask (0 none, 1 u, 2 v, 3 radial)
uniform ivec4 KilaDistortOpts;
// xy = amount per axis, z = 1 pushes both ways (rg * 2 - 1)
uniform vec4 KilaDistortParams;
#ifdef KILA_DISTORT_NOISE
uniform vec4 KilaDistortNoise;
#endif

// the uv offset, or with a flow map how far one cycle carries the uv
vec2 kila_flow() {
    int flags = KilaDistortOpts.x;
    vec2 uv = kila_uv(kila_base(flags), KilaDistortST, KilaDistortAnim, KilaDistortPolar, flags, vec2(0.0), vec2(0.0));
#ifdef KILA_DISTORT_NOISE
    vec2 n = kila_noise2(uv, KilaDistortNoise);
#else
    vec2 n = texture(KILA_TEX_DISTORT, uv).rg;
#endif
    if (KilaDistortParams.z > 0.5) n = n * 2.0 - 1.0;
    int mask = KilaDistortOpts.w;
    float m = mask == 1 ? kilaMeshUv.x : mask == 2 ? kilaMeshUv.y
            : mask == 3 ? 1.0 - clamp(length(kilaMeshUv - 0.5) * 2.0, 0.0, 1.0) : 1.0;
    return n * KilaDistortParams.xy * KILA_DISTORT_STRENGTH * m;
}
#endif

#ifdef KILA_MAIN
uniform vec4 KilaMainST;
uniform vec4 KilaMainAnim;
uniform vec4 KilaMainPolar;
// x = uv flags, y = colour mode, z = 1 when the flipbook frame is driven, w = in-cell wrap (0 repeat, 1 clamp, 2 mirror)
uniform ivec4 KilaMainOpts;
#ifdef KILA_FLIPBOOK
// x = columns, y = rows, z = frames per second, w = start frame
uniform vec4 KilaFlipbook;
#ifdef KILA_FLIPBOOK_RANDOM
// x = 1 plays one row picked per particle, y = 1 starts each particle at a frame of its own
uniform ivec4 KilaFlipbookOpts;
#endif
#ifdef KILA_FLIPBOOK_MOTION
// x = motion vector strength
uniform vec4 KilaFlipbookMotion;
#endif

vec2 kila_cell(vec2 uv, float frame, vec2 grid) {
    float f = mod(frame, grid.x * grid.y);
    return (uv + vec2(mod(f, grid.x), floor(f / grid.x))) / grid;
}
#endif
#ifdef KILA_DISPERSION
// xy = the direction the channels split along, z = 1 splits away from the centre instead
uniform vec4 KilaDispersion;
#endif

#ifdef KILA_FLIPBOOK
// 0 repeat, 1 clamp, 2 mirror (KilaTexture.cellWrap)
float kila_cell_wrap(float x, int mode) {
    return mode == 1 ? clamp(x, 0.0, 1.0) : mode == 2 ? 1.0 - abs(mod(x, 2.0) - 1.0) : fract(x);
}
#endif

// The main texture at uv through the flipbook; side = the six-way sheet beside it, read alike.
vec4 kila_main_sample(vec2 uv, out vec4 side) {
    side = vec4(0.0);
#ifdef KILA_FLIPBOOK
    vec2 grid = max(floor(KilaFlipbook.xy), vec2(1.0));
    float span = grid.x * grid.y;
    float first = 0.0;
#ifdef KILA_FLIPBOOK_RANDOM
    if (KilaFlipbookOpts.x == 1) {
        span = grid.x;
        first = floor(fract(KILA_FLIPBOOK_SEED) * grid.y) * grid.x;
    }
#endif
    bool once = KilaMainOpts.z == 1;
    float frame = once ? clamp(KILA_MAIN_FRAME, 0.0, 1.0) * span : kila_time() * KilaFlipbook.z + KilaFlipbook.w;
#ifdef KILA_FLIPBOOK_RANDOM
    if (KilaFlipbookOpts.y == 1) {
        frame += floor(fract(KILA_FLIPBOOK_SEED * 13.7) * span);
        once = false;
    }
#endif
    // a driven sheet plays once: it holds the last frame rather than wrapping to the first
    float now = once ? min(floor(frame), span - 1.0) : floor(frame);
    float next = once ? min(now + 1.0, span - 1.0) : now + 1.0;
    // wrap inside one cell: the sheet's cells sit side by side
    vec2 cell = vec2(kila_cell_wrap(uv.x, KilaMainOpts.w & 3), kila_cell_wrap(uv.y, KilaMainOpts.w >> 2));
    vec2 a = kila_cell(cell, first + mod(now, span), grid);
#ifdef KILA_FLIPBOOK_BLEND
    vec2 b = kila_cell(cell, first + mod(next, span), grid);
    float f = fract(frame);
#ifdef KILA_FLIPBOOK_MOTION
    // both frames pushed along their motion so they meet at f instead of crossfading
    a -= (texture(KILA_TEX_MOTION, a).rg * 2.0 - 1.0) / grid * KilaFlipbookMotion.x * f;
    b += (texture(KILA_TEX_MOTION, b).rg * 2.0 - 1.0) / grid * KilaFlipbookMotion.x * (1.0 - f);
#endif
    vec4 texel = mix(texture(KILA_TEX_MAIN, a), texture(KILA_TEX_MAIN, b), f);
#ifdef KILA_SIXWAY
    side = mix(texture(KILA_TEX_SIXWAY, a), texture(KILA_TEX_SIXWAY, b), f);
#endif
#else
    vec4 texel = texture(KILA_TEX_MAIN, a);
#ifdef KILA_SIXWAY
    side = texture(KILA_TEX_SIXWAY, a);
#endif
#endif
#else
    vec4 texel = texture(KILA_TEX_MAIN, uv);
#ifdef KILA_SIXWAY
    side = texture(KILA_TEX_SIXWAY, uv);
#endif
#endif
    return kila_color_mode(texel, KilaMainOpts.y);
}

vec4 kila_main_fetch(vec2 uv, out vec4 side) {
#ifdef KILA_DISPERSION
    vec2 split = (KilaDispersion.z > 0.5 ? kilaMeshUv - 0.5 : KilaDispersion.xy) * KILA_DISPERSION_AMOUNT;
    vec4 unused;
    vec4 r = kila_main_sample(uv + split, unused);
    vec4 b = kila_main_sample(uv - split, unused);
    vec4 g = kila_main_sample(uv, side);
    // split premultiplied, so a white shape still fringes
    float a = max(max(r.a, g.a), b.a);
    return vec4(vec3(r.r * r.a, g.g * g.a, b.b * b.a) / max(a, 1e-4), a);
#else
    return kila_main_sample(uv, side);
#endif
}

vec4 kila_main(vec2 flow, out vec4 side) {
    int flags = KilaMainOpts.x;
    vec2 uv = kila_uv(kila_base(flags), KilaMainST, KilaMainAnim, KilaMainPolar, flags, KILA_MAIN_OFFSET, vec2(0.0));
#ifdef KILA_FLOWMAP
    vec4 texel;
    if (kilaFlowing) {
        vec4 sideA;
        vec4 sideB;
        texel = mix(kila_main_fetch(uv - flow * kilaFlowPhase.x, sideA), kila_main_fetch(uv - flow * kilaFlowPhase.y, sideB),
                kilaFlowPhase.z);
        side = mix(sideA, sideB, kilaFlowPhase.z);
    } else {
        texel = kila_main_fetch(uv, side);
    }
#else
    uv += flow;
    vec4 texel = kila_main_fetch(uv, side);
#endif
    texel.a *= kila_clip(uv, flags);
    return texel;
}
#endif

#if defined(KILA_LAYER) || defined(KILA_LAYER2)
// a layer's texel over c: mode 0 multiply, 1 add, 2 lerp, 3 screen; affects 1 colour, 2 alpha — ⚠️ MIRRORED FROM
// KilaLayer.Blend
vec4 kila_layer_blend(vec4 c, vec4 layer, int mode, int affects, float strength) {
    vec4 blended;
    if (mode == 0) {
        blended = c * layer;
    } else if (mode == 1) {
        // add and screen keep alpha: an opaque layer must not fill the quad
        blended = vec4(c.rgb + layer.rgb * layer.a, c.a);
    } else if (mode == 2) {
        blended = vec4(mix(c.rgb, layer.rgb, layer.a), max(c.a, layer.a));
    } else {
        blended = vec4(1.0 - (1.0 - c.rgb) * (1.0 - layer.rgb * layer.a), c.a);
    }
    float k = clamp(strength, 0.0, 1.0);
    return vec4((affects & 1) != 0 ? mix(c.rgb, blended.rgb, k) : c.rgb, (affects & 2) != 0 ? mix(c.a, blended.a, k) : c.a);
}
#endif

#ifdef KILA_LAYER
uniform vec4 KilaLayerST;
uniform vec4 KilaLayerAnim;
uniform vec4 KilaLayerPolar;
// x = uv flags, y = colour mode, z = blend (0 multiply, 1 add, 2 lerp, 3 screen), w = affects (1 colour, 2 alpha)
uniform ivec4 KilaLayerOpts;

vec4 kila_layer_sample(vec2 uv) {
    return kila_color_mode(texture(KILA_TEX_LAYER, uv), KilaLayerOpts.y);
}

vec4 kila_layer(vec4 c, vec2 flow) {
    int flags = KilaLayerOpts.x;
    vec2 uv = kila_uv(kila_base(flags), KilaLayerST, KilaLayerAnim, KilaLayerPolar, flags, KILA_LAYER_OFFSET, vec2(0.0));
#ifdef KILA_FLOWMAP
    vec4 layer = kilaFlowing ? mix(kila_layer_sample(uv - flow * kilaFlowPhase.x),
            kila_layer_sample(uv - flow * kilaFlowPhase.y), kilaFlowPhase.z) : kila_layer_sample(uv);
#else
    uv += flow;
    vec4 layer = kila_layer_sample(uv);
#endif
    layer.a *= kila_clip(uv, flags);
    return kila_layer_blend(c, layer, KilaLayerOpts.z, KilaLayerOpts.w, KILA_LAYER_STRENGTH);
}
#endif

#ifdef KILA_LAYER2
uniform vec4 KilaLayer2ST;
uniform vec4 KilaLayer2Anim;
uniform vec4 KilaLayer2Polar;
uniform ivec4 KilaLayer2Opts;

vec4 kila_layer2(vec4 c) {
    int flags = KilaLayer2Opts.x;
    vec2 uv = kila_uv(kila_base(flags), KilaLayer2ST, KilaLayer2Anim, KilaLayer2Polar, flags, vec2(0.0), vec2(0.0));
    vec4 layer = kila_color_mode(texture(KILA_TEX_LAYER2, uv), KilaLayer2Opts.y);
    layer.a *= kila_clip(uv, flags);
    return kila_layer_blend(c, layer, KilaLayer2Opts.z, KilaLayer2Opts.w, KILA_LAYER2_STRENGTH);
}
#endif

#ifdef KILA_RAMP
// x = intensity
uniform vec4 KilaRamp;
// x = input (0 luminance, 1 red, 2 alpha, 3 u, 4 v), y = mode (0 replace rgb, 1 multiply rgb, 2 replace rgba)
uniform ivec4 KilaRampOpts;

vec4 kila_ramp(vec4 c) {
    int source = KilaRampOpts.x;
    float x = source == 0 ? kila_luma(c.rgb) : source == 1 ? c.r : source == 2 ? c.a
            : source == 3 ? kilaMeshUv.x : kilaMeshUv.y;
    vec4 ramp = texture(KILA_TEX_RAMP, vec2(clamp(x + KILA_RAMP_OFFSET, 0.0, 1.0), 0.5));
    ramp.rgb *= KilaRamp.x;
    int mode = KilaRampOpts.y;
    if (mode == 0) return vec4(ramp.rgb, c.a);
    if (mode == 1) return vec4(c.rgb * ramp.rgb, c.a);
    return vec4(ramp.rgb, c.a * ramp.a);
}
#endif

#ifdef KILA_SCREEN_DISTORT
#ifndef KILA_SCREEN_STACK
uniform sampler2D SamplerSceneColor;
#endif
#ifndef KILA_SCREEN_NORMAL
uniform vec4 KilaScreenST;
uniform vec4 KilaScreenAnim;
uniform vec4 KilaScreenPolar;
uniform ivec4 KilaScreenOpts;
#ifdef KILA_SCREEN_NOISE
uniform vec4 KilaScreenNoise;
#endif
#endif
// x = 1 while a scene copy is bound (not in the thumbnail), y = 1 while the scene depth is too, z = colour fringe
uniform vec4 KilaScreenParams;

// how far the scene behind is pushed, in screen uv
vec2 kila_screen_push() {
#ifdef KILA_SCREEN_NORMAL
    vec2 push = kilaNormal.xy * KILA_SCREEN_STRENGTH;
#else
    int flags = KilaScreenOpts.x;
    vec2 uv = kila_uv(kila_base(flags), KilaScreenST, KilaScreenAnim, KilaScreenPolar, flags, vec2(0.0), vec2(0.0));
#ifdef KILA_SCREEN_NOISE
    vec2 n = kila_noise2(uv, KilaScreenNoise);
#else
    vec2 n = texture(KILA_TEX_SCREEN, uv).rg;
#endif
    vec2 push = (n * 2.0 - 1.0) * KILA_SCREEN_STRENGTH;
#endif
    return push;
}

#ifndef KILA_SCREEN_STACK
vec3 kila_screen() {
    vec2 push = kila_screen_push();
    // the capture is window-sized, as gl_FragCoord is: right in the editor's sub-viewport too
    vec2 here = gl_FragCoord.xy / vec2(textureSize(SamplerSceneColor, 0));
#ifdef KILA_SCREEN_DEPTH
    if (KilaScreenParams.y > 0.5) {
        // don't pull in what stands in front of the particle
        float there = photon_eye_from_ndcz(texture(SamplerSceneDepth, here + push).r * 2.0 - 1.0);
        push *= clamp((there + kilaViewPos.z) / 0.25, 0.0, 1.0);
    }
#endif
#ifdef KILA_SCREEN_CHROMA
    float fringe = KilaScreenParams.z;
    return vec3(texture(SamplerSceneColor, here + push * (1.0 + fringe)).r, texture(SamplerSceneColor, here + push).g,
            texture(SamplerSceneColor, here + push * (1.0 - fringe)).b);
#else
    return texture(SamplerSceneColor, here + push).rgb;
#endif
}
#endif
#endif

#ifdef KILA_MASK1
uniform vec4 KilaMask1ST;
uniform vec4 KilaMask1Anim;
uniform vec4 KilaMask1Polar;
uniform ivec4 KilaMask1Opts;
// x = strength, y = invert, z = power, w = also multiplies rgb
uniform vec4 KilaMask1Params;
#ifdef KILA_MASK1_NOISE
uniform vec4 KilaMask1Noise;
#else
uniform vec4 KilaMask1Channel;
#endif

float kila_mask1(vec2 flow) {
#ifdef KILA_MASK1_NOISE
    return kila_noise_scalar(KilaMask1ST, KilaMask1Anim, KilaMask1Polar, KilaMask1Opts, KilaMask1Noise,
            KILA_MASK1_OFFSET, flow);
#else
    return kila_sample_scalar(KILA_TEX_MASK1, KilaMask1ST, KilaMask1Anim, KilaMask1Polar, KilaMask1Opts,
            KilaMask1Channel, KILA_MASK1_OFFSET, flow);
#endif
}
#endif
#ifdef KILA_MASK2
uniform vec4 KilaMask2ST;
uniform vec4 KilaMask2Anim;
uniform vec4 KilaMask2Polar;
uniform ivec4 KilaMask2Opts;
uniform vec4 KilaMask2Params;
#ifdef KILA_MASK2_NOISE
uniform vec4 KilaMask2Noise;
#else
uniform vec4 KilaMask2Channel;
#endif

float kila_mask2(vec2 flow) {
#ifdef KILA_MASK2_NOISE
    return kila_noise_scalar(KilaMask2ST, KilaMask2Anim, KilaMask2Polar, KilaMask2Opts, KilaMask2Noise,
            KILA_MASK2_OFFSET, flow);
#else
    return kila_sample_scalar(KILA_TEX_MASK2, KilaMask2ST, KilaMask2Anim, KilaMask2Polar, KilaMask2Opts,
            KilaMask2Channel, KILA_MASK2_OFFSET, flow);
#endif
}
#endif

float kila_mask_factor(float m, vec4 params) {
    m = pow(clamp(m, 0.0, 1.0), max(params.z, 1e-3));
    if (params.y > 0.5) m = 1.0 - m;
    return mix(1.0, m, clamp(params.x, 0.0, 1.0));
}

#ifdef KILA_DISSOLVE
uniform vec4 KilaDissolveST;
uniform vec4 KilaDissolveAnim;
uniform vec4 KilaDissolvePolar;
// x = uv flags, z = direction (0 none, 1 u, 2 v, 3 radial, 4 texture), w = edge (0 adds, 1 replaces)
uniform ivec4 KilaDissolveOpts;
// x = softness, y = sharpen, z = direction weight, w = 1 reverses the direction
uniform vec4 KilaDissolveParams;
// rgb = HDR edge colour, a = 1 tints the edge with the vertex colour
uniform vec4 KilaDissolveEdgeColor;
#ifdef KILA_DISSOLVE_NOISE
uniform vec4 KilaDissolveNoise;
#else
uniform vec4 KilaDissolveChannel;
#endif
#ifdef KILA_DISSOLVE_DIRTEX
uniform vec4 KilaDissolveDirST;
uniform vec4 KilaDissolveDirChannel;
#endif

vec4 kila_dissolve(vec4 c, vec2 flow, out float noise) {
#ifdef KILA_DISSOLVE_NOISE
    float n = kila_noise_scalar(KilaDissolveST, KilaDissolveAnim, KilaDissolvePolar, KilaDissolveOpts,
            KilaDissolveNoise, KILA_DISSOLVE_OFFSET, flow);
#else
    float n = kila_sample_scalar(KILA_TEX_DISSOLVE, KilaDissolveST, KilaDissolveAnim, KilaDissolvePolar,
            KilaDissolveOpts, KilaDissolveChannel, KILA_DISSOLVE_OFFSET, flow);
#endif
    n = pow(clamp(n, 0.0, 1.0), max(KilaDissolveParams.y, 1e-3));
    int direction = KilaDissolveOpts.z;
    if (direction != 0) {
        float g = direction == 1 ? kilaMeshUv.x : direction == 2 ? kilaMeshUv.y
                : clamp(length(kilaMeshUv - 0.5) * 2.0, 0.0, 1.0);
#ifdef KILA_DISSOLVE_DIRTEX
        if (direction == 4) g = dot(texture(KILA_TEX_DISSOLVE_DIR, kilaMeshUv * KilaDissolveDirST.xy + KilaDissolveDirST.zw),
                KilaDissolveDirChannel);
#endif
        if (KilaDissolveParams.w > 0.5) g = 1.0 - g;
        n = mix(n, clamp(g, 0.0, 1.0), clamp(KilaDissolveParams.z, 0.0, 1.0));
    }
    noise = n;
    // the cut sweeps from -(soft + edge) to 1: progress 0 is whole and 1 is gone at any width
    float soft = max(KilaDissolveParams.x, 1e-4);
    float edgeWidth = max(KILA_DISSOLVE_EDGE, 0.0);
    float cut = mix(-(soft + edgeWidth), 1.0, clamp(KILA_DISSOLVE_PROGRESS, 0.0, 1.0));
    if (edgeWidth > 0.0) {
        float edge = 1.0 - smoothstep(cut + soft, cut + soft + edgeWidth, n);
        vec3 edgeColor = KilaDissolveEdgeColor.rgb;
#ifdef KILA_DISSOLVE_RAMP
        // across the band: left at the burnt side, right at the intact side; its alpha fades the band
        vec4 ramp = textureLod(KILA_TEX_DISSOLVE_RAMP, vec2(clamp((n - cut - soft) / edgeWidth, 0.0, 1.0), 0.5), 0.0);
        edgeColor *= ramp.rgb;
        edge = (n < cut + soft + edgeWidth ? 1.0 : 0.0) * ramp.a;
#endif
        if (KilaDissolveEdgeColor.a > 0.5) edgeColor *= vertexColor.rgb;
        c.rgb = KilaDissolveOpts.w == 1 ? mix(c.rgb, edgeColor, edge) : c.rgb + edgeColor * edge;
    }
    c.a *= smoothstep(cut, cut + soft, n);
    return c;
}
#endif

#ifdef KILA_FRESNEL
// x = power, y = scale, z = mode (0 rim glow, 1 fades the edges, 2 fades the centre)
uniform vec4 KilaFresnel;
uniform vec4 KilaFresnelColor;
// xyz = offset added to the view direction, faking where the light comes from
uniform vec4 KilaFresnelOffset;

vec4 kila_fresnel(vec4 c) {
    vec3 n = kilaNormal;
    vec3 v = normalize(normalize(-kilaViewPos) + KilaFresnelOffset.xyz);
    float f = clamp(KilaFresnel.y * pow(1.0 - abs(dot(n, v)), max(KilaFresnel.x, 1e-3)), 0.0, 1.0);
    float k = clamp(KILA_FRESNEL_STRENGTH, 0.0, 1.0);
    int mode = int(KilaFresnel.z + 0.5);
    if (mode == 0) {
        c.rgb += KilaFresnelColor.rgb * f * k;
    } else if (mode == 1) {
        c.a *= mix(1.0, 1.0 - f, k);
    } else {
        c.a *= mix(1.0, f, k);
    }
    return c;
}
#endif

#ifdef KILA_SHADING
// x = normal (0 mesh, 1 sphere), y = bump (0 none, 1 height, 2 normal map), z = model (0 lambert, 1 six-way),
// w = 1 flips a normal map's green — ⚠️ all MIRRORED FROM KilaShading
uniform ivec4 KilaShadingOpts;
// x = sphere bulge, y = bump strength, z = wrap, w = ambient
uniform vec4 KilaShadingParams;
// xyz = view-space direction towards the key light
uniform vec4 KilaKeyLight;
// rgb = HDR colour of the key light
uniform vec4 KilaKeyColor;
#ifdef KILA_BUMP
uniform vec4 KilaBumpST;
uniform vec4 KilaBumpAnim;
uniform vec4 KilaBumpPolar;
uniform ivec4 KilaBumpOpts;
#ifdef KILA_BUMP_NOISE
uniform vec4 KilaBumpNoise;
#elif defined(KILA_BUMP_HEIGHT)
uniform vec4 KilaBumpChannel;
#endif
#endif

// view space: along u, along v (down the texture), out of the quad towards the camera
vec3 kilaTangent;
vec3 kilaBitangent;
vec3 kilaFace;
#ifdef KILA_SIXWAY
// the six-way sheet here: right, top, back from the main texture; left, bottom, front from the one beside it
vec3 kilaSixPos;
vec3 kilaSixNeg;
#endif

// Takes derivatives: call it at the top of main().
void kila_shading_normal() {
    vec3 t;
    vec3 b;
    bool framed = kila_quad_frame(kilaViewPos, t, b);
    vec3 mesh = normalize(kilaViewNormal);
    if (!gl_FrontFacing) mesh = -mesh;
    kilaTangent = normalize(t);
    kilaBitangent = normalize(b);
    vec3 face = framed ? normalize(cross(t, b)) : mesh;
    if (dot(face, -kilaViewPos) < 0.0) face = -face;
    kilaFace = face;
    vec3 n = mesh;
    if (KilaShadingOpts.x == 1) {
        vec2 p = texCoord0 * 2.0 - 1.0;
        vec3 sphere = normalize(kilaTangent * p.x + kilaBitangent * p.y + face * sqrt(max(1.0 - dot(p, p), 0.0)));
        n = normalize(mix(face, sphere, clamp(KilaShadingParams.x, 0.0, 1.0)));
    }
#ifdef KILA_BUMP
    int flags = KilaBumpOpts.x;
    vec2 uv = kila_uv(kila_base(flags), KilaBumpST, KilaBumpAnim, KilaBumpPolar, flags, vec2(0.0), vec2(0.0));
#ifdef KILA_BUMP_HEIGHT
#ifdef KILA_BUMP_NOISE
    float h = kila_noise(uv, KilaBumpNoise);
#else
    float h = dot(texture(KILA_TEX_BUMP, uv), KilaBumpChannel);
#endif
    // Mikkelsen's derivative bump mapping: no tangents needed
    h *= KilaShadingParams.y * 0.1;
    vec3 sx = dFdx(kilaViewPos);
    vec3 sy = dFdy(kilaViewPos);
    vec3 r1 = cross(sy, n);
    vec3 r2 = cross(n, sx);
    float det = dot(sx, r1);
    n = normalize(abs(det) * n - sign(det) * (dFdx(h) * r1 + dFdy(h) * r2));
#else
    vec3 m = texture(KILA_TEX_BUMP, uv).rgb * 2.0 - 1.0;
    if (KilaShadingOpts.w == 1) m.y = -m.y;
    m.xy *= KilaShadingParams.y;
    // green points up the texture, against v
    n = normalize(kilaTangent * m.x - kilaBitangent * m.y + n * max(m.z, 1e-3));
#endif
#endif
    kilaNormal = n;
}

// l: view space, towards the light
float kila_light_weight(vec3 l) {
#ifdef KILA_SIXWAY
    float x = dot(l, kilaTangent);
    float y = -dot(l, kilaBitangent);
    float z = -dot(l, kilaFace);
    return kilaSixPos.r * max(x, 0.0) + kilaSixNeg.r * max(-x, 0.0) + kilaSixPos.g * max(y, 0.0)
            + kilaSixNeg.g * max(-y, 0.0) + kilaSixPos.b * max(z, 0.0) + kilaSixNeg.b * max(-z, 0.0);
#else
    float wrap = KilaShadingParams.z;
    return clamp((dot(kilaNormal, l) + wrap) / (1.0 + wrap), 0.0, 1.0);
#endif
}

vec3 kila_shade() {
    float ambient = KilaShadingParams.w;
#ifdef KILA_SIXWAY
    // ambient: even light from every side, each lightmap once
    float even = (kilaSixPos.r + kilaSixPos.g + kilaSixPos.b + kilaSixNeg.r + kilaSixNeg.g + kilaSixNeg.b) / 6.0;
    return KilaKeyColor.rgb * kila_light_weight(KilaKeyLight.xyz) + vec3(ambient * even);
#else
    return KilaKeyColor.rgb * mix(ambient, 1.0, kila_light_weight(KilaKeyLight.xyz));
#endif
}

#ifdef KILA_LIT
// The dynamic lights, each weighted by the way it falls on the surface. ⚠️ MIRRORS photon_surface_light in
// photon:lit_particle.glsl apart from that weight, which six-way lighting needs.
vec3 kila_dynamic_light(vec3 viewPos) {
    if (LitParticleParams.x < 0.5) return vec3(0.0);
    ivec2 cluster = photon_cluster(ivec2(gl_FragCoord.xy), -viewPos.z);
    vec3 sum = vec3(0.0);
    for (int k = 0; k < cluster.y; k++) {
        int light = photon_clusterLight(cluster.x + k);
        vec4 t0, t1, t2, t3;
        photon_fetchLight(light, t0, t1, t2, t3);
        vec3 toLight = t0.xyz - viewPos;
        float d2 = dot(toLight, toLight);
        float attenuation = photon_attenuation(d2, t0.w);
        if (attenuation <= 0.0) continue;
        vec3 l = toLight * inversesqrt(max(d2, 1e-6));
        attenuation *= photon_spot(l, t1, t2, t3);
        if (attenuation <= 0.0) continue;
        attenuation *= photon_visibility(t3, -toLight, sqrt(d2)) * kila_light_weight(l);
        sum += t1.rgb * attenuation;
    }
    float peak = max(sum.r, max(sum.g, sum.b));
    if (peak <= 0.0) return vec3(0.0);
    return sum * (LitParticleParams.y * (1.0 - exp(-peak)) / peak);
}
#endif
#endif

#ifdef KILA_MATCAP
// x = strength, y = blend (⚠️ MIRRORED FROM KilaMatcap.Blend: 0 add, 1 multiply, 2 replace)
uniform vec4 KilaMatcap;
#endif

#ifdef KILA_VOLUME
// x = the radius as a share of the quad's half width, y = density, z = power, w = 1 while the scene depth clips it
uniform vec4 KilaVolume;

// Inigo Quilez's sphere density of a ball round the particle along the view ray, cut short by the scene.
// Takes derivatives: call it at the top of main().
float kila_volume() {
    vec3 t;
    vec3 b;
    if (!kila_quad_frame(kilaViewPos, t, b)) return 0.0;
    vec3 centre = kilaViewPos + t * (0.5 - texCoord0.x) + b * (0.5 - texCoord0.y);
    float radius = 0.5 * length(t) * KilaVolume.x;
    vec3 rd = normalize(kilaViewPos);
    float far = 1e6;
#ifdef KILA_VOLUME_CLIP
    if (KilaVolume.w > 0.5) {
        vec2 depthUv = gl_FragCoord.xy / vec2(textureSize(SamplerSceneDepth, 0));
        float sceneEye = photon_eye_from_ndcz(texture(SamplerSceneDepth, depthUv).r * 2.0 - 1.0);
        far = sceneEye / max(-rd.z, 1e-4);
    }
#endif
    // in units of the radius, from the camera
    vec3 rc = -centre / radius;
    float bb = dot(rd, rc);
    float cc = dot(rc, rc) - 1.0;
    float h = bb * bb - cc;
    if (h < 0.0) return 0.0;
    h = sqrt(h);
    float t1 = max(-bb - h, 0.0);
    float t2 = min(-bb + h, far / radius);
    if (t2 <= t1) return 0.0;
    float i1 = -(cc * t1 + bb * t1 * t1 + t1 * t1 * t1 / 3.0);
    float i2 = -(cc * t2 + bb * t2 * t2 + t2 * t2 * t2 / 3.0);
    return pow(clamp((i2 - i1) * 0.75 * KilaVolume.y, 0.0, 1.0), max(KilaVolume.z, 1e-3));
}
#endif

#ifdef KILA_COLOR_ADJUST
// x = hue shift (radians), y = saturation, z = contrast, w = brightness
uniform vec4 KilaColorAdjust;
// x = levels per channel (below 2 = off)
uniform vec4 KilaPosterize;

vec3 kila_color_adjust(vec3 c) {
    // hue: a rotation about the grey axis
    const vec3 grey = vec3(0.57735027);
    float cosine = cos(KilaColorAdjust.x);
    c = c * cosine + cross(grey, c) * sin(KilaColorAdjust.x) + grey * dot(grey, c) * (1.0 - cosine);
    c = mix(vec3(kila_luma(c)), c, KilaColorAdjust.y);
    c = (c - 0.5) * KilaColorAdjust.z + 0.5;
    c *= KilaColorAdjust.w;
    if (KilaPosterize.x >= 2.0) c = floor(c * (KilaPosterize.x - 1.0) + 0.5) / (KilaPosterize.x - 1.0);
    return max(c, vec3(0.0));
}
#endif

#ifdef KILA_PIXELATE
// x = pixels across one uv unit
uniform vec4 KilaPixelate;
#endif

#ifdef KILA_DEPTH
// x = 1 fades softly into the scene, y = measure, z = contact mode (⚠️ both MIRRORED FROM KilaDepth),
// w = 1 cuts the glow by the shape's alpha
uniform ivec4 KilaDepthOpts;
// rgb = HDR colour where the particle touches the scene
uniform vec4 KilaContactColor;
// x = falloff power, y = hardness, z = breakup, w = pulses across the band (0 = none)
uniform vec4 KilaContactShape;
// x = pulses per second
uniform vec4 KilaContactPulse;
#ifdef KILA_CONTACT_BREAKUP
uniform vec4 KilaContactST;
uniform vec4 KilaContactAnim;
uniform vec4 KilaContactPolar;
uniform ivec4 KilaContactOpts;
#ifdef KILA_CONTACT_NOISE
uniform vec4 KilaContactNoise;
#else
uniform vec4 KilaContactChannel;
#endif

float kila_contact_breakup() {
#ifdef KILA_CONTACT_NOISE
    return kila_noise_scalar(KilaContactST, KilaContactAnim, KilaContactPolar, KilaContactOpts, KilaContactNoise,
            vec2(0.0), vec2(0.0));
#else
    return kila_sample_scalar(KILA_TEX_CONTACT, KilaContactST, KilaContactAnim, KilaContactPolar, KilaContactOpts,
            KilaContactChannel, vec2(0.0), vec2(0.0));
#endif
}
#endif

// Distance to the scene behind: along the view ray, or square to the face there when it is flat.
// Takes derivatives: call it in uniform control flow.
float kila_contact_distance(float gap, float fragEye) {
#ifdef KILA_REL_POS
    if (KilaDepthOpts.y == 1) {
        vec3 toScene = kilaRelPos * (gap / fragEye);
        vec3 scene = kilaRelPos + toScene;
        vec3 dx = dFdx(scene);
        vec3 dy = dFdy(scene);
        vec3 a = abs(cross(dx, dy));
        vec3 axis = a.x > a.y && a.x > a.z ? vec3(1.0, 0.0, 0.0) : a.y > a.z ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
        // an axis-aligned flat face; across a silhouette the neighbours are not on one plane
        if (abs(dot(dx, axis)) <= 0.1 * length(dx) && abs(dot(dy, axis)) <= 0.1 * length(dy)) {
            return abs(dot(toScene, axis));
        }
    }
#endif
    return gap;
}

float kila_contact_glow(float d, float width, float breakup, out vec3 color) {
    float u = clamp(d / width, 0.0, 1.0);
    float glow = clamp(pow(1.0 - u, max(KilaContactShape.x, 1e-3)) / max(1.0 - KilaContactShape.y, 1e-3), 0.0, 1.0);
    if (KilaContactShape.w >= 1.0) {
        float p = fract(u * KilaContactShape.w - kila_time() * KilaContactPulse.x);
        glow *= pow(1.0 - abs(2.0 * p - 1.0), 3.0);
    }
    float cut = breakup * KilaContactShape.z;
    if (cut > 0.0) glow *= smoothstep(cut - 0.05, cut + 0.05, glow);
    color = KilaContactColor.rgb;
#ifdef KILA_CONTACT_RAMP
    vec4 ramp = textureLod(KILA_TEX_CONTACT_RAMP, vec2(u, 0.5), 0.0);
    color *= ramp.rgb;
    glow *= ramp.a;
#endif
    return glow * clamp(KILA_CONTACT_STRENGTH, 0.0, 1.0);
}

// the glow as a layer of its own over the particle, so a faint particle does not dim it twice
vec4 kila_contact(vec4 c, float glow, vec3 color) {
    vec3 under = c.rgb * c.a;
    vec3 over = KilaDepthOpts.z == 1 ? color * glow + under * (1.0 - glow) : under + color * glow;
    c.a = glow + c.a * (1.0 - glow);
    c.rgb = over / max(c.a, 1e-4);
    return c;
}
#endif

#ifdef KILA_CAMERA_FADE
// x = distance where the fade starts, y = its length
uniform vec4 KilaCameraFade;
#endif

void main() {
    kilaMeshUv = texCoord0;
#ifdef KILA_PROJECTION
    float projectionKeep;
    kilaMeshUv = kila_project(projectionKeep);
#endif
#ifdef KILA_TIME_STEP
    // boil: a random uv jump every step
    float tick = floor(GameTime * 1200.0 * KilaTimeStep.x);
    kilaMeshUv += (fract(sin(vec2(tick * 12.9898, tick * 78.233)) * 43758.5453) - 0.5) * KilaTimeStep.y;
#endif
#ifdef KILA_UV_EFFECTS
    kilaMeshUv = kila_uv_effects(kilaMeshUv);
#endif
#ifdef KILA_PIXELATE
    float pixels = max(KilaPixelate.x, 1.0);
    kilaMeshUv = (floor(kilaMeshUv * pixels) + 0.5) / pixels;
#endif
    kilaScreenUv = gl_FragCoord.xy / max(ScreenSize, vec2(1.0));
#ifdef KILA_WORLD_UV
#ifdef KILA_PROJECTION
    kilaWorldPos = kilaSurfaceRel + KilaCameraPos;
#else
    kilaWorldPos = kilaRelPos + KilaCameraPos;
#endif
#else
    kilaWorldPos = vec3(0.0);
#endif
#ifdef KILA_FLOWMAP
    float phase = fract(kila_time() * KilaFlowMap.x);
    kilaFlowPhase = vec3(phase, fract(phase + 0.5), abs(1.0 - 2.0 * phase));
#endif
    kilaNormal = normalize(kilaViewNormal);
#ifdef KILA_SHADING
    kila_shading_normal();
#endif
#ifdef KILA_VOLUME
    float volume = kila_volume();
#endif
#ifdef KILA_DEPTH
    // before any discard: the surface measure takes derivatives
    bool depthBound = SoftParticleParams.w > 0.5;
    // not gl_FragCoord.z: the camera offset moves the depth the quad is drawn at, not where it is
    float fragEye = -kilaViewPos.z;
    // far out the depth buffer cannot resolve a thin band: widen it rather than let it flicker
    float resolvable = 3.6e-6 * fragEye * fragEye;
    float sceneGap = 0.0;
    float contactGlow = 0.0;
    vec3 contactColor = vec3(0.0);
    if (depthBound) {
        vec2 depthUv = gl_FragCoord.xy / vec2(textureSize(SamplerSceneDepth, 0));
        sceneGap = photon_eye_from_ndcz(texture(SamplerSceneDepth, depthUv).r * 2.0 - 1.0) - fragEye;
        float contactDistance = kila_contact_distance(sceneGap, fragEye);
        float contactBreakup = 0.0;
#ifdef KILA_CONTACT_BREAKUP
        contactBreakup = kila_contact_breakup();
#endif
        float width = KILA_CONTACT_WIDTH;
        // below 0 the fragment is behind the scene (no depth test): it touches nothing
        if (width > 0.0 && sceneGap >= 0.0) {
            contactGlow = kila_contact_glow(contactDistance, max(width, resolvable), contactBreakup, contactColor);
        }
    }
#endif

    vec4 debugMain = vec4(0.0);
    float debugMask = 1.0;
    float debugNoise = 0.0;
    vec2 debugFlow = vec2(0.0);

#ifdef KILA_DISTORT
    vec2 flow = kila_flow();
    debugFlow = flow;
    int targets = KilaDistortOpts.z;
#ifdef KILA_FLOWMAP
#define KILA_FLOW_FOR(bit) ((kilaFlowing = (targets & (bit)) != 0) ? flow : vec2(0.0))
#else
#define KILA_FLOW_FOR(bit) ((targets & (bit)) != 0 ? flow : vec2(0.0))
#endif
#else
#define KILA_FLOW_FOR(bit) vec2(0.0)
#endif

#ifdef KILA_MAIN
    vec4 sixNeg;
    vec4 c = kila_main(KILA_FLOW_FOR(KILA_FLOW_MAIN), sixNeg);
    debugMain = c;
#ifdef KILA_SIXWAY
    // the sheet holds light, not colour
    kilaSixPos = c.rgb;
    kilaSixNeg = sixNeg.rgb;
    c.rgb = vec3(1.0);
#endif
#else
    vec4 c = vec4(1.0);
#ifdef KILA_SIXWAY
    kilaSixPos = vec3(1.0);
    kilaSixNeg = texture(KILA_TEX_SIXWAY, kilaMeshUv).rgb;
#endif
#endif
#ifdef KILA_LAYER
    c = kila_layer(c, KILA_FLOW_FOR(KILA_FLOW_LAYER));
#endif
#ifdef KILA_LAYER2
    c = kila_layer2(c);
#endif
#ifdef KILA_RAMP
    c = kila_ramp(c);
#endif

    c *= KilaTint * ColorModulator;
    c.rgb *= gl_FrontFacing ? vec3(1.0) : KilaBackTint.rgb;
    int vertexMode = KilaSurfaceModes.x;
    if (vertexMode == 0) {
        c *= vertexColor;
    } else if (vertexMode == 1) {
        c.a *= vertexColor.a;
    } else if (vertexMode == 3) {
        c.rgb *= vertexColor.rgb;
    }

#ifdef KILA_SCREEN_DISTORT
    // the scene behind is lit and fogged already
    bool sceneShown = KilaScreenParams.x > 0.5;
#else
    bool sceneShown = false;
#endif
    // light falls on the surface colour only: emission, edges and rims added below glow in the dark
    if (!sceneShown) {
        vec3 surface = c.rgb;
        c.rgb *= mix(vec3(1.0), lightColor.rgb, KilaSurface.z);
#ifdef KILA_SHADING
        c.rgb *= kila_shade();
#endif
#ifdef KILA_LIT
#ifdef KILA_SHADING
        c.rgb += surface * kila_dynamic_light(kilaViewPos);
#else
        c.rgb += surface * photon_particle_light(kilaViewPos);
#endif
#endif
    }
#ifdef KILA_SCREEN_STACK
    vec2 stackPush = kila_screen_push();
#elif defined(KILA_SCREEN_DISTORT)
    if (sceneShown) c.rgb *= kila_screen();
#endif
    c.rgb = c.rgb * KILA_INTENSITY + KilaEmission.rgb;
#ifdef KILA_MATCAP
    // v runs down the texture, so the normal's y is flipped
    vec3 cap = texture(KILA_TEX_MATCAP, vec2(kilaNormal.x, -kilaNormal.y) * 0.5 + 0.5).rgb;
    int capBlend = int(KilaMatcap.y + 0.5);
    c.rgb = capBlend == 0 ? c.rgb + cap * KilaMatcap.x : capBlend == 1 ? c.rgb * mix(vec3(1.0), cap, KilaMatcap.x)
            : mix(c.rgb, cap, clamp(KilaMatcap.x, 0.0, 1.0));
#endif

#ifdef KILA_MASK1
    float mask1 = kila_mask_factor(kila_mask1(KILA_FLOW_FOR(KILA_FLOW_MASK1)), KilaMask1Params);
    c.a *= mask1;
    if (KilaMask1Params.w > 0.5) c.rgb *= mask1;
    debugMask *= mask1;
#endif
#ifdef KILA_MASK2
    float mask2 = kila_mask_factor(kila_mask2(KILA_FLOW_FOR(KILA_FLOW_MASK2)), KilaMask2Params);
    c.a *= mask2;
    if (KilaMask2Params.w > 0.5) c.rgb *= mask2;
    debugMask *= mask2;
#endif
#ifdef KILA_DISSOLVE
    c = kila_dissolve(c, KILA_FLOW_FOR(KILA_FLOW_DISSOLVE), debugNoise);
#endif
#ifdef KILA_FRESNEL
    c = kila_fresnel(c);
#endif
#ifdef KILA_COLOR_ADJUST
    c.rgb = kila_color_adjust(c.rgb);
#endif

    c.a = clamp(pow(clamp(c.a, 0.0, 1.0), max(KilaSurface.y, 1e-3)) * KILA_ALPHA, 0.0, 1.0);
#ifdef KILA_VOLUME
    c.a *= volume;
#endif
#ifdef KILA_PROJECTION
    c.a *= projectionKeep;
#endif

    // ⚠️ views MIRRORED FROM KilaMaterial.DebugView — before the discard, so what is clipped still shows
    int view = KilaDebug.x;
    if (view != 0) {
        vec3 shown = view == 1 ? debugMain.rgb * debugMain.a : view == 2 ? vec3(c.a) : view == 3 ? vec3(debugMask)
                : view == 4 ? vec3(debugNoise) : view == 5 ? vec3(debugFlow * 5.0 + 0.5, 0.5)
                : view == 6 ? kilaNormal * 0.5 + 0.5 : vec3(fract(kilaMeshUv), 0.0);
        fragColor = vec4(shown, 1.0);
        return;
    }

    float kept = c.a;
#ifdef KILA_DEPTH
    // a glow that ignores the shape keeps what the shape alone would clip
    if (KilaDepthOpts.w == 0) kept = max(kept, contactGlow);
#endif
    if (kept < KilaSurface.x) {
        discard;
    }

    if (KilaSurface.w > 0.5 && !sceneShown) {
        c = linear_fog(c, vertexDistance, FogStart, FogEnd, FogColor);
    }

    // ⚠️ fades act on alpha after the fog: fogging an already faded fragment would bring FogColor back
#ifdef KILA_DEPTH
    if (depthBound) {
        float shape = c.a;
        if (KilaDepthOpts.x == 1) {
            c.a *= pow(clamp(sceneGap / max(SoftParticleParams.x, resolvable), 0.0, 1.0), max(SoftParticleParams.y, 1e-5));
        }
        if (contactGlow > 0.0) c = kila_contact(c, KilaDepthOpts.w == 1 ? contactGlow * shape : contactGlow, contactColor);
    }
#endif
#ifdef KILA_CAMERA_FADE
    c.a *= clamp((length(kilaViewPos) - KilaCameraFade.x) / max(KilaCameraFade.y, 1e-4), 0.0, 1.0);
#endif

#ifdef KILA_SCREEN_STACK
    // straight coverage, before a preset folds it into the colour
    float stackCoverage = clamp(c.a, 0.0, 1.0);
#endif
    int preset = KilaSurfaceModes.y;
    if (preset == 2) {
        c = vec4(c.rgb * c.a, c.a * (1.0 - clamp(KilaOutput.x, 0.0, 1.0)));
    } else if (preset == 3) {
        c.rgb = mix(vec3(1.0), c.rgb, c.a);
    } else if (preset == 4) {
        c.a = 1.0;
    }
#ifdef KILA_SCREEN_STACK
    // rg = push by coverage, a = eye depth, b = 1 - what the preset's blend leaves of the bent scene:
    // src * tint + dst, with the bent scene standing in for both (negative brightens)
    float stackSrc = preset <= 1 ? c.a : 1.0;
    float stackDst = preset == 1 ? 1.0 : preset == 0 || preset == 2 ? 1.0 - c.a : 0.0;
    fragColor = vec4(stackPush * stackCoverage, 1.0 - stackSrc * kila_luma(max(c.rgb, 0.0)) - stackDst, -kilaViewPos.z);
#else
    fragColor = c;
#endif
}
