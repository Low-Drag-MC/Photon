#ifdef PARTICLE_INSTANCE

layout(location = 0) in vec3 aPos;

layout(location = 1) in vec3 iPos;
layout(location = 2) in vec2 iSize;
layout(location = 3) in vec3 iScale;
layout(location = 4) in vec4 iRot;
layout(location = 5) in vec4 iColor;
layout(location = 6) in vec4 iUV;
layout(location = 7) in int iLight;

// additional GPU data: pulled from PhotonData by gl_InstanceID (see photon_data_*()); the raw
// vertex attributes above stop at iLight — custom shaders declare their own legacy channel
// attributes at location 8+. Record = 5 texels — MIRRORED FROM PhotonGpuChannels (keep in lockstep).
uniform samplerBuffer PhotonData;
#define PHOTON_DATA_TEXELS 5

// user custom data: pulled from PhotonCustomData by gl_InstanceID (see photon_custom_data()).
// Constant stride — MIRRORED FROM AdditionalGPUDataSetting.MAX_CUSTOM_DATA (keep in lockstep).
uniform samplerBuffer PhotonCustomData;
#define PHOTON_CUSTOM_TEXELS 4

#elif defined(PARTICLE_MODEL_INSTANCE)

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
#ifdef PHOTON_TANGENT
// The emitter's "Tangent" renderer setting is ON, so tangent vertex data is uploaded. Brightness moves
// into aNormal.w so the tangent costs no EXTRA attribute location — the mesh still uses locations 0..3,
// the per-instance attributes stay at 4..8 and PhotonGpuChannels.Kind.TILE_MODEL's channel base at 9.
layout(location = 2) in vec4 aNormal;  // xyz = normal, w = per-face shade brightness
layout(location = 3) in vec4 aTangent; // xyz = tangent (dP/du), w = handedness
#else
// Setting off: byte-for-byte the pre-tangent layout. Nothing is uploaded, nothing is generated, and a
// hand-written shader written against the old layout still links.
layout(location = 2) in vec3 aNormal;
layout(location = 3) in float aBrightness;
#endif

layout(location = 4) in vec3 iPos;
layout(location = 5) in vec3 iScale;
layout(location = 6) in vec4 iRot;
layout(location = 7) in vec4 iColor;
layout(location = 8) in int iLight;

// additional GPU data: pulled from PhotonData by gl_InstanceID; custom shaders declare their own
// legacy channel attributes at location 9+. Record = 5 texels — MIRRORED FROM PhotonGpuChannels.
uniform samplerBuffer PhotonData;
#define PHOTON_DATA_TEXELS 5

// user custom data: pulled from PhotonCustomData by gl_InstanceID (see photon_custom_data()).
// Constant stride — MIRRORED FROM AdditionalGPUDataSetting.MAX_CUSTOM_DATA (keep in lockstep).
uniform samplerBuffer PhotonCustomData;
#define PHOTON_CUSTOM_TEXELS 4

#elif defined(TRAIL_INSTANCE)

// one instance per trail segment; aPos.x in {0,1} selects curr/next, aPos.y in {-1,+1} the ribbon
// side. Point data is pulled from the PhotonPoints buffer texture (3 texels per point:
// pos+width / premultiplied color / u); each trail's block is padded with bitwise copies of its
// first/last point, so the c-1 / c+2 neighbor fetches stay in-trail and the endpoint-fallback
// equality tests hold.
layout(location = 0) in vec2 aPos;

layout(location = 1) in ivec2 iSeg;  // (point index of curr, packed light)
layout(location = 2) in vec2 iSegV;  // (v0, v1)

uniform samplerBuffer PhotonPoints;
// additional GPU data: pulled from PhotonData by gl_InstanceID; custom shaders declare their own
// legacy channel attributes at location 3+. Record = 4 texels — MIRRORED FROM PhotonGpuChannels.
uniform samplerBuffer PhotonData;
#define PHOTON_DATA_TEXELS 4

#elif defined(ARA_TRAIL_INSTANCE)

// one instance per AraTrail flat segment; aPos.x in {0,1} selects the segment's curr/next point
// (walk order, newest-first), aPos.y in {-1,+1} the +/- bitangent side. Point data is pulled from
// PhotonPoints (4 texels per point: pos+u / offset / normal / color); the per-point frames are
// CPU-computed, so there are no neighbor fetches and no padding.
layout(location = 0) in vec2 aPos;

layout(location = 1) in int iSeg;    // point index of curr
layout(location = 2) in vec2 iSegV;  // (vA, vB): cross-ribbon v of the +side / -side

uniform samplerBuffer PhotonPoints;
// additional GPU data: pulled from PhotonData by gl_InstanceID; custom shaders declare their own
// legacy channel attributes at location 3+. Record = 4 texels — MIRRORED FROM PhotonGpuChannels.
uniform samplerBuffer PhotonData;
#define PHOTON_DATA_TEXELS 4

#elif defined(ARA_TRAIL_TUBE_INSTANCE)

// one instance per AraTrail ring pair; aPos = (x: curr/next ring, y/z: section polygon vertex,
// w: uAround, all baked from the section config). Point data is pulled from PhotonPoints
// (4 texels per point: pos+thickness / bitangent+vCoord / tangent(UNNORMALIZED) / color).
layout(location = 0) in vec4 aPos;

layout(location = 1) in int iSeg;    // point index of curr

uniform samplerBuffer PhotonPoints;
// additional GPU data: pulled from PhotonData by gl_InstanceID; custom shaders declare their own
// legacy channel attributes at location 3+. Record = 4 texels — MIRRORED FROM PhotonGpuChannels.
uniform samplerBuffer PhotonData;
#define PHOTON_DATA_TEXELS 4

#elif defined(BEAM_INSTANCE)

// one instance per beam; aPos.x in {0,1} selects start/end, aPos.y in {-1,+1} the width side
layout(location = 0) in vec2 aPos;

layout(location = 1) in vec4 iStart; // xyz = start (camera-relative), w = half-width
layout(location = 2) in vec3 iEnd;
layout(location = 3) in vec4 iColor;
layout(location = 4) in vec4 iUV;    // (u0, v0, u1, v1), uv-scroll baked in
layout(location = 5) in int iLight;

// additional GPU data: pulled from PhotonData by gl_InstanceID; custom shaders declare their own
// legacy channel attributes at location 6+. Record = 3 texels — MIRRORED FROM PhotonGpuChannels.
uniform samplerBuffer PhotonData;
#define PHOTON_DATA_TEXELS 3

#else

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

#endif

/**
 * The value ParticleData.Tangent carries where there is genuinely nothing to report — only the CPU path,
 * whose DefaultVertexFormat.BLOCK has no tangent element and whose geometry comes from a vertex buffer
 * this shader cannot see behind. Deliberately an inert constant rather than a guess.
 */
#define PHOTON_NO_TANGENT vec4(1.0, 0.0, 0.0, 1.0)

/** Any unit vector perpendicular to n, for a dP/du that collapsed (a zero-length segment). */
vec3 photon_any_perpendicular(vec3 n) {
    vec3 axis = abs(n.x) > 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    return normalize(cross(axis, n));
}

/**
 * normalize(v), falling back to an arbitrary perpendicular when v collapses. Callers pass dP/du already
 * scaled by its uv delta, so v is a product of two possibly-small numbers — the threshold only has to
 * reject a true zero, not "small".
 */
vec3 photon_safe_tangent(vec3 v, vec3 n) {
    return dot(v, v) > 1.0e-30 ? normalize(v) : photon_any_perpendicular(n);
}

/**
 * The tangent frame's handedness: +1 when cross(N, T) already points along dP/dv, -1 when the uv layout
 * is mirrored. n and b need not be unit — only the sign of the triple product matters.
 */
float photon_handedness(vec3 n, vec3 t, vec3 b) {
    return dot(cross(n, t), b) < 0.0 ? -1.0 : 1.0;
}

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
    // surface tangent: xyz = dP/du, w = handedness (bitangent = cross(Normal, Tangent.xyz) * w).
    // Model instancing reads it from the aTangent attribute (the emitter's "Tangent" renderer setting).
    // The other instanced paths build their geometry here in the vertex shader, so dP/du and dP/dv are
    // exactly known and the frame is derived from values already in registers — no extra fetch, no extra
    // attribute, no varying unless a node actually reads it. Only the CPU path has nothing to report.
    vec4 Tangent;
    // object/local space: the mesh vertex BEFORE the per-instance rotate/scale/translate that getParticleData
    // bakes into Position. Meaningful on the instanced paths (model instancing = mesh-local; billboards =
    // centered quad coord); degenerates to Position/Normal (world) on the CPU/trail/beam paths.
    vec3 ObjectPosition;
    vec3 ObjectNormal;
    // The mesh-local tangent, xyz + handedness — the object-space counterpart of Tangent, carrying its
    // own w so a shader reading only this still has a complete frame.
    vec4 ObjectTangent;
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
    // The quad's uv is axis-aligned: u runs along local +X, v along local +Y. Which WAY each runs is read
    // off the packed frame rather than assumed — the uploader packs iUV as (u0, v1, u1, v0) ("flip v"), so
    // v normally decreases with +Y, and a mirrored sprite frame reverses either axis.
    float uDir = (iUV.z < iUV.x) ? -1.0 : 1.0;
    float vDir = (iUV.w < iUV.y) ? -1.0 : 1.0;
    // cross(N, T) = uDir * +Y while dP/dv = vDir * +Y, so the handedness is just their product.
    data.Tangent = vec4(normalize(rotMat * vec3(uDir, 0, 0)), uDir * vDir);
    // object space: the local billboard corner (aPos.xy = centered quad coord), facing +z pre-rotation
    data.ObjectPosition = aPos;
    data.ObjectNormal = vec3(0.0, 0.0, 1.0);
    data.ObjectTangent = vec4(uDir, 0.0, 0.0, uDir * vDir);

#elif defined(PARTICLE_MODEL_INSTANCE)

    mat3 rotMat = quatToMat(iRot);
    // aPos is already in centered model space (PhotonMesh convention)
    data.Position = (rotMat * (aPos * iScale)) + iPos;
    data.UV = aUV;
    // vanilla UV2 order is (block, sky); java packs sky<<20 | block<<4
    data.LightUV = ivec2(iLight & 0xFFFF, (iLight >> 16) & 0xFFFF);
    // object space: the mesh's own centered model-space vertex + normal
    data.ObjectPosition = aPos;
#ifdef PHOTON_TANGENT
    data.Color = vec4(iColor.rgb * aNormal.w, iColor.a);
    data.Normal = normalize(rotMat * aNormal.xyz);
    data.ObjectNormal = aNormal.xyz;
    // Like Normal above, the tangent is ROTATED but not scaled — iScale is deliberately left out of
    // both (a long-standing simplification; the CPU path uses a real normal matrix). The frame therefore
    // lives in the mesh's own unscaled space and keeps the mesh's own handedness. Flipping w for a
    // negative-determinant iScale would only be right if the tangent had been mirrored along with it;
    // doing it here inverted the bitangent instead. (glTF node mirroring IS handled, in GltfMeshParser,
    // where the tangent really is transformed by the mirroring matrix.)
    data.Tangent = vec4(normalize(rotMat * aTangent.xyz), aTangent.w);
    data.ObjectTangent = aTangent;
#else
    data.Color = vec4(iColor.rgb * aBrightness, iColor.a);
    data.Normal = normalize(rotMat * aNormal);
    data.ObjectNormal = aNormal;
    // the emitter's Tangent setting is off, so no tangent data was uploaded
    data.Tangent = PHOTON_NO_TANGENT;
    data.ObjectTangent = data.Tangent;
#endif

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
    // u runs along the trail, v across it — both scaled by their own uv delta so a reversed walk order
    // reverses the frame with it. Every term here is already in registers.
    vec3 tDir = photon_safe_tangent(seg * (uN - uC), data.Normal);
    data.Tangent = vec4(tDir, photon_handedness(data.Normal, tDir, avg * (iSegV.x - iSegV.y)));

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
    // u runs along the ribbon (t0C.w -> t0N.w), v across it along the CPU-computed offset
    vec3 tDir = photon_safe_tangent((t0N.xyz - t0C.xyz) * (t0N.w - t0C.w), data.Normal);
    data.Tangent = vec4(tDir, photon_handedness(data.Normal, tDir, off * (iSegV.x - iSegV.y)));
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
    vec3 secBitangent = mix(t1C.xyz, t1N.xyz, aPos.x);
    vec3 secTangent = mix(texelFetch(PhotonPoints, c * 4 + 2).xyz,
                          texelFetch(PhotonPoints, (c + 1) * 4 + 2).xyz, aPos.x);
    vec3 off = (aPos.y * secBitangent + aPos.z * secTangent) * mix(t0C.w, t0N.w, aPos.x);
    data.Position = mix(t0C.xyz, t0N.xyz, aPos.x) + off;
    data.Normal = off;
    // NOTE the swap versus the flat ribbon: here u goes AROUND the ring (aPos.w) and v runs along the
    // curve. (aPos.y, aPos.z) IS this vertex's position on the section polygon, so travelling around the
    // ring is that point rotated 90 degrees in the section basis — exact for the default circular
    // section, correctly signed for any counter-clockwise one, and off the same two texels `off` uses.
    vec3 tDir = photon_safe_tangent(-aPos.z * secBitangent + aPos.y * secTangent, data.Normal);
    data.Tangent = vec4(tDir, photon_handedness(data.Normal, tDir,
                                                (t0N.xyz - t0C.xyz) * (t1N.w - t1C.w)));
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
    // u runs start -> end along the beam, v across its width along beamN; both scaled by their own uv
    // delta so a reversed (uv-scrolled) frame reverses the tangent frame with it
    vec3 tDir = photon_safe_tangent(beamDir * (iUV.z - iUV.x), data.Normal);
    data.Tangent = vec4(tDir, photon_handedness(data.Normal, tDir, beamN * (iUV.w - iUV.y)));

#else

    data.Position = Position;
    data.Color = Color;
    data.UV = UV0;
    data.LightUV = UV2;
    data.Normal = Normal;
    data.Tangent = PHOTON_NO_TANGENT; // DefaultVertexFormat.BLOCK has no tangent element

#endif

    // Paths with no meaningful object space (CPU quads, trails, beams) degenerate object -> world.
#if !defined(PARTICLE_INSTANCE) && !defined(PARTICLE_MODEL_INSTANCE)
    data.ObjectPosition = data.Position;
    data.ObjectNormal = data.Normal;
    data.ObjectTangent = data.Tangent;
#endif

    return data;
}

// ---------------------------------------------------------------------------
// object <-> (camera-relative) world matrices.
//
// Photon has NO per-draw model matrix: getParticleData() already yields camera-relative WORLD vertices,
// so this pipeline's ModelViewMat is the VIEW matrix. The object->world transform lives in the
// per-instance GPU expansion (quatToMat(iRot) / iScale / iPos / iSize) instead. These accessors expose
// that expansion as an affine matrix pair so the shadergraph's object-space seams
// (PhotonShaderCompiler.objectToViewMatrix/viewToObjectMatrix, read by the Transform and View Direction
// nodes) can mean the mesh's own local space -- the same thing ParticleData.ObjectPosition/ObjectNormal
// and the Position/Normal nodes' "Object" outputs mean. MIRRORED FROM the getParticleData() expansions
// above (keep in lockstep).
//
// Paths with no meaningful object space (CPU quads, trails, beams) return identity, matching the
// object -> world degeneration getParticleData() applies there.
//
// Deliberately functions rather than ParticleData fields: a graph that never converts spaces must not
// pay for the matrix build (and its inverse) on every vertex.
// ---------------------------------------------------------------------------

/** Per-component reciprocal that collapses a degenerate (zero) axis to 0 rather than propagating inf/NaN
 *  -- a zero scale/size is a legitimate particle state (spawn/despawn keyframes). */
vec3 photon_safeRcp(vec3 v) {
    bvec3 ok = greaterThan(abs(v), vec3(1e-6));
    return vec3(ok.x ? 1.0 / v.x : 0.0, ok.y ? 1.0 / v.y : 0.0, ok.z ? 1.0 / v.z : 0.0);
}

/** object -> camera-relative world (affine). */
mat4 photon_objectToWorld() {
#if defined(PARTICLE_INSTANCE)
    // world = iScale * (quatToMat(iRot) * vec3(aPos.xy * iSize, aPos.z)) + iPos
    //       = diag(iScale) . R . diag(iSize.x, iSize.y, 1) . aPos + iPos
    // NOTE the asymmetry: iSize applies BEFORE the rotation, iScale AFTER (mirrors getParticleData()).
    // M . diag(d) scales COLUMN i by d[i]; diag(s) . M scales every column componentwise by s.
    mat3 R = quatToMat(iRot);
    mat3 L = mat3(R[0] * iSize.x * iScale, R[1] * iSize.y * iScale, R[2] * iScale);
    return mat4(vec4(L[0], 0.0), vec4(L[1], 0.0), vec4(L[2], 0.0), vec4(iPos, 1.0));
#elif defined(PARTICLE_MODEL_INSTANCE)
    // world = quatToMat(iRot) * (aPos * iScale) + iPos = R . diag(iScale) . aPos + iPos
    mat3 R = quatToMat(iRot);
    mat3 L = mat3(R[0] * iScale.x, R[1] * iScale.y, R[2] * iScale.z);
    return mat4(vec4(L[0], 0.0), vec4(L[1], 0.0), vec4(L[2], 0.0), vec4(iPos, 1.0));
#else
    return mat4(1.0);
#endif
}

/** camera-relative world -> object (the exact inverse of photon_objectToWorld()). The rotation is a unit
 *  quaternion on every path that writes iRot (JOML, normalized on the CPU), so transpose == inverse. */
mat4 photon_worldToObject() {
#if defined(PARTICLE_INSTANCE)
    // L = diag(iScale) . R . diag(size)  ->  L^-1 = diag(1/size) . R^T . diag(1/iScale)
    mat3 Rt = transpose(quatToMat(iRot));
    vec3 rs = photon_safeRcp(iScale);
    vec3 rz = photon_safeRcp(vec3(iSize, 1.0));
    mat3 Li = mat3(Rt[0] * rs.x * rz, Rt[1] * rs.y * rz, Rt[2] * rs.z * rz);
    return mat4(vec4(Li[0], 0.0), vec4(Li[1], 0.0), vec4(Li[2], 0.0), vec4(-(Li * iPos), 1.0));
#elif defined(PARTICLE_MODEL_INSTANCE)
    // L = R . diag(iScale)  ->  L^-1 = diag(1/iScale) . R^T
    mat3 Rt = transpose(quatToMat(iRot));
    vec3 rs = photon_safeRcp(iScale);
    mat3 Li = mat3(Rt[0] * rs, Rt[1] * rs, Rt[2] * rs);
    return mat4(vec4(Li[0], 0.0), vec4(Li[1], 0.0), vec4(Li[2], 0.0), vec4(-(Li * iPos), 1.0));
#else
    return mat4(1.0);
#endif
}

// ---------------------------------------------------------------------------
// additional GPU data accessors — MIRRORED FROM PhotonGpuChannels packing (keep in lockstep).
// Instanced variants pull the packed record from the PhotonData buffer texture by gl_InstanceID
// (VERTEX STAGE ONLY — gl_InstanceID is undefined in the fragment stage; the shadergraph routes
// these through a varying). Channels a variant doesn't support (and the whole CPU path) read 0.
// ---------------------------------------------------------------------------
#if defined(PARTICLE_INSTANCE) || defined(PARTICLE_MODEL_INSTANCE) || defined(TRAIL_INSTANCE) \
 || defined(ARA_TRAIL_INSTANCE) || defined(ARA_TRAIL_TUBE_INSTANCE) || defined(BEAM_INSTANCE)
#define PHOTON_DATA_SLOT(slot) texelFetch(PhotonData, gl_InstanceID * PHOTON_DATA_TEXELS + (slot))
#endif

#if defined(PARTICLE_INSTANCE) || defined(PARTICLE_MODEL_INSTANCE)

float photon_data_random()          { return PHOTON_DATA_SLOT(0).x; }
float photon_data_t()               { return PHOTON_DATA_SLOT(0).y; }
float photon_data_age()             { return PHOTON_DATA_SLOT(0).z; }
float photon_data_lifetime()        { return PHOTON_DATA_SLOT(0).w; }
vec3  photon_data_position()        { return PHOTON_DATA_SLOT(1).xyz; }
float photon_data_isCollided()      { return PHOTON_DATA_SLOT(1).w; }
vec3  photon_data_velocity()        { return PHOTON_DATA_SLOT(2).xyz; }
float photon_data_emitter_t()       { return PHOTON_DATA_SLOT(2).w; }
float photon_data_emitter_age()     { return PHOTON_DATA_SLOT(3).x; }
vec3  photon_data_emitter_position(){ return PHOTON_DATA_SLOT(3).yzw; }
vec3  photon_data_emitter_velocity(){ return PHOTON_DATA_SLOT(4).xyz; }
float photon_data_point_t()         { return 0.0; }
float photon_data_point_life()      { return 0.0; }
vec3  photon_data_beam_direction()  { return vec3(0.0); }
float photon_data_beam_length()     { return 0.0; }

#elif defined(TRAIL_INSTANCE) || defined(ARA_TRAIL_INSTANCE) || defined(ARA_TRAIL_TUBE_INSTANCE)

float photon_data_random()          { return PHOTON_DATA_SLOT(0).x; }
float photon_data_t()               { return PHOTON_DATA_SLOT(0).y; }
float photon_data_age()             { return 0.0; }
float photon_data_lifetime()        { return 0.0; }
vec3  photon_data_position()        { return vec3(0.0); }
float photon_data_isCollided()      { return 0.0; }
vec3  photon_data_velocity()        { return vec3(0.0); }
float photon_data_emitter_t()       { return PHOTON_DATA_SLOT(0).z; }
float photon_data_emitter_age()     { return PHOTON_DATA_SLOT(0).w; }
vec3  photon_data_emitter_position(){ return PHOTON_DATA_SLOT(1).xyz; }
vec3  photon_data_emitter_velocity(){ return PHOTON_DATA_SLOT(2).xyz; }
// per-point channels: (value at curr, value at next) mixed by the corner
float photon_data_point_t()         { return mix(PHOTON_DATA_SLOT(3).x, PHOTON_DATA_SLOT(3).y, aPos.x); }
float photon_data_point_life()      { return mix(PHOTON_DATA_SLOT(3).z, PHOTON_DATA_SLOT(3).w, aPos.x); }
vec3  photon_data_beam_direction()  { return vec3(0.0); }
float photon_data_beam_length()     { return 0.0; }

#elif defined(BEAM_INSTANCE)

float photon_data_random()          { return PHOTON_DATA_SLOT(0).x; }
float photon_data_t()               { return PHOTON_DATA_SLOT(0).y; }
float photon_data_age()             { return 0.0; }
float photon_data_lifetime()        { return 0.0; }
vec3  photon_data_position()        { return vec3(0.0); }
float photon_data_isCollided()      { return 0.0; }
vec3  photon_data_velocity()        { return vec3(0.0); }
float photon_data_emitter_t()       { return PHOTON_DATA_SLOT(0).z; }
float photon_data_emitter_age()     { return PHOTON_DATA_SLOT(0).w; }
vec3  photon_data_emitter_position(){ return PHOTON_DATA_SLOT(1).xyz; }
vec3  photon_data_emitter_velocity(){ return PHOTON_DATA_SLOT(2).xyz; }
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

// ---------------------------------------------------------------------------
// user custom data accessor — one vec4 per stream, pulled from PhotonCustomData by gl_InstanceID
// with a config-independent constant stride (PHOTON_CUSTOM_TEXELS). Streams beyond what the emitter
// defines, unsupported kinds, and the whole CPU path read vec4(0). (VERTEX STAGE ONLY — the
// shadergraph routes it through a varying.)
// ---------------------------------------------------------------------------
#if defined(PARTICLE_INSTANCE) || defined(PARTICLE_MODEL_INSTANCE)
vec4 photon_custom_data(int i) {
    return (i < 0 || i >= PHOTON_CUSTOM_TEXELS) ? vec4(0.0)
        : texelFetch(PhotonCustomData, gl_InstanceID * PHOTON_CUSTOM_TEXELS + i);
}
#else
vec4 photon_custom_data(int i) { return vec4(0.0); }
#endif
