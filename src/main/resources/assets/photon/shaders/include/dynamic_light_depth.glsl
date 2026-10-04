// Scene depth helpers for the light pass, its blur and the composite, which must agree texel for texel.

uniform sampler2D SceneDepthSampler;
uniform mat4 IProjMat;
uniform vec2 ScreenSize;
uniform float ResolutionScale;

vec3 viewPos(vec2 uv, float depth) {
    vec4 p = IProjMat * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
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
