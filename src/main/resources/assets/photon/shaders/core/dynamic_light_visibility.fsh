#version 330

// Per shadowed light, the distance to the first voxel in every direction out to its range, as an octahedral map:
// what the haze, fog volumes and lit particles are shadowed from.
// StepParams: texels per map side (x), maps per atlas row (y), maps (z).

#moj_import <photon:dynamic_light_voxel.glsl>

// one texel per map: the light's view position and range
uniform samplerBuffer VisibilityLights;

out vec4 fragColor;

// [0, 1]^2 back to a unit direction, the inverse of photon_octEncode
vec3 octDecode(vec2 uv) {
    vec2 f = uv * 2.0 - 1.0;
    vec3 n = vec3(f, 1.0 - abs(f.x) - abs(f.y));
    float t = max(-n.z, 0.0);
    n.xy += vec2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
    return normalize(n);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int size = int(StepParams.x + 0.5);
    ivec2 tile = texel / size;
    int light = tile.x + tile.y * int(StepParams.y + 0.5);
    if (light >= int(StepParams.z + 0.5)) {
        fragColor = vec4(0.0);
        return;
    }
    vec4 v0 = texelFetch(VisibilityLights, light);
    vec3 fromBlock = voxelPos(v0.xyz);
    vec3 direction = octDecode((vec2(texel - tile * size) + 0.5) / float(size));
    fragColor = vec4(voxelTrace(fromBlock, direction, v0.w), 0.0, 0.0, 1.0);
}
