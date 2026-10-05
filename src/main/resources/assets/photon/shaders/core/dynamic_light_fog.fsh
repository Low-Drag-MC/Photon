#version 150

// Fog volumes, nearest first, marched at this pixel's stratum of the 4x4 tile and lit by the lights of each sample's
// cluster; the blur integrates the tile. Blended into the volume buffer: rgb adds to the haze, alpha multiplies into
// how much of the scene behind still shows.

#moj_import <photon:dynamic_light.glsl>
#moj_import <photon:dynamic_light_depth.glsl>

// eight texels per fog volume: view to local space (three rows) | shape (0 box, 1 sphere), density per block, edge
// falloff, absorption | colour, noise | emission, noise scale | noise offset in blocks along the local axes | size of
// a local unit in blocks
uniform sampler2D FogData;
uniform ivec4 FogInfo;         // fog volumes, steps through each
uniform float ForwardScattering;

out vec4 fragColor;

// the t range of the ray inside the unit box or sphere, given in the volume's local space
vec2 shapeRange(vec3 lo, vec3 ld, bool sphere) {
    if (sphere) {
        float a = dot(ld, ld);
        float b = dot(lo, ld);
        float c = dot(lo, lo) - 0.25;
        float disc = b * b - a * c;
        if (disc < 0.0) return vec2(1.0, 0.0);
        float s = sqrt(disc);
        return vec2((-b - s) / a, (-b + s) / a);
    }
    vec3 inv = 1.0 / vec3(abs(ld.x) < 1e-8 ? 1e-8 : ld.x, abs(ld.y) < 1e-8 ? 1e-8 : ld.y, abs(ld.z) < 1e-8 ? 1e-8 : ld.z);
    vec3 a = (-0.5 - lo) * inv;
    vec3 b = (0.5 - lo) * inv;
    vec3 near = min(a, b);
    vec3 far = max(a, b);
    return vec2(max(near.x, max(near.y, near.z)), min(far.x, min(far.y, far.z)));
}

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float valueNoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

// light arriving at p and scattered toward the camera along -d, from every light of p's cluster
vec3 inscatter(vec3 p, vec3 d, ivec2 pixel, float g) {
    ivec2 cluster = photon_cluster(pixel, -p.z);
    vec3 sum = vec3(0.0);
    for (int k = 0; k < cluster.y; k++) {
        int light = photon_clusterLight(cluster.x + k);
        vec4 t0, t1, t2, t3;
        photon_fetchLight(light, t0, t1, t2, t3);
        vec3 toLight = t0.xyz - p;
        float d2 = dot(toLight, toLight);
        float attenuation = photon_attenuation(d2, t0.w);
        if (attenuation <= 0.0) continue;
        vec3 l = toLight * inversesqrt(max(d2, 1e-6));
        attenuation *= photon_spot(l, t1, t2, t3);
        if (attenuation <= 0.0) continue;
        attenuation *= photon_visibility(t3, -toLight, sqrt(d2));
        sum += t1.rgb * (attenuation * photon_phase(dot(l, d), g));
    }
    return sum;
}

void main() {
    ivec2 pixel = scenePixel(ivec2(gl_FragCoord.xy));
    vec3 o, d;
    float rayEnd;
    viewRay(pixel, texelFetch(SceneDepthSampler, pixel, 0).r, o, d, rayEnd);
    float stratum = tileStratum();
    float g = ForwardScattering;

    vec3 sum = vec3(0.0);
    float transmittance = 1.0;
    for (int f = 0; f < FogInfo.x && transmittance > 1e-3; f++) {
        vec4 f0 = texelFetch(FogData, ivec2(f * 8, 0), 0);
        vec4 f1 = texelFetch(FogData, ivec2(f * 8 + 1, 0), 0);
        vec4 f2 = texelFetch(FogData, ivec2(f * 8 + 2, 0), 0);
        vec4 f3 = texelFetch(FogData, ivec2(f * 8 + 3, 0), 0);
        vec3 lo = vec3(dot(f0, vec4(o, 1.0)), dot(f1, vec4(o, 1.0)), dot(f2, vec4(o, 1.0)));
        vec3 ld = vec3(dot(f0.xyz, d), dot(f1.xyz, d), dot(f2.xyz, d));
        bool sphere = f3.x > 0.5;
        vec2 range = shapeRange(lo, ld, sphere);
        float t0 = max(range.x, 0.0);
        float t1 = min(range.y, rayEnd);
        if (t1 <= t0) continue;
        vec4 f4 = texelFetch(FogData, ivec2(f * 8 + 4, 0), 0);
        vec4 f5 = texelFetch(FogData, ivec2(f * 8 + 5, 0), 0);
        vec4 f6 = texelFetch(FogData, ivec2(f * 8 + 6, 0), 0);
        vec4 f7 = texelFetch(FogData, ivec2(f * 8 + 7, 0), 0);
        int steps = max(FogInfo.y, 1);
        float dt = (t1 - t0) / float(steps);
        for (int i = 0; i < steps && transmittance > 1e-3; i++) {
            float t = t0 + (float(i) + stratum) * dt;
            vec3 local = lo + t * ld;
            // 1 deep inside, 0 at the surface
            float inside = sphere ? 1.0 - 2.0 * length(local) : 1.0 - 2.0 * max(abs(local.x), max(abs(local.y), abs(local.z)));
            float density = f3.y * smoothstep(0.0, max(f3.z, 1e-3), inside);
            if (f4.w > 0.0) {
                vec3 q = (local * f7.xyz + f6.xyz) / f5.w;
                float n = 0.65 * valueNoise(q) + 0.35 * valueNoise(q * 2.03 + 17.0);
                density *= clamp(1.0 + f4.w * (2.0 * n - 1.0), 0.0, 2.0);
            }
            if (density <= 0.0) continue;
            vec3 p = o + t * d;
            vec3 light = f4.rgb * inscatter(p, d, pixel, g) + f5.rgb;
            // the transmittance integrated over the step, so coarse steps through dense fog don't overshoot
            float extinction = density * f3.w;
            float through = exp(-extinction * dt);
            sum += transmittance * density * (extinction > 1e-5 ? (1.0 - through) / extinction : dt) * light;
            transmittance *= through;
        }
    }
    // half of RGBA16F's 65504: this adds onto the volume pass, and Inf would turn to NaN in the blur
    fragColor = vec4(min(sum, vec3(3e4)), transmittance);
}
