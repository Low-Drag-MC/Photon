// Depth helpers shared by every dynamic-light pass, which must agree texel for texel.

uniform sampler2D SceneDepthSampler;
uniform mat4 IProjMat;
uniform vec2 ScreenSize;
uniform float ResolutionScale;

vec3 viewPos(vec2 uv, float depth) {
    vec4 p = IProjMat * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

// the view ray through a pixel and where the scene stops it; from the near plane, so an orthographic editor camera
// works too, and not toward the far plane, which a mod's projection may push to infinity
void viewRay(ivec2 pixel, float depth, out vec3 origin, out vec3 dir, out float rayEnd) {
    vec2 uv = (vec2(pixel) + 0.5) / ScreenSize;
    origin = viewPos(uv, 0.0);
    dir = normalize(viewPos(uv, 0.5) - origin);
    rayEnd = depth < 1.0 ? dot(viewPos(uv, depth) - origin, dir) : 1e5;
}

vec3 viewPosAt(ivec2 pixel) {
    pixel = clamp(pixel, ivec2(0), ivec2(ScreenSize) - 1);
    return viewPos((vec2(pixel) + 0.5) / ScreenSize, texelFetch(SceneDepthSampler, pixel, 0).r);
}

// from the closer neighbour on each axis; on the screen edge the clamped neighbour is the centre itself
vec3 reconstructNormal(ivec2 pixel, vec3 center) {
    ivec2 last = ivec2(ScreenSize) - 1;
    vec3 l = viewPosAt(pixel - ivec2(1, 0));
    vec3 r = viewPosAt(pixel + ivec2(1, 0));
    vec3 d = viewPosAt(pixel - ivec2(0, 1));
    vec3 u = viewPosAt(pixel + ivec2(0, 1));
    bool left = pixel.x == last.x || (pixel.x > 0 && abs(l.z - center.z) < abs(r.z - center.z));
    bool down = pixel.y == last.y || (pixel.y > 0 && abs(d.z - center.z) < abs(u.z - center.z));
    vec3 n = cross(left ? center - l : r - center, down ? center - d : u - center);
    float length2 = dot(n, n);
    if (length2 < 1e-24) return -normalize(center);
    n *= inversesqrt(length2);
    return dot(n, center) > 0.0 ? -n : n;
}

// the full-resolution pixel a light-buffer texel stands for
ivec2 scenePixel(ivec2 lightPixel) {
    return clamp(ivec2(floor((vec2(lightPixel) + 0.5) / ResolutionScale)), ivec2(0), ivec2(ScreenSize) - 1);
}

// positive view depth under a light-buffer texel, -1 for the sky
float lightTexelDepth(ivec2 lightPixel) {
    ivec2 pixel = scenePixel(lightPixel);
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    return depth >= 1.0 ? -1.0 : -viewPos((vec2(pixel) + 0.5) / ScreenSize, depth).z;
}

// bilinear over the four nearest light texels, each weighted by how close its depth is to ours; sky texels
// stand at skyDepth
vec4 upsample(sampler2D source, ivec2 pixel, float z, float skyDepth) {
    if (ResolutionScale >= 0.999) return texelFetch(source, pixel, 0);
    vec2 lp = (vec2(pixel) + 0.5) * ResolutionScale - 0.5;
    ivec2 base = ivec2(floor(lp));
    vec2 f = lp - vec2(base);
    ivec2 size = textureSize(source, 0);
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    vec4 nearest = vec4(0.0);
    float nearestDz = 1e30;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            ivec2 q = clamp(base + ivec2(i, j), ivec2(0), size - 1);
            float zq = lightTexelDepth(q);
            vec4 value = texelFetch(source, q, 0);
            float dz = abs((zq < 0.0 ? skyDepth : zq) - z);
            if (dz < nearestDz) {
                nearestDz = dz;
                nearest = value;
            }
            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y) * exp(-dz / (0.02 * z + 0.03));
            sum += value * w;
            weightSum += w;
        }
    }
    return weightSum > 1e-4 ? sum / weightSum : nearest;
}

// Each pixel of a 4x4 tile takes one of 16 cells (e.g. 4 rings x 4 sectors of a light's disk), laid out as a
// Graeco-Latin square over GF(4) so every tile row and column holds each x and each y once; the blur integrates
// the tile.
ivec2 tileCell() {
    const int mul2[4] = int[](0, 2, 3, 1);
    ivec2 p = ivec2(gl_FragCoord.xy) & 3;
    return ivec2(p.x ^ p.y, p.x ^ mul2[p.y]);
}

// the tile cell as one of 16 evenly spaced offsets in (0, 1)
float tileStratum() {
    ivec2 cell = tileCell();
    return (float(cell.x * 4 + cell.y) + 0.5) / 16.0;
}
