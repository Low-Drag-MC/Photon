// Photon dynamic lights, shared by the light pass and any shader that wants to be lit. Light i is four
// RGBA32F texels in row i / 256:
//   t0 = view-space position, range        t1 = colour * intensity, type (0 point, 1 spot)
//   t2 = view-space spot direction, cos outer   t3 = cos inner, shadowed (0/1), vanilla ambient, source radius
// Clusters: PhotonClusterInfo.x-pixel tiles times PhotonClusterInfo.w exponential depth slices; each cell is
// (offset, count) into the index texture. Float textures holding exact integers.

uniform sampler2D PhotonLightData;
uniform sampler2D PhotonClusterGrid;
uniform sampler2D PhotonClusterIndices;
uniform ivec4 PhotonClusterInfo;   // tile size, tiles x, tiles y, slices
uniform vec4 PhotonClusterView;    // near, slice scale, viewport origin in the target being drawn

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
