#version 150

// Single scattering from the volumetric lights along each view ray, up to the scene. Per light the ray is clipped to
// its range and cone, split at the closest approach and cut into pieces of equal angle from the light: the falloff
// 1/(r^2+1) - 1/(range^2+1) integrates exactly per piece; phase, cone and visibility are sampled once per piece, at
// its middle, or for shadowed lights at this pixel's stratum of the 4x4 tile, which the blur integrates.

#moj_import <photon:dynamic_light.glsl>
#moj_import <photon:dynamic_light_depth.glsl>

// three texels per light: view position, range | scattered colour, cos inner (< -1.5: point) | spot axis, cos outer
uniform sampler2D VolumeLightData;
uniform sampler2D VolumeTileGrid;
uniform sampler2D VolumeTileIndices;
uniform ivec4 VolumeTileInfo;  // tile size, tiles x, tiles y
uniform vec4 VolumeParams;     // forward scattering, pieces on each side of the closest approach, shadowed lights

out vec4 fragColor;

// the t range of o + t d inside the forward nappe of the cone at apex p
vec2 coneRange(vec3 o, vec3 d, vec3 p, vec3 axis, float cosOuter) {
    vec3 w = o - p;
    float dA = dot(d, axis);
    float wA = dot(w, axis);
    float c2 = cosOuter * cosOuter;
    float a2 = dA * dA - c2;
    float a1 = dA * wA - c2 * dot(d, w);
    float a0 = wA * wA - c2 * dot(w, w);
    if (abs(a2) < 1e-6) a2 = a2 >= 0.0 ? 1e-6 : -1e-6;
    float disc = a1 * a1 - a2 * a0;
    float lo = -1e9;
    float hi = 1e9;
    if (disc < 0.0) {
        if (a2 < 0.0) return vec2(1.0, 0.0);
    } else {
        float q = -(a1 + (a1 >= 0.0 ? sqrt(disc) : -sqrt(disc)));
        float r0 = q / a2;
        float r1 = abs(q) > 1e-12 ? a0 / q : r0;
        if (a2 < 0.0) {
            lo = min(r0, r1);
            hi = max(r0, r1);
        } else if (dA > 0.0) {
            lo = max(r0, r1);
        } else {
            hi = min(r0, r1);
        }
    }
    if (abs(dA) < 1e-6) {
        if (wA < 0.0) return vec2(1.0, 0.0);
    } else if (dA > 0.0) {
        lo = max(lo, -wA / dA);
    } else {
        hi = min(hi, -wA / dA);
    }
    return vec2(lo, hi);
}

void main() {
    ivec2 pixel = scenePixel(ivec2(gl_FragCoord.xy));
    vec3 o, d;
    float rayEnd;
    viewRay(pixel, texelFetch(SceneDepthSampler, pixel, 0).r, o, d, rayEnd);

    ivec2 tile = clamp(pixel / VolumeTileInfo.x, ivec2(0), VolumeTileInfo.yz - 1);
    vec2 cell = texelFetch(VolumeTileGrid, tile, 0).rg;
    int offset = int(cell.x + 0.5);
    int count = int(cell.y + 0.5);
    float g = VolumeParams.x;
    int pieces = int(VolumeParams.y + 0.5);
    float stratum = tileStratum();
    mat3 viewToWorld = mat3(PhotonViewInverse);

    vec3 sum = vec3(0.0);
    for (int k = 0; k < count; k++) {
        int entry = offset + k;
        int index = int(texelFetch(VolumeTileIndices, ivec2(entry & 4095, entry >> 12), 0).r + 0.5);
        ivec2 base = ivec2((index & 255) * 3, index >> 8);
        vec4 v0 = texelFetch(VolumeLightData, base, 0);
        vec3 rel = v0.xyz - o;
        float b = dot(rel, d);
        float h2 = max(dot(rel, rel) - b * b, 0.0);
        float range2 = v0.w * v0.w;
        if (h2 >= range2) continue;
        float halfChord = sqrt(range2 - h2);
        float t0 = max(0.0, b - halfChord);
        float t1 = min(rayEnd, b + halfChord);
        if (t1 <= t0) continue;
        // the rest only for rays that cross the range: fetches dominate
        vec4 v1 = texelFetch(VolumeLightData, base + ivec2(1, 0), 0);
        bool spot = v1.w > -1.5;
        vec4 v2 = spot ? texelFetch(VolumeLightData, base + ivec2(2, 0), 0) : vec4(0.0);
        // wider than ~85 degrees the pieces resolve the cone on their own
        if (spot && v2.w > 0.087) {
            vec2 cone = coneRange(o, d, v0.xyz, v2.xyz, v2.w);
            t0 = max(t0, cone.x);
            t1 = min(t1, cone.y);
        }
        if (t1 <= t0) continue;

        float H = sqrt(h2 + 1.0);
        float shift = 1.0 / (range2 + 1.0);
        float u0 = t0 - b;
        float u1 = t1 - b;
        // shadowed lights come first, and their visibility maps are numbered like them
        bool shadowed = index < int(VolumeParams.z + 0.5);
        int n = spot || shadowed ? pieces * 2 : pieces;
        float at = shadowed ? stratum : 0.5;
        // the visibility maps are in world axes: turn the ray once per light, not per piece
        vec3 dWorld = shadowed ? viewToWorld * d : vec3(0.0);
        vec3 relWorld = shadowed ? viewToWorld * rel : vec3(0.0);
        float phi0 = atan(u0 / H);
        float phi1 = atan(u1 / H);
        float total = 0.0;
        for (int side = 0; side < 2; side++) {
            float a = side == 0 ? u0 : max(u0, 0.0);
            float z = side == 0 ? min(u1, 0.0) : u1;
            if (z <= a) continue;
            float stepAngle = ((side == 0 ? min(phi1, 0.0) : phi1) - (side == 0 ? phi0 : max(phi0, 0.0))) / float(n);
            float tanStep = tan(stepAngle);
            float tanOffset = tan(at * stepAngle);
            float tanA = a / H;
            for (int i = 0; i < n; i++) {
                // tan of the next boundary and of the sample by angle addition; angles stay under 90 degrees
                float tanB = i == n - 1 ? z / H : (tanA + tanStep) / (1.0 - tanA * tanStep);
                float piece = stepAngle / H - shift * H * (tanB - tanA);
                float um = H * (tanA + tanOffset) / (1.0 - tanA * tanOffset);
                tanA = tanB;
                float rm = max(sqrt(um * um + h2), 1e-4);
                float factor = photon_phase(-um / rm, g);
                if (spot) factor *= smoothstep(v2.w, v1.w, dot((b + um) * d - rel, v2.xyz) / rm);
                if (shadowed) factor *= photon_mapVisibilityWorld(index, (b + um) * dWorld - relWorld, rm);
                total += max(piece, 0.0) * factor;
            }
        }
        sum += v1.rgb * total;
    }

    // half of RGBA16F's 65504: the fog pass adds onto this, and Inf would turn to NaN in the blur
    fragColor = vec4(min(sum, vec3(3e4)), 1.0);
}
