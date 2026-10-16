#version 150

// One axis of a depth-aware blur that integrates the 4x4 tile of shadow samples into penumbrae: box4 * box4
// has a double zero at the tile's frequencies, and taps are weighted against the depth the centre's plane
// predicts, so surfaces seen at an angle blur as evenly as those facing the camera.

#moj_import <photon:dynamic_light_depth.glsl>

uniform sampler2D IrradianceSampler;
uniform vec2 BlurDirection;
uniform float SkyDepth;  // where sky texels stand; below 0 (the light buffer, empty there) they are left alone

out vec4 fragColor;

float depthAt(ivec2 lightPixel) {
    float z = lightTexelDepth(lightPixel);
    return z < 0.0 ? SkyDepth : z;
}

void main() {
    ivec2 center = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(IrradianceSampler, 0);
    vec4 value = texelFetch(IrradianceSampler, center, 0);
    float z = depthAt(center);
    if (z < 0.0) {
        fragColor = value;
        return;
    }
    // 1/z is affine across a plane in screen space; take the slope from the neighbour on the same surface
    ivec2 axis = ivec2(BlurDirection);
    float zp = depthAt(clamp(center + axis, ivec2(0), size - 1));
    float zm = depthAt(clamp(center - axis, ivec2(0), size - 1));
    float slope = 0.0;
    if (zp > 0.0 && zm > 0.0) {
        float forward = 1.0 / zp - 1.0 / z;
        float backward = 1.0 / z - 1.0 / zm;
        slope = abs(forward) < abs(backward) ? forward : backward;
    }

    const float tent[4] = float[](1.0, 0.75, 0.5, 0.25);
    float tolerance = 0.03 * z + 0.05;
    vec4 sum = value * tent[0];
    float weightSum = tent[0];
    for (int i = 1; i <= 3; i++) {
        for (int s = -1; s <= 1; s += 2) {
            ivec2 q = clamp(center + axis * (i * s), ivec2(0), size - 1);
            float zq = depthAt(q);
            if (zq < 0.0) continue;
            float expected = 1.0 / max(1.0 / z + slope * float(i * s), 1e-5);
            float w = tent[i] * exp(-abs(zq - expected) / tolerance);
            sum += texelFetch(IrradianceSampler, q, 0) * w;
            weightSum += w;
        }
    }
    fragColor = sum / weightSum;
}
