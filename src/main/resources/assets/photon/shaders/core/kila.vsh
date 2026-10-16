#version 330 core

#moj_import <fog.glsl>
#moj_import <photon:particle.glsl>
#moj_import <photon:kila_lib.glsl>

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
// below 0 normally; the thumbnail feeds it to every particle-data source, so it animates
uniform float KilaPreview;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out vec4 lightColor;
out vec3 kilaViewPos;
out vec3 kilaViewNormal;
#if defined(KILA_WORLD_UV) || defined(KILA_REL_POS)
// camera-relative, so the fragment adds the camera back at full precision
out vec3 kilaRelPos;
#endif
#ifdef KILA_WORLD_UV
uniform vec3 KilaCameraPos;
#endif
#ifdef KILA_CAMERA_OFFSET
// blocks the quad is drawn towards the camera
uniform float KilaCameraOffset;
#endif

// drivable parameters, each base + scale * source, resolved per vertex — ⚠️ groups MIRRORED FROM KilaParam
#ifdef KILA_P0
uniform vec4 KilaBase0;
uniform vec4 KilaScale0;
uniform ivec4 KilaSrc0;
out vec4 kilaP0;
#endif
#ifdef KILA_P1
uniform vec4 KilaBase1;
uniform vec4 KilaScale1;
uniform ivec4 KilaSrc1;
out vec4 kilaP1;
#endif
#ifdef KILA_P2
uniform vec4 KilaBase2;
uniform vec4 KilaScale2;
uniform ivec4 KilaSrc2;
out vec4 kilaP2;
#endif
#ifdef KILA_P3
uniform vec4 KilaBase3;
uniform vec4 KilaScale3;
uniform ivec4 KilaSrc3;
out vec4 kilaP3;
#endif
#ifdef KILA_P4
uniform vec4 KilaBase4;
uniform vec4 KilaScale4;
uniform ivec4 KilaSrc4;
out vec4 kilaP4;
#endif
#ifdef KILA_P5
uniform vec4 KilaBase5;
uniform vec4 KilaScale5;
uniform ivec4 KilaSrc5;
out vec4 kilaP5;
#endif
#ifdef KILA_P6
uniform vec4 KilaBase6;
uniform vec4 KilaScale6;
uniform ivec4 KilaSrc6;
out vec4 kilaP6;
#endif

#ifdef KILA_VERTEX_OFFSET
uniform vec4 KilaVertexST;
uniform vec4 KilaVertexAnim;
uniform vec4 KilaVertexPolar;
uniform ivec4 KilaVertexOpts;
#ifdef KILA_VERTEX_NOISE
uniform vec4 KilaVertexNoise;
#else
uniform vec4 KilaVertexChannel;
#endif
// x = 1 pushes along the axis in yzw instead of the normal
uniform vec4 KilaVertexDir;
// x = 1 remaps the texture to -1..1, y = width of the fade towards v = 0 and v = 1 (0 = none)
uniform vec4 KilaVertexShape;
#endif

// ⚠️ codes MIRRORED FROM KilaDriver.Source
float kila_source(int code, ParticleData data) {
    if (code == 0) return 0.0;
    if (code <= 4) return data.Color[code - 1];
    if (KilaPreview >= 0.0) return KilaPreview;
    if (code == 5) return photon_data_t();
    if (code == 6) return photon_data_random();
    if (code == 7) return photon_data_point_life();
    if (code == 8) return photon_data_point_t();
    int c = code - 16;
    return photon_custom_data(c / 4)[c % 4];
}

vec4 kila_drive(vec4 base, vec4 scale, ivec4 src, ParticleData data) {
    return base + scale * vec4(kila_source(src.x, data), kila_source(src.y, data),
            kila_source(src.z, data), kila_source(src.w, data));
}

void main() {
    ParticleData data = getParticleData();

#ifdef KILA_P0
    kilaP0 = kila_drive(KilaBase0, KilaScale0, KilaSrc0, data);
#endif
#ifdef KILA_P1
    kilaP1 = kila_drive(KilaBase1, KilaScale1, KilaSrc1, data);
#endif
#ifdef KILA_P2
    kilaP2 = kila_drive(KilaBase2, KilaScale2, KilaSrc2, data);
#endif
#ifdef KILA_P3
    kilaP3 = kila_drive(KilaBase3, KilaScale3, KilaSrc3, data);
#endif
#ifdef KILA_P4
    kilaP4 = kila_drive(KilaBase4, KilaScale4, KilaSrc4, data);
#endif
#ifdef KILA_P5
    kilaP5 = kila_drive(KilaBase5, KilaScale5, KilaSrc5, data);
#endif
#ifdef KILA_P6
    kilaP6 = kila_drive(KilaBase6, KilaScale6, KilaSrc6, data);
#endif

    vec3 position = data.Position;
#ifdef KILA_WORLD_UV
    vec3 world = position + KilaCameraPos;
#else
    vec3 world = vec3(0.0);
#endif
#ifdef KILA_VERTEX_OFFSET
    // there is no screen yet: a screen-space slot reads the mesh uv here
    int flags = KilaVertexOpts.x;
    vec2 offsetUv = kila_uv(kila_base_uv(flags, data.UV, data.UV, world), KilaVertexST, KilaVertexAnim, KilaVertexPolar,
            flags, vec2(0.0), vec2(0.0));
#ifdef KILA_VERTEX_NOISE
    float push = kila_noise(offsetUv, KilaVertexNoise);
#else
    float push = dot(textureLod(KILA_TEX_VERTEX, offsetUv, 0.0), KilaVertexChannel);
#endif
    if (KilaVertexShape.x > 0.5) push = push * 2.0 - 1.0;
    if (KilaVertexShape.y > 0.0) {
        push *= smoothstep(0.0, KilaVertexShape.y, data.UV.y) * smoothstep(0.0, KilaVertexShape.y, 1.0 - data.UV.y);
    }
    vec3 direction = KilaVertexDir.x > 0.5 ? KilaVertexDir.yzw : data.Normal;
    position += direction * push * kilaP4.w;
#endif

    vec4 viewPos = ModelViewMat * vec4(position, 1.0);
#ifdef KILA_CAMERA_OFFSET
    // along the view ray: only the depth it is tested at moves
    float away = length(position);
    vec3 drawn = away > 1e-4 ? position - position / away * min(KilaCameraOffset, away - 0.1) : position;
    gl_Position = ProjMat * ModelViewMat * vec4(drawn, 1.0);
#else
    gl_Position = ProjMat * viewPos;
#endif
    kilaViewPos = viewPos.xyz;
    kilaViewNormal = mat3(ModelViewMat) * data.Normal;
#if defined(KILA_WORLD_UV) || defined(KILA_REL_POS)
    kilaRelPos = position;
#endif

    vertexDistance = fog_distance(position, FogShape);
    texCoord0 = data.UV;
    vertexColor = data.Color;
    lightColor = texelFetch(Sampler2, data.LightUV / 16, 0);
}
