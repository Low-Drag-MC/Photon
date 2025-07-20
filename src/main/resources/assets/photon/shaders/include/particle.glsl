#ifdef PARTICLE_INSTANCE

layout(location = 0) in vec3 aPos;

layout(location = 1) in vec3 iPos;
layout(location = 2) in vec2 iSize;
layout(location = 3) in vec3 iScale;
layout(location = 4) in vec4 iRot;
layout(location = 5) in vec4 iColor;
layout(location = 6) in vec4 iUV;
layout(location = 7) in int iLight;

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
    data.LightUV = ivec2((iLight >> 16) & 0xFFFF, iLight & 0xFFFF);
    data.Normal = normalize(rotMat * vec3(0, 0, 1));

#elif defined(PARTICLE_MODEL_INSTANCE)

    mat3 rotMat = quatToMat(iRot);
    vec3 centeredPos = aPos - vec3(0.5);   // centered
    data.Position = (rotMat * (centeredPos * iScale)) + iPos;
    data.Color = vec4(iColor.rgb * aBrightness, iColor.a);
    data.UV = aUV;
    data.LightUV = ivec2((iLight >> 16) & 0xFFFF, iLight & 0xFFFF);
    data.Normal = normalize(rotMat * aNormal);

#else

    data.Position = Position;
    data.Color = Color;
    data.UV = UV0;
    data.LightUV = UV2;
    data.Normal = Normal;

#endif

    return data;
}
