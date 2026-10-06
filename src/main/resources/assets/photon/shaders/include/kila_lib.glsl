// Shared helpers of photon:kila. Polar, rotation and noise follow Unity's (and KilaGraph's) node formulas, so a
// ported material keeps its numbers.

uniform float GameTime;

// Modules sample through the KILA_TEX_<SLOT> aliases KilaLayout defines to one of these.
#ifdef KILA_TEX0
uniform sampler2D KilaTex0;
#endif
#ifdef KILA_TEX1
uniform sampler2D KilaTex1;
#endif
#ifdef KILA_TEX2
uniform sampler2D KilaTex2;
#endif
#ifdef KILA_TEX3
uniform sampler2D KilaTex3;
#endif
#ifdef KILA_TEX4
uniform sampler2D KilaTex4;
#endif
#ifdef KILA_TEX5
uniform sampler2D KilaTex5;
#endif
#ifdef KILA_TEX6
uniform sampler2D KilaTex6;
#endif
#ifdef KILA_TEX7
uniform sampler2D KilaTex7;
#endif
#ifdef KILA_TEX8
uniform sampler2D KilaTex8;
#endif

#ifdef KILA_TIME_STEP
// x = steps per second, y = how far the uv jumps each step
uniform vec4 KilaTimeStep;
#endif

// seconds: Photon drives GameTime from the particle clock
float kila_time() {
#ifdef KILA_TIME_STEP
    return floor(GameTime * 1200.0 * KilaTimeStep.x) / KilaTimeStep.x;
#else
    return GameTime * 1200.0;
#endif
}

vec2 kila_rotate(vec2 uv, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    vec2 d = uv - 0.5;
    return vec2(c * d.x - s * d.y, s * d.x + c * d.y) + 0.5;
}

// polar: x = center u, y = center v, z = radial scale, w = angular repeats
vec2 kila_polar(vec2 uv, vec4 polar) {
    vec2 d = uv - polar.xy;
    return vec2(length(d) * 2.0 * polar.z, atan(d.x, d.y) * 0.15915494 * polar.w);
}

// ⚠️ flags MIRRORED FROM KilaTexture.flags()
#define KILA_UV_POLAR 1
#define KILA_UV_SCREEN 2
#define KILA_UV_CLIP_U 4
#define KILA_UV_CLIP_V 8
#define KILA_UV_WORLD_XZ 16
#define KILA_UV_WORLD_XY 32
#define KILA_UV_WORLD_ZY 64

vec2 kila_base_uv(int flags, vec2 meshUv, vec2 screenUv, vec3 world) {
    if ((flags & KILA_UV_SCREEN) != 0) return screenUv;
    if ((flags & KILA_UV_WORLD_XZ) != 0) return world.xz;
    if ((flags & KILA_UV_WORLD_XY) != 0) return world.xy;
    if ((flags & KILA_UV_WORLD_ZY) != 0) return world.zy;
    return meshUv;
}

// The chain every texture slot runs: polar -> rotate -> tiling/offset -> scroll -> driven offset -> flow.
// st = (tiling.xy, offset.xy), anim = (scroll.xy per second, rotation, rotation per second) in radians.
vec2 kila_uv(vec2 uv, vec4 st, vec4 anim, vec4 polar, int flags, vec2 offset, vec2 flow) {
    if ((flags & KILA_UV_POLAR) != 0) uv = kila_polar(uv, polar);
    float angle = anim.z + anim.w * kila_time();
    if (angle != 0.0) uv = kila_rotate(uv, angle);
    return uv * st.xy + st.zw + anim.xy * kila_time() + offset + flow;
}

float kila_clip(vec2 uv, int flags) {
    float keep = 1.0;
    if ((flags & KILA_UV_CLIP_U) != 0 && (uv.x < 0.0 || uv.x > 1.0)) keep = 0.0;
    if ((flags & KILA_UV_CLIP_V) != 0 && (uv.y < 0.0 || uv.y > 1.0)) keep = 0.0;
    return keep;
}

float kila_luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// colour slots — ⚠️ MIRRORED FROM KilaTexture.ColorMode
vec4 kila_color_mode(vec4 t, int mode) {
    if (mode == 1) return vec4(t.rgb, t.r);
    if (mode == 2) return vec4(1.0, 1.0, 1.0, t.a);
    if (mode == 3) return vec4(t.rgb, kila_luma(t.rgb));
    return t;
}

// Unity's Simple / Gradient Noise / Voronoi. noise: x = type (⚠️ MIRRORED FROM KilaTexture.Noise: 1 simple,
// 2 gradient, 3 voronoi), y = scale, z = voronoi cell motion per second.
float kila_value_noise(vec2 uv) {
    vec2 i = floor(uv);
    vec2 f = fract(uv);
    f = f * f * (3.0 - 2.0 * f);
    float r0 = fract(sin(dot(i, vec2(12.9898, 78.233))) * 43758.5453);
    float r1 = fract(sin(dot(i + vec2(1.0, 0.0), vec2(12.9898, 78.233))) * 43758.5453);
    float r2 = fract(sin(dot(i + vec2(0.0, 1.0), vec2(12.9898, 78.233))) * 43758.5453);
    float r3 = fract(sin(dot(i + vec2(1.0, 1.0), vec2(12.9898, 78.233))) * 43758.5453);
    return mix(mix(r0, r1, f.x), mix(r2, r3, f.x), f.y);
}

vec2 kila_gradient_dir(vec2 p) {
    p = mod(p, 289.0);
    float x = mod((34.0 * p.x + 1.0) * p.x, 289.0) + p.y;
    x = mod((34.0 * x + 1.0) * x, 289.0);
    x = fract(x / 41.0) * 2.0 - 1.0;
    return normalize(vec2(x - floor(x + 0.5), abs(x) - 0.5));
}

float kila_gradient_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float d00 = dot(kila_gradient_dir(i), f);
    float d01 = dot(kila_gradient_dir(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
    float d10 = dot(kila_gradient_dir(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
    float d11 = dot(kila_gradient_dir(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));
    vec2 w = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(d00, d10, w.x), mix(d01, d11, w.x), w.y) + 0.5;
}

vec2 kila_voronoi_random(vec2 uv, float offset) {
    mat2 m = mat2(15.27, 47.63, 99.41, 89.98);
    uv = fract(sin(m * uv) * 46839.32);
    return vec2(sin(uv.y * offset) * 0.5 + 0.5, cos(uv.x * offset) * 0.5 + 0.5);
}

float kila_voronoi(vec2 uv, float angleOffset, float density) {
    vec2 g = floor(uv * density);
    vec2 f = fract(uv * density);
    float best = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 lattice = vec2(float(x), float(y));
            best = min(best, distance(lattice + kila_voronoi_random(lattice + g, angleOffset), f));
        }
    }
    return best;
}

float kila_noise(vec2 uv, vec4 noise) {
    int type = int(noise.x + 0.5);
    if (type == 1) {
        vec2 p = uv * noise.y;
        return kila_value_noise(p) * 0.125 + kila_value_noise(p / 2.0) * 0.25 + kila_value_noise(p / 4.0) * 0.5;
    }
    if (type == 2) return kila_gradient_noise(uv * noise.y);
    return kila_voronoi(uv, 2.0 + kila_time() * noise.z, noise.y);
}

// two decorrelated noises
vec2 kila_noise2(vec2 uv, vec4 noise) {
    return vec2(kila_noise(uv, noise), kila_noise(uv + vec2(17.31, 5.27), noise));
}
