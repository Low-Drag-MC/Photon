#ifdef PARTICLE_INSTANCE

layout(location = 0) in vec3 aPos;

layout(location = 1) in vec3 iPos;
layout(location = 2) in vec2 iSize;
layout(location = 3) in vec3 iScale;
layout(location = 4) in vec4 iRot;
layout(location = 5) in vec4 iColor;
layout(location = 6) in vec4 iUV;
layout(location = 7) in int iLight;

// additional GPU data slots — MIRRORED FROM PhotonGpuChannels (keep in lockstep)
layout(location = 8) in vec4 iCustom0;
layout(location = 9) in vec4 iCustom1;
layout(location = 10) in vec4 iCustom2;
layout(location = 11) in vec4 iCustom3;
layout(location = 12) in vec4 iCustom4;

#elif defined(PARTICLE_MODEL_INSTANCE)

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in float aBrightness;

layout(location = 4) in vec3 iPos;
layout(location = 5) in vec3 iScale;
layout(location = 6) in vec4 iRot;
layout(location = 7) in vec4 iColor;
layout(location = 8) in int iLight;

// additional GPU data slots — MIRRORED FROM PhotonGpuChannels (keep in lockstep)
layout(location = 9) in vec4 iCustom0;
layout(location = 10) in vec4 iCustom1;
layout(location = 11) in vec4 iCustom2;
layout(location = 12) in vec4 iCustom3;
layout(location = 13) in vec4 iCustom4;

#elif defined(TRAIL_INSTANCE)

// one instance per trail segment; aPos.x in {0,1} selects curr/next, aPos.y in {-1,+1} the ribbon
// side. Point data is pulled from the PhotonPoints buffer texture (3 texels per point:
// pos+width / premultiplied color / u); each trail's block is padded with bitwise copies of its
// first/last point, so the c-1 / c+2 neighbor fetches stay in-trail and the endpoint-fallback
// equality tests hold.
layout(location = 0) in vec2 aPos;

layout(location = 1) in ivec2 iSeg;  // (point index of curr, packed light)
layout(location = 2) in vec2 iSegV;  // (v0, v1)

// additional GPU data slots — MIRRORED FROM PhotonGpuChannels (keep in lockstep)
layout(location = 3) in vec4 iCustom0;
layout(location = 4) in vec4 iCustom1;
layout(location = 5) in vec4 iCustom2;
layout(location = 6) in vec4 iCustom3;

uniform samplerBuffer PhotonPoints;

#elif defined(ARA_TRAIL_INSTANCE)

// one instance per AraTrail flat segment; aPos.x in {0,1} selects the segment's curr/next point
// (walk order, newest-first), aPos.y in {-1,+1} the +/- bitangent side. Point data is pulled from
// PhotonPoints (4 texels per point: pos+u / offset / normal / color); the per-point frames are
// CPU-computed, so there are no neighbor fetches and no padding.
layout(location = 0) in vec2 aPos;

layout(location = 1) in int iSeg;    // point index of curr
layout(location = 2) in vec2 iSegV;  // (vA, vB): cross-ribbon v of the +side / -side

// additional GPU data slots — MIRRORED FROM PhotonGpuChannels (keep in lockstep)
layout(location = 3) in vec4 iCustom0;
layout(location = 4) in vec4 iCustom1;
layout(location = 5) in vec4 iCustom2;
layout(location = 6) in vec4 iCustom3;

uniform samplerBuffer PhotonPoints;

#elif defined(ARA_TRAIL_TUBE_INSTANCE)

// one instance per AraTrail ring pair; aPos = (x: curr/next ring, y/z: section polygon vertex,
// w: uAround, all baked from the section config). Point data is pulled from PhotonPoints
// (4 texels per point: pos+thickness / bitangent+vCoord / tangent(UNNORMALIZED) / color).
layout(location = 0) in vec4 aPos;

layout(location = 1) in int iSeg;    // point index of curr

// additional GPU data slots — MIRRORED FROM PhotonGpuChannels (keep in lockstep)
layout(location = 3) in vec4 iCustom0;
layout(location = 4) in vec4 iCustom1;
layout(location = 5) in vec4 iCustom2;
layout(location = 6) in vec4 iCustom3;

uniform samplerBuffer PhotonPoints;

#elif defined(BEAM_INSTANCE)

// one instance per beam; aPos.x in {0,1} selects start/end, aPos.y in {-1,+1} the width side
layout(location = 0) in vec2 aPos;

layout(location = 1) in vec4 iStart; // xyz = start (camera-relative), w = half-width
layout(location = 2) in vec3 iEnd;
layout(location = 3) in vec4 iColor;
layout(location = 4) in vec4 iUV;    // (u0, v0, u1, v1), uv-scroll baked in
layout(location = 5) in int iLight;

// additional GPU data slots — MIRRORED FROM PhotonGpuChannels (keep in lockstep)
layout(location = 6) in vec4 iCustom0;
layout(location = 7) in vec4 iCustom1;
layout(location = 8) in vec4 iCustom2;

#else

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

#endif

mat3 quatToMat(vec4 q) {
    float x2 = q.x + q.x, y2 = q.y + q.y, z2 = q.z + q.z;
    float xx = q.x * x2, yy = q.y * y2, zz = q.z * z2;
    float xy = q.x * y2, xz = q.x * z2, yz = q.y * z2;
    float wx = q.w * x2, wy = q.w * y2, wz = q.w * z2;

    return mat3(
    1 - (yy + zz), xy + wz, xz - wy,
    xy - wz, 1 - (xx + zz), yz + wx,
    xz + wy, yz - wx, 1 - (xx + yy)
    );
}

struct ParticleData {
    vec3 Position;
    vec4 Color;
    vec2 UV;
    ivec2 LightUV;
    vec3 Normal;
};

ParticleData getParticleData() {
    ParticleData data;

#ifdef PARTICLE_INSTANCE

    mat3 rotMat = quatToMat(iRot);
    data.Position = (rotMat * vec3(aPos.xy * iSize, aPos.z)) * iScale + iPos;
    data.Color = iColor;
    data.UV = mix(iUV.xy, iUV.zw, aPos.xy * 0.5 + 0.5);
    // vanilla UV2 order is (block, sky); java packs sky<<20 | block<<4
    data.LightUV = ivec2(iLight & 0xFFFF, (iLight >> 16) & 0xFFFF);
    data.Normal = normalize(rotMat * vec3(0, 0, 1));

#elif defined(PARTICLE_MODEL_INSTANCE)

    mat3 rotMat = quatToMat(iRot);
    vec3 centeredPos = aPos - vec3(0.5);   // centered
    data.Position = (rotMat * (centeredPos * iScale)) + iPos;
    data.Color = vec4(iColor.rgb * aBrightness, iColor.a);
    data.UV = aUV;
    // vanilla UV2 order is (block, sky); java packs sky<<20 | block<<4
    data.LightUV = ivec2(iLight & 0xFFFF, (iLight >> 16) & 0xFFFF);
    data.Normal = normalize(rotMat * aNormal);

#elif defined(TRAIL_INSTANCE)

    // camera-relative expansion: the camera sits at the origin, so "point - cameraPos" is the
    // point itself. Padded endpoints (prev==curr / next2==next, bitwise copies from upload)
    // mark the trail ends and fall back to the segment's own normal — mirrors the CPU strip.
    int c = iSeg.x;
    vec4 pC = texelFetch(PhotonPoints, c * 3);           // xyz = pos, w = width
    vec4 pN = texelFetch(PhotonPoints, (c + 1) * 3);
    vec3 pPrev = texelFetch(PhotonPoints, (c - 1) * 3).xyz;
    vec3 pNext2 = texelFetch(PhotonPoints, (c + 2) * 3).xyz;
    vec3 seg = pN.xyz - pC.xyz;
    vec3 nCurr = normalize(cross(seg, pC.xyz));
    vec3 nPrev = (pPrev == pC.xyz) ? nCurr : normalize(cross(pC.xyz - pPrev, pPrev));
    vec3 nNext = (pNext2 == pN.xyz) ? nCurr : normalize(cross(pNext2 - pN.xyz, pN.xyz));
    // averaged like the CPU path: (a + b) / 2, NOT renormalized
    vec3 avg = mix((nPrev + nCurr) * 0.5, (nCurr + nNext) * 0.5, aPos.x);
    data.Position = mix(pC.xyz, pN.xyz, aPos.x) + avg * (mix(pC.w, pN.w, aPos.x) * aPos.y);
    vec3 fnVec = (aPos.x < 0.5 || pNext2 == pN.xyz) ? seg : (pNext2 - pN.xyz);
    data.Normal = normalize(cross(avg, fnVec));
    data.Color = mix(texelFetch(PhotonPoints, c * 3 + 1), texelFetch(PhotonPoints, (c + 1) * 3 + 1), aPos.x);
    float uC = texelFetch(PhotonPoints, c * 3 + 2).x;
    float uN = texelFetch(PhotonPoints, (c + 1) * 3 + 2).x;
    // up (+1) uses v0 (iSegV.x), down (-1) uses v1 (iSegV.y)
    data.UV = vec2(mix(uC, uN, aPos.x), mix(iSegV.y, iSegV.x, aPos.y * 0.5 + 0.5));
    data.LightUV = ivec2(iSeg.y & 0xFFFF, (iSeg.y >> 16) & 0xFFFF);

#elif defined(ARA_TRAIL_INSTANCE)

    // camera-relative flat ribbon; the per-point frames (offset = bitangent * thickness, normal)
    // are CPU-computed — the expansion is a pure lerp. Light is hardcoded FULL_BRIGHT like the
    // CPU path.
    int c = iSeg;
    vec4 t0C = texelFetch(PhotonPoints, c * 4);           // xyz = pos, w = u (vCoord)
    vec4 t0N = texelFetch(PhotonPoints, (c + 1) * 4);
    vec3 off = mix(texelFetch(PhotonPoints, c * 4 + 1).xyz, texelFetch(PhotonPoints, (c + 1) * 4 + 1).xyz, aPos.x);
    data.Position = mix(t0C.xyz, t0N.xyz, aPos.x) + off * aPos.y;
    // raw mix — the CPU path never renormalizes after the renderMatrix transform
    data.Normal = mix(texelFetch(PhotonPoints, c * 4 + 2).xyz, texelFetch(PhotonPoints, (c + 1) * 4 + 2).xyz, aPos.x);
    data.Color = mix(texelFetch(PhotonPoints, c * 4 + 3), texelFetch(PhotonPoints, (c + 1) * 4 + 3), aPos.x);
    // +side (+1) uses vA (iSegV.x), -side (-1) uses vB (iSegV.y)
    data.UV = vec2(mix(t0C.w, t0N.w, aPos.x), mix(iSegV.y, iSegV.x, aPos.y * 0.5 + 0.5));
    data.LightUV = ivec2(240, 240);                       // LightTexture.FULL_BRIGHT

#elif defined(ARA_TRAIL_TUBE_INSTANCE)

    // ring offset = (sx * bitangent + sy * tangent) * thickness — tangent stays UNNORMALIZED and
    // the vertex normal is the UNNORMALIZED scaled offset, both mirroring the CPU path exactly.
    int c = iSeg;
    vec4 t0C = texelFetch(PhotonPoints, c * 4);           // xyz = pos, w = thickness
    vec4 t0N = texelFetch(PhotonPoints, (c + 1) * 4);
    vec4 t1C = texelFetch(PhotonPoints, c * 4 + 1);       // xyz = bitangent, w = vCoord
    vec4 t1N = texelFetch(PhotonPoints, (c + 1) * 4 + 1);
    vec3 off = (aPos.y * mix(t1C.xyz, t1N.xyz, aPos.x)
              + aPos.z * mix(texelFetch(PhotonPoints, c * 4 + 2).xyz, texelFetch(PhotonPoints, (c + 1) * 4 + 2).xyz, aPos.x))
              * mix(t0C.w, t0N.w, aPos.x);
    data.Position = mix(t0C.xyz, t0N.xyz, aPos.x) + off;
    data.Normal = off;
    data.Color = mix(texelFetch(PhotonPoints, c * 4 + 3), texelFetch(PhotonPoints, (c + 1) * 4 + 3), aPos.x);
    data.UV = vec2(aPos.w, mix(t1C.w, t1N.w, aPos.x));
    data.LightUV = ivec2(240, 240);                       // LightTexture.FULL_BRIGHT

#elif defined(BEAM_INSTANCE)

    vec3 beamDir = iEnd - iStart.xyz;
    // toO = start - cameraPos = start (camera at origin)
    vec3 beamN = normalize(cross(iStart.xyz, beamDir)) * iStart.w;
    data.Position = mix(iStart.xyz, iEnd, aPos.x) + beamN * aPos.y;
    data.Color = iColor;
    // -n side (-1) uses v0 (iUV.y), +n side (+1) uses v1 (iUV.w)
    data.UV = vec2(mix(iUV.x, iUV.z, aPos.x), mix(iUV.y, iUV.w, aPos.y * 0.5 + 0.5));
    data.LightUV = ivec2(iLight & 0xFFFF, (iLight >> 16) & 0xFFFF);
    data.Normal = normalize(cross(beamDir, beamN));

#else

    data.Position = Position;
    data.Color = Color;
    data.UV = UV0;
    data.LightUV = UV2;
    data.Normal = Normal;

#endif

    return data;
}

// ---------------------------------------------------------------------------
// additional GPU data accessors — MIRRORED FROM PhotonGpuChannels (keep in lockstep).
// Channels a variant does not support (and the whole CPU path) read 0.
// ---------------------------------------------------------------------------
#if defined(PARTICLE_INSTANCE) || defined(PARTICLE_MODEL_INSTANCE)

float photon_data_random()          { return iCustom0.x; }
float photon_data_t()               { return iCustom0.y; }
float photon_data_age()             { return iCustom0.z; }
float photon_data_lifetime()        { return iCustom0.w; }
vec3  photon_data_position()        { return iCustom1.xyz; }
float photon_data_isCollided()      { return iCustom1.w; }
vec3  photon_data_velocity()        { return iCustom2.xyz; }
float photon_data_emitter_t()       { return iCustom2.w; }
float photon_data_emitter_age()     { return iCustom3.x; }
vec3  photon_data_emitter_position(){ return iCustom3.yzw; }
vec3  photon_data_emitter_velocity(){ return iCustom4.xyz; }
float photon_data_point_t()         { return 0.0; }
float photon_data_point_life()      { return 0.0; }
vec3  photon_data_beam_direction()  { return vec3(0.0); }
float photon_data_beam_length()     { return 0.0; }

#elif defined(TRAIL_INSTANCE) || defined(ARA_TRAIL_INSTANCE) || defined(ARA_TRAIL_TUBE_INSTANCE)

float photon_data_random()          { return iCustom0.x; }
float photon_data_t()               { return iCustom0.y; }
float photon_data_age()             { return 0.0; }
float photon_data_lifetime()        { return 0.0; }
vec3  photon_data_position()        { return vec3(0.0); }
float photon_data_isCollided()      { return 0.0; }
vec3  photon_data_velocity()        { return vec3(0.0); }
float photon_data_emitter_t()       { return iCustom0.z; }
float photon_data_emitter_age()     { return iCustom0.w; }
vec3  photon_data_emitter_position(){ return iCustom1.xyz; }
vec3  photon_data_emitter_velocity(){ return iCustom2.xyz; }
// per-point channels: (value at curr, value at next) mixed by the corner
float photon_data_point_t()         { return mix(iCustom3.x, iCustom3.y, aPos.x); }
float photon_data_point_life()      { return mix(iCustom3.z, iCustom3.w, aPos.x); }
vec3  photon_data_beam_direction()  { return vec3(0.0); }
float photon_data_beam_length()     { return 0.0; }

#elif defined(BEAM_INSTANCE)

float photon_data_random()          { return iCustom0.x; }
float photon_data_t()               { return iCustom0.y; }
float photon_data_age()             { return 0.0; }
float photon_data_lifetime()        { return 0.0; }
vec3  photon_data_position()        { return vec3(0.0); }
float photon_data_isCollided()      { return 0.0; }
vec3  photon_data_velocity()        { return vec3(0.0); }
float photon_data_emitter_t()       { return iCustom0.z; }
float photon_data_emitter_age()     { return iCustom0.w; }
vec3  photon_data_emitter_position(){ return iCustom1.xyz; }
vec3  photon_data_emitter_velocity(){ return iCustom2.xyz; }
float photon_data_point_t()         { return 0.0; }
float photon_data_point_life()      { return 0.0; }
// derived from the base beam attributes, nothing uploaded
vec3  photon_data_beam_direction()  { return iEnd - iStart.xyz; }
float photon_data_beam_length()     { return length(iEnd - iStart.xyz); }

#else

float photon_data_random()          { return 0.0; }
float photon_data_t()               { return 0.0; }
float photon_data_age()             { return 0.0; }
float photon_data_lifetime()        { return 0.0; }
vec3  photon_data_position()        { return vec3(0.0); }
float photon_data_isCollided()      { return 0.0; }
vec3  photon_data_velocity()        { return vec3(0.0); }
float photon_data_emitter_t()       { return 0.0; }
float photon_data_emitter_age()     { return 0.0; }
vec3  photon_data_emitter_position(){ return vec3(0.0); }
vec3  photon_data_emitter_velocity(){ return vec3(0.0); }
float photon_data_point_t()         { return 0.0; }
float photon_data_point_life()      { return 0.0; }
vec3  photon_data_beam_direction()  { return vec3(0.0); }
float photon_data_beam_length()     { return 0.0; }

#endif
