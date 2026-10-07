// Dynamic lights on a particle or surface. A billboard has no normal, so light comes from every direction.
// Shadowed by the lights' visibility maps where they have one. Java side: LitParticles.

#moj_import <photon:dynamic_light.glsl>

// x = 1 enables, y = intensity
uniform vec4 LitParticleParams;

// The light reaching viewPos, each light weighted by how square it falls on viewNormal (unit, view space);
// a zero normal takes light from every direction. wrap > 0 lets light reach past the terminator.
vec3 photon_surface_light(vec3 viewPos, vec3 viewNormal, float wrap) {
    if (LitParticleParams.x < 0.5) return vec3(0.0);
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
    // intensity x the colour and keeps the light's hue instead of clipping to white
    float peak = max(sum.r, max(sum.g, sum.b));
    if (peak <= 0.0) return vec3(0.0);
    return sum * (LitParticleParams.y * (1.0 - exp(-peak)) / peak);
}

vec3 photon_particle_light(vec3 viewPos) {
    return photon_surface_light(viewPos, vec3(0.0), 0.0);
}
