#version 150

// The volumetric haze over the finished frame, particles included: blended ONE, ONE_MINUS_SRC_COLOR, which is the
// soft add 1 - (1 - frame) * exp(-haze) without reading the frame back. Fog that hides what is behind it first
// darkens the frame by its transmittance, blended ZERO, SRC_COLOR.

#moj_import <photon:dynamic_light_depth.glsl>

uniform sampler2D VolumeSampler;
uniform vec4 PackParams;      // x: over a shader pack's final image
uniform ivec2 ViewportOrigin; // where the snapshot sits in the target being drawn (an editor scene's viewport)
uniform int HazeMode;         // 0 the light the haze adds, 1 the transmittance of the fog

out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy) - ViewportOrigin;
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    // a pack's hand is drawn with its own projection, too close for any haze
    if (PackParams.x > 0.5 && depth < 0.56) discard;
    float z = depth >= 1.0 ? 1e6 : -viewPos((vec2(pixel) + 0.5) / ScreenSize, depth).z;
    vec4 volume = upsample(VolumeSampler, pixel, z, 1e6);
    fragColor = HazeMode == 1 ? vec4(vec3(volume.a), 1.0) : vec4(1.0 - exp(-volume.rgb), 1.0);
}
