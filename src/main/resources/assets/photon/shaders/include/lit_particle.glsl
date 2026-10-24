// Dynamic lights on a particle or surface. A billboard has no normal, so light comes from every direction.
// Shadowed by the lights' visibility maps where they have one. Java side: LitParticles.
//
// ⚠️ Everything hangs off PHOTON_LIT, which PhotonPipelines adds only for a lit material, a shader graph with a
// Dynamic Light node, or a custom shader that imports this file. Without the define this file declares NOTHING —
// a declared buffer must be bound at every draw, so an unlit material must not carry the lights. See
// PhotonPipelines.LIT_DEFINE. The functions below exist either way, so the call sites stay the same.

#ifdef PHOTON_LIT

#moj_import <photon:dynamic_light.glsl>

// The light reaching viewPos, each light weighted by how square it falls on viewNormal (unit, view space);
// a zero normal takes light from every direction. wrap > 0 lets light reach past the terminator. Saturated so the
// boost tops out at 1x the light's colour per channel; the caller scales by its own intensity.
vec3 photon_surface_light(vec3 viewPos, vec3 viewNormal, float wrap) {
    if (!photon_hasLights()) return vec3(0.0);
    bool facing = dot(viewNormal, viewNormal) > 0.0;
    ivec2 cluster = photon_cluster(ivec2(gl_FragCoord.xy), -viewPos.z);
    vec3 sum = vec3(0.0);
    for (int k = 0; k < cluster.y; k++) {
        int light = photon_clusterLight(cluster.x + k);
        vec4 t0, t1, t2, t3;
        photon_fetchLight(light, t0, t1, t2, t3);
        vec3 toLight = t0.xyz - viewPos;
        float d2 = dot(toLight, toLight);
        float attenuation = photon_attenuation(d2, t0.w);
        if (attenuation <= 0.0) continue;
        vec3 l = toLight * inversesqrt(max(d2, 1e-6));
        attenuation *= photon_spot(l, t1, t2, t3);
        if (facing) attenuation *= max((dot(viewNormal, l) + wrap) / (1.0 + wrap), 0.0);
        if (attenuation <= 0.0) continue;
        attenuation *= photon_visibility(t3, -toLight, sqrt(d2));
        sum += t1.rgb * attenuation;
    }
    // inverse square is unbounded next to a light: saturate by the brightest channel, so the boost caps at
    // the colour and keeps the light's hue instead of clipping to white
    float peak = max(sum.r, max(sum.g, sum.b));
    if (peak <= 0.0) return vec3(0.0);
    return sum * ((1.0 - exp(-peak)) / peak);
}

#else

vec3 photon_surface_light(vec3 viewPos, vec3 viewNormal, float wrap) {
    return vec3(0.0);
}

#endif

vec3 photon_particle_light(vec3 viewPos) {
    return photon_surface_light(viewPos, vec3(0.0), 0.0);
}
