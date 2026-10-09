#version 330

// Full-screen depth from a shader pack that draws its world into the lower-left DepthScale of the screen and upscales
// only the colour: bilinear within a plane (window depth is affine on screen there), the nearest texel across an edge,
// where blending would float pixels between the two surfaces. Drawn with colour writes off and depth always written.
// StepParams.x: the pack's render scale.

#moj_import <photon:dynamic_light_frame.glsl>

uniform sampler2D DepthSampler;

out vec4 fragColor;

ivec2 lastTexel;

float tap(ivec2 texel) {
    return texelFetch(DepthSampler, clamp(texel, ivec2(0), lastTexel), 0).r;
}

// whether b and c lie on one plane, judged from the step on either side of them; one side is enough, so the texels
// next to an edge and on the border still blend
bool straight(float a, float b, float c, float d) {
    float g = c - b;
    float tolerance = 0.25 * abs(g) + 1e-7;
    return abs(b - a - g) <= tolerance || abs(d - c - g) <= tolerance;
}

void main() {
    float depthScale = StepParams.x;
    // the last texel whose centre the pack's squeezed viewport covers
    lastTexel = ivec2(ceil(vec2(textureSize(DepthSampler, 0)) * depthScale - 0.5)) - 1;
    vec2 p = gl_FragCoord.xy * depthScale - 0.5;
    ivec2 i = ivec2(floor(p));
    vec2 f = p - vec2(i);
    float d00 = tap(i), d10 = tap(i + ivec2(1, 0)), d01 = tap(i + ivec2(0, 1)), d11 = tap(i + ivec2(1, 1));
    bool planar = max(max(d00, d10), max(d01, d11)) < 1.0
            && straight(tap(i + ivec2(-1, 0)), d00, d10, tap(i + ivec2(2, 0)))
            && straight(tap(i + ivec2(-1, 1)), d01, d11, tap(i + ivec2(2, 1)))
            && straight(tap(i + ivec2(0, -1)), d00, d01, tap(i + ivec2(0, 2)))
            && straight(tap(i + ivec2(1, -1)), d10, d11, tap(i + ivec2(1, 2)));
    gl_FragDepth = planar ? mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y) : tap(ivec2(gl_FragCoord.xy * depthScale));
    fragColor = vec4(0.0);
}
