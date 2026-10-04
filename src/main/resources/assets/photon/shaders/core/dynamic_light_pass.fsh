#version 150

// Irradiance from the clustered lights at ResolutionScale, position and normal from depth. Alpha carries
// the lights' contribution-weighted vanilla ambient, which the composite divides out of the frame.

#moj_import <photon:dynamic_light.glsl>
#moj_import <photon:dynamic_light_depth.glsl>

uniform usampler2D VoxelPages;
uniform usampler2D VoxelBricks;

uniform mat4 ProjMat;
uniform mat4 IViewMat;
uniform vec4 ShadowParams;   // mode (0 off, 1 screen, 2 voxel), steps, occluder thickness, max march
uniform vec4 ContactParams;  // contact steps, contact distance, soft shadows, unused
uniform vec3 CameraFrac;     // camera position minus its block position
uniform ivec3 VoxelOrigin;   // voxel region min corner in blocks, relative to the camera block
uniform vec4 PackParams;     // x: drawing over a shader pack's final image
uniform int DebugMode;

out vec4 fragColor;

const ivec3 REGION_SECTIONS = ivec3(32, 16, 32);
const ivec3 REGION_CELLS = REGION_SECTIONS * 32;

float pixelFootprint(float viewZ) {
    return 2.0 * -viewZ / (ProjMat[1][1] * ScreenSize.y);
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Each pixel of a 4x4 tile takes one of 16 points on the light's disk (4 rings x 4 sectors), laid out as a
// Graeco-Latin square over GF(4) so every tile row and column holds each ring and sector once; the blur
// integrates the tile.
ivec2 tileCell() {
    const int mul2[4] = int[](0, 2, 3, 1);
    ivec2 p = ivec2(gl_FragCoord.xy) & 3;
    return ivec2(p.x ^ p.y, p.x ^ mul2[p.y]);
}

// the cell's point on the disk the light's sphere shows to `origin`; x = ring, y = sector
vec3 lightSample(vec3 lightPos, vec3 origin, float radius, int light, ivec2 cell) {
    vec3 w = normalize(lightPos - origin);
    vec3 a = abs(w.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 u = normalize(cross(a, w));
    vec3 v = cross(w, u);
    float r = radius * sqrt((float(cell.x) + 0.5) / 4.0);
    float phi = (float(cell.y) + 0.5 + 0.25 * float(cell.x)) * 1.5707963 + float(light) * 1.3;
    return lightPos + (u * cos(phi) + v * sin(phi)) * r;
}

// 1 = lit. Leaving the screen or passing the near plane counts as lit. Starts a few pixels out and
// stops short of the light: whatever draws the source is not its own occluder.
float screenShadow(vec3 origin, vec3 target, float maxLen, int steps, float noise, float slope) {
    vec3 delta = target - origin;
    float len = length(delta);
    float start = min(3.0 * pixelFootprint(origin.z), len * 0.5);
    float marchLen = max(min(len - 0.3, maxLen) - start, 0.0);
    vec3 dir = delta / len;
    for (int i = 0; i < steps; i++) {
        vec3 s = origin + dir * (start + (float(i) + noise) / float(steps) * marchLen);
        if (s.z > -0.05) break;
        vec4 clip = ProjMat * vec4(s, 1.0);
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
        float behind = viewPos(uv, texture(SceneDepthSampler, uv).r).z - s.z;
        float bias = 0.02 + 2.0 * pixelFootprint(s.z) / slope;
        if (behind > bias && behind < ShadowParams.z) return 0.0;
    }
    return 1.0;
}

// pages: 0 unknown, 1 empty, 2 solid, 3+ brick slot; bricks: one byte per block, a 2x2x2 occupancy mask
uint pageAt(ivec3 section) {
    int index = section.x + REGION_SECTIONS.x * (section.z + REGION_SECTIONS.z * section.y);
    return texelFetch(VoxelPages, ivec2(index & 127, index >> 7), 0).r;
}

uint blockMask(ivec3 cell, uint page) {
    int slot = int(page - 3u);
    ivec3 local = (cell >> 1) & 15;
    ivec2 texel = ivec2((slot & 31) * 64 + local.x + 16 * (local.y & 3), (slot >> 5) * 64 + local.z + 16 * (local.y >> 2));
    return texelFetch(VoxelBricks, texel, 0).r;
}

// DDA through half-block cells, from and to in blocks relative to the voxel region. Empty sections and
// empty blocks are crossed in one jump each. Outside the region the path is unknown and counts as clear.
float voxelShadow(vec3 fromBlock, vec3 toBlock) {
    vec3 p0 = fromBlock * 2.0;
    vec3 delta = toBlock * 2.0 - p0;
    float len = length(delta);
    float stopAt = len - 0.6;
    if (stopAt <= 0.0) return 1.0;
    vec3 dir = delta / len;
    vec3 safe = vec3(abs(dir.x) < 1e-6 ? 1e-6 : dir.x, abs(dir.y) < 1e-6 ? 1e-6 : dir.y, abs(dir.z) < 1e-6 ? 1e-6 : dir.z);
    vec3 invDir = 1.0 / safe;
    ivec3 stepDir = ivec3(sign(safe));
    vec3 tDelta = abs(invDir);
    vec3 forward = step(vec3(0.0), safe);
    ivec3 cell = ivec3(floor(p0));
    vec3 tMax = (vec3(cell) + forward - p0) * invDir;
    float t = 0.0;
    bool started = false;
    ivec3 cachedSection = ivec3(-1);
    uint page = 0u;
    for (int i = 0; i < 160; i++) {
        if (started) {
            if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, REGION_CELLS))) return 1.0;
            ivec3 section = cell >> 5;
            if (section != cachedSection) {
                cachedSection = section;
                page = pageAt(section);
            }
            if (page == 2u) return 0.0;
            // size of the empty box to jump over, in cells: a whole section, or a whole block
            float jump = 0.0;
            if (page < 2u) {
                jump = 32.0;
            } else {
                uint bits = blockMask(cell, page);
                if (bits == 0u) {
                    jump = 2.0;
                } else {
                    ivec3 sub = cell & 1;
                    if (((bits >> uint(sub.x + 2 * sub.y + 4 * sub.z)) & 1u) != 0u) return 0.0;
                }
            }
            if (jump > 0.0) {
                vec3 boxMin = floor(vec3(cell) / jump) * jump;
                vec3 exitT = (boxMin + forward * jump - p0) * invDir;
                float tExit = min(exitT.x, min(exitT.y, exitT.z));
                if (tExit >= stopAt) return 1.0;
                t = tExit + 1e-3;
                cell = ivec3(floor(p0 + dir * t));
                tMax = (vec3(cell) + forward - p0) * invDir;
                continue;
            }
        }
        started = true;
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            t = tMax.x; tMax.x += tDelta.x; cell.x += stepDir.x;
        } else if (tMax.y < tMax.z) {
            t = tMax.y; tMax.y += tDelta.y; cell.y += stepDir.y;
        } else {
            t = tMax.z; tMax.z += tDelta.z; cell.z += stepDir.z;
        }
        if (t >= stopAt) return 1.0;
    }
    return 1.0;
}

void main() {
    ivec2 pixel = scenePixel(ivec2(gl_FragCoord.xy));
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    // Iris draws the hand with a z-scaled projection; packs tell it apart by depth the same way
    if (depth >= 1.0 || (PackParams.x > 0.5 && depth < 0.56)) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 p = viewPos((vec2(pixel) + 0.5) / ScreenSize, depth);
    vec3 n = reconstructNormal(pixel, p);
    mat3 viewToWorld = mat3(IViewMat);
    vec3 nw = normalize(viewToWorld * n);
    vec3 an = abs(nw);
    float major = max(an.x, max(an.y, an.z));
    if (major > 0.985) {
        nw = an.y == major ? vec3(0.0, sign(nw.y), 0.0) : an.x == major ? vec3(sign(nw.x), 0.0, 0.0) : vec3(0.0, 0.0, sign(nw.z));
        n = transpose(viewToWorld) * nw;
    }

    float viewDepth = -p.z;
    bool soft = ContactParams.z > 0.5;
    ivec2 cell = tileCell();
    float noise = soft ? (float(cell.x * 4 + cell.y) + 0.5) / 16.0 : ign(gl_FragCoord.xy);
    vec3 origin = p + n * (0.03 + 0.002 * viewDepth);
    float slope = max(abs(dot(n, normalize(-p))), 0.15);
    // the full matrix, not mat3: under a shader pack Iris folds view bobbing, translation included, into the model-view
    vec3 originBlock = (IViewMat * vec4(origin, 1.0)).xyz + CameraFrac - vec3(VoxelOrigin);
    int mode = int(ShadowParams.x + 0.5);

    vec3 irradiance = vec3(0.0);
    vec3 unshadowed = vec3(0.0);
    float ambientSum = 0.0;
    float weightSum = 0.0;
    ivec2 cluster = photon_cluster(pixel, viewDepth);
    for (int k = 0; k < cluster.y; k++) {
        int light = photon_clusterLight(cluster.x + k);
        vec4 t0, t1, t2, t3;
        photon_fetchLight(light, t0, t1, t2, t3);
        vec3 toLight = t0.xyz - p;
        float d2 = dot(toLight, toLight);
        float attenuation = photon_attenuation(d2, t0.w);
        if (attenuation <= 0.0) continue;
        vec3 l = toLight * inversesqrt(d2);
        float ndl = dot(n, l);
        if (ndl <= 0.0) continue;
        attenuation *= photon_spot(l, t1, t2, t3);
        if (attenuation <= 0.0) continue;
        vec3 contribution = t1.rgb * (attenuation * ndl);
        unshadowed += contribution;
        float visible = 1.0;
        if (mode > 0 && t3.y > 0.5) {
            vec3 target = soft && t3.w > 0.0 ? lightSample(t0.xyz, origin, t3.w, light, cell) : t0.xyz;
            if (mode == 2) {
                visible = voxelShadow(originBlock, (IViewMat * vec4(target, 1.0)).xyz + CameraFrac - vec3(VoxelOrigin));
                if (visible > 0.0 && ContactParams.x > 0.5) {
                    visible = screenShadow(origin, target, ContactParams.y, int(ContactParams.x), noise, slope);
                }
            } else {
                visible = screenShadow(origin, target, ShadowParams.w, int(ShadowParams.y), noise, slope);
            }
        }
        vec3 lit = contribution * visible;
        irradiance += lit;
        float weight = dot(lit, vec3(0.2126, 0.7152, 0.0722));
        ambientSum += weight * t3.z;
        weightSum += weight;
    }

    float ambient = weightSum > 1e-6 ? ambientSum / weightSum : 0.0;
    if (DebugMode == 3) {
        float total = dot(unshadowed, vec3(1.0));
        float visible = total > 1e-4 ? dot(irradiance, vec3(1.0)) / total : 1.0;
        fragColor = vec4(mix(vec3(1.0, 0.1, 0.1), vec3(1.0), visible) * min(total * 4.0, 1.0), ambient);
    } else if (DebugMode == 5) {
        fragColor = vec4(vec3(float(cluster.y) / 16.0), ambient);
    } else {
        // RGBA16F tops out at 65504; past that a texel turns to Inf, and Inf * 0 in the blur to NaN
        fragColor = vec4(min(irradiance, vec3(6e4)), ambient);
    }
}
