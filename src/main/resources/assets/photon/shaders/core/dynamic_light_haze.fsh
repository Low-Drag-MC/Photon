#version 330

// The volumetric haze over the finished frame, particles included: blended ONE, ONE_MINUS_SRC_COLOR, which is the
// soft add 1 - (1 - frame) * exp(-haze) without reading the frame back. Fog that hides what is behind it first
// darkens the frame by its transmittance, blended ZERO, SRC_COLOR.
// StepParams.x: 0 the light the haze adds, 1 the transmittance of the fog.

#moj_import <photon:dynamic_light_depth.glsl>

uniform sampler2D VolumeSampler;

out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    // a pack's hand is drawn with its own projection, too close for any haze
    if (isPackHand(depth)) discard;
    float z = isSky(depth) ? 1e6 : -viewPos((vec2(pixel) + 0.5) / ScreenSize, depth).z;
    vec4 volume = upsample(VolumeSampler, pixel, z, 1e6);
    fragColor = StepParams.x > 0.5 ? vec4(vec3(volume.a), 1.0) : vec4(1.0 - exp(-volume.rgb), 1.0);
}
