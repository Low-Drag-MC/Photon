// Photon dynamic lights, shared by the light pass and any shader that wants to be lit. Light i is four
// RGBA32F texels in row i / 256:
//   t0 = view-space position, range        t1 = colour * intensity, type (0 point, 1 spot)
//   t2 = view-space spot direction, cos outer   t3 = cos inner, shadowed, vanilla ambient, source radius
// Clusters: PhotonClusterInfo.x-pixel tiles times PhotonClusterInfo.w exponential depth slices; each cell is
// (offset, count) into the index texture. Float textures holding exact integers.
// t3.y: 0 unshadowed, else 1 + its visibility map, an octahedral square of how far the light gets in each
// direction; only maps below PhotonVisibilityInfo.z exist this frame.

uniform sampler2D PhotonLightData;
uniform sampler2D PhotonClusterGrid;
uniform sampler2D PhotonClusterIndices;
uniform ivec4 PhotonClusterInfo;   // tile size, tiles x, tiles y, slices
uniform vec4 PhotonClusterView;    // near, slice scale, viewport origin in the target being drawn
uniform sampler2D PhotonVisibilityAtlas;
uniform ivec4 PhotonVisibilityInfo; // texels per map side, maps per atlas row, maps this frame
uniform mat4 PhotonViewInverse;

void photon_fetchLight(int index, out vec4 t0, out vec4 t1, out vec4 t2, out vec4 t3) {
    ivec2 base = ivec2((index & 255) * 4, index >> 8);
    t0 = texelFetch(PhotonLightData, base, 0);
    t1 = texelFetch(PhotonLightData, base + ivec2(1, 0), 0);
    t2 = texelFetch(PhotonLightData, base + ivec2(2, 0), 0);
    t3 = texelFetch(PhotonLightData, base + ivec2(3, 0), 0);
}

// full-resolution pixel (gl_FragCoord) + positive view depth -> (offset, count) into the index list
ivec2 photon_cluster(ivec2 pixel, float viewDepth) {
    ivec2 tile = clamp((pixel - ivec2(PhotonClusterView.zw)) / PhotonClusterInfo.x, ivec2(0), PhotonClusterInfo.yz - 1);
    int slice = clamp(int(floor(log(max(viewDepth, PhotonClusterView.x) / PhotonClusterView.x) * PhotonClusterView.y)),
            0, PhotonClusterInfo.w - 1);
    return ivec2(texelFetch(PhotonClusterGrid, ivec2(tile.x + PhotonClusterInfo.y * slice, tile.y), 0).rg + 0.5);
}

int photon_clusterLight(int entry) {
    return int(texelFetch(PhotonClusterIndices, ivec2(entry & 4095, entry >> 12), 0).r + 0.5);
}

// windowed inverse square, zero at the range
float photon_attenuation(float distanceSq, float range) {
    float range2 = range * range;
    if (distanceSq >= range2) return 0.0;
    float window = clamp(1.0 - (distanceSq * distanceSq) / (range2 * range2), 0.0, 1.0);
    return window * window / (distanceSq + 1.0);
}

float photon_spot(vec3 toLightDir, vec4 t1, vec4 t2, vec4 t3) {
    return t1.w > 0.5 ? smoothstep(t2.w, t3.x, dot(-toLightDir, t2.xyz)) : 1.0;
}

// Henyey-Greenstein, normalised to a mean of 1
float photon_phase(float cosTheta, float g) {
    float k = 1.0 + g * g - 2.0 * g * cosTheta;
    return (1.0 - g * g) / (k * sqrt(k));
}

// a direction of any length to [0, 1]^2, octahedral
vec2 photon_octEncode(vec3 v) {
    vec3 n = v / max(abs(v.x) + abs(v.y) + abs(v.z), 1e-20);
    vec2 p = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return p * 0.5 + 0.5;
}

// how much of the light gets past the blocks to a point `fromLight` away from it in world axes, `distance` far:
// lit up to the first block, dark from half a block behind it
float photon_mapVisibilityWorld(int map, vec3 fromLight, float distance) {
    int size = PhotonVisibilityInfo.x;
    ivec2 tile = ivec2(map % PhotonVisibilityInfo.y, map / PhotonVisibilityInfo.y);
    ivec2 texel = tile * size + clamp(ivec2(photon_octEncode(fromLight) * float(size)), ivec2(0), ivec2(size - 1));
    return clamp((texelFetch(PhotonVisibilityAtlas, texel, 0).r - distance) * 2.0 + 1.0, 0.0, 1.0);
}

// the same from view space: a direction, so mat3 is right even under a shader pack
float photon_mapVisibility(int map, vec3 fromLight, float distance) {
    return photon_mapVisibilityWorld(map, mat3(PhotonViewInverse) * fromLight, distance);
}

// 1 for lights without a visibility map this frame
float photon_visibility(vec4 t3, vec3 fromLight, float distance) {
    int map = int(t3.y + 0.5) - 1;
    return map >= 0 && map < PhotonVisibilityInfo.z ? photon_mapVisibility(map, fromLight, distance) : 1.0;
}
