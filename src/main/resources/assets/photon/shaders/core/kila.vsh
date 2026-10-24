#version 330 core

#moj_import <fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <photon:particle.glsl>
#moj_import <photon:kila_lib.glsl>

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out vec4 lightColor;
out vec3 kilaViewPos;
out vec3 kilaViewNormal;
#if defined(KILA_WORLD_UV) || defined(KILA_REL_POS)
// eye-relative, so the fragment adds the camera back at full precision
out vec3 kilaRelPos;
#endif

// drivable parameters, each base + scale * source, resolved per vertex — ⚠️ groups MIRRORED FROM KilaParam
#ifdef KILA_P0
out vec4 kilaP0;
#endif
#ifdef KILA_P1
out vec4 kilaP1;
#endif
#ifdef KILA_P2
out vec4 kilaP2;
#endif
#ifdef KILA_P3
out vec4 kilaP3;
#endif
#ifdef KILA_P4
out vec4 kilaP4;
#endif
#ifdef KILA_P5
out vec4 kilaP5;
#endif
#ifdef KILA_P6
out vec4 kilaP6;
#endif

// ⚠️ codes MIRRORED FROM KilaDriver.Source. The records are read only when a driver asks for them (KILA_DATA,
// KILA_CUSTOM_DATA): a record buffer the program reads must be declared, and a declared one bound.
float kila_source(int code, ParticleData data) {
    if (code == 0) return 0.0;
    if (code <= 4) return data.Color[code - 1];
    if (KilaPreview >= 0.0) return KilaPreview;
#ifdef KILA_DATA
    if (code == 5) return photon_data_t();
    if (code == 6) return photon_data_random();
    if (code == 7) return photon_data_point_life();
    if (code == 8) return photon_data_point_t();
#endif
#ifdef KILA_CUSTOM_DATA
    if (code >= 16) {
        int c = code - 16;
        return photon_custom_data(c / 4)[c % 4];
    }
#endif
    return 0.0;
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
// PHOTON_INSTANCED comes from photon:particle.glsl
#ifdef PHOTON_INSTANCED
    // editor scenes extract eye-relative; ModelOffset carries the facing-eye -> render-origin delta back
    // (zero in-world) — as core/particle.vsh
    position += ModelOffset;
#endif
    // the editor's vertices are world space, the world's camera-relative: from the eye, they are the same
    vec3 eye = kila_eye();
#ifdef KILA_WORLD_UV
    vec3 world = position - eye + KilaCameraPos;
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
    float push = dot(textureLod(KILA_TEX_VERTEX, kila_wrap(offsetUv, flags), 0.0), KilaVertexChannel);
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
    // along the view ray: only the depth it is tested at moves. In view space, where the eye is at the origin —
    // the vertices are not camera-relative in an editor scene.
    float away = length(viewPos.xyz);
    vec3 drawn = away > 1e-4 ? viewPos.xyz - viewPos.xyz / away * min(KilaCameraOffset, away - 0.1) : viewPos.xyz;
    gl_Position = ProjMat * vec4(drawn, 1.0);
#else
    gl_Position = ProjMat * viewPos;
#endif
    kilaViewPos = viewPos.xyz;
    kilaViewNormal = mat3(ModelViewMat) * data.Normal;
#if defined(KILA_WORLD_UV) || defined(KILA_REL_POS)
    kilaRelPos = position - eye;
#endif

    sphericalVertexDistance = fog_spherical_distance(position - eye);
    cylindricalVertexDistance = fog_cylindrical_distance(position - eye);
    texCoord0 = data.UV;
    vertexColor = data.Color;
    // the lightmap is a rendered texture: sampled as vanilla's particle stage does, not texel-fetched
    lightColor = sample_lightmap(Sampler2, data.LightUV);
}
