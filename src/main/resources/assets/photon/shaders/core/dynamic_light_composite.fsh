#version 330

// Full resolution: upsample the light buffer (depth-aware), estimate the albedo from the frame
// already lit by vanilla (or a shader pack), and add albedo * light. The haze goes on later, over the particles.

#moj_import <minecraft:fog.glsl>
#moj_import <photon:dynamic_light_depth.glsl>

uniform sampler2D SceneColorSampler;
uniform sampler2D IrradianceSampler;
// the frame's lightmap: its darkest texel is the least light vanilla gives a surface
uniform sampler2D LightmapSampler;

out vec4 fragColor;

// 26.1's fog: a spherical environmental ramp against a cylindrical render-distance one, whichever is denser
float fogValue(vec3 relative) {
    if (PackParams.z < 0.5) return 0.0;
    float spherical = fog_spherical_distance(relative);
    float cylindrical = fog_cylindrical_distance(relative);
    return total_fog_value(spherical, cylindrical, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 scene = texelFetch(SceneColorSampler, pixel, 0).rgb;
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    bool afterPack = PackParams.x > 0.5;
    // the hand under a pack is drawn with its own projection; the volume view shows the haze pass alone
    if (depth >= 1.0 || (afterPack && depth < 0.56) || DebugMode == 6) {
        fragColor = vec4(DebugMode == 0 ? scene : vec3(0.0), 1.0);
        return;
    }

    vec3 p = viewPos((vec2(pixel) + 0.5) / ScreenSize, depth);
    vec4 light = upsample(IrradianceSampler, pixel, -p.z, 1e9);

    mat3 viewToWorld = mat3(IViewMat);
    vec3 nw = normalize(viewToWorld * reconstructNormal(pixel, p));
    vec3 an = abs(nw);
    float major = max(an.x, max(an.y, an.z));
    // undo vanilla's per-face shading, which is baked into the colour we estimate the albedo from
    float shade = 1.0;
    if (!afterPack && major > 0.985) {
        shade = an.y == major ? (nw.y > 0.0 ? FaceShade.y : FaceShade.x) : an.z == major ? FaceShade.z : FaceShade.w;
    }

    float fog = fogValue((IViewMat * vec4(p, 1.0)).xyz);
    vec3 unfogged = fog < 0.999 ? clamp((scene - FogColor.rgb * fog) / (1.0 - fog), 0.0, 1.0) : scene;
    // a pixel was lit at least by its own brightness over the brightest plausible albedo
    float lumaBound = dot(unfogged, vec3(0.2126, 0.7152, 0.0722)) / LightParams.x;
    float floorLuma = dot(texelFetch(LightmapSampler, ivec2(0), 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float ambient = afterPack ? PackParams.y : max(light.a, floorLuma) * shade;
    vec3 albedo = min(unfogged / max(max(ambient, lumaBound), 1e-4), vec3(1.0));
    vec3 added = albedo * light.rgb * (1.0 - fog);

    if (DebugMode == 1) {
        fragColor = vec4(1.0 - exp(-light.rgb), 1.0);
    } else if (DebugMode == 2) {
        fragColor = vec4(nw * 0.5 + 0.5, 1.0);
    } else if (DebugMode == 3 || DebugMode == 5) {
        fragColor = vec4(light.rgb, 1.0);
    } else if (DebugMode == 4) {
        fragColor = vec4(albedo, 1.0);
    } else {
        fragColor = vec4(1.0 - (1.0 - scene) * exp(-added), 1.0);
    }
}
