#version 150

// Full resolution: upsample the light buffer (depth-aware), estimate the albedo from the frame
// already lit by vanilla (or a shader pack), and add albedo * light.

#moj_import <photon:dynamic_light_depth.glsl>

uniform sampler2D SceneColorSampler;
uniform sampler2D IrradianceSampler;

uniform mat4 IViewMat;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
uniform vec3 LightmapFloor;
uniform vec4 FaceShade;     // down, up, north/south, east/west
uniform float AlbedoMax;
uniform vec4 PackParams;    // x: over a shader pack's final image, y: assumed darkest pack lighting
uniform int DebugMode;
uniform ivec2 ViewportOrigin;  // where the snapshot sits in the target being drawn (an editor scene's viewport)

out vec4 fragColor;

// bilinear over the four nearest light texels, each weighted by how close its depth is to ours
vec4 upsample(ivec2 pixel, float z) {
    vec2 lp = (vec2(pixel) + 0.5) * ResolutionScale - 0.5;
    ivec2 base = ivec2(floor(lp));
    vec2 f = lp - vec2(base);
    ivec2 size = textureSize(IrradianceSampler, 0);
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    vec4 nearest = vec4(0.0);
    float nearestDz = 1e9;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            ivec2 q = clamp(base + ivec2(i, j), ivec2(0), size - 1);
            float zq = lightTexelDepth(q);
            vec4 value = texelFetch(IrradianceSampler, q, 0);
            float dz = zq < 0.0 ? 1e9 : abs(zq - z);
            if (dz < nearestDz) {
                nearestDz = dz;
                nearest = value;
            }
            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y) * exp(-dz / (0.02 * z + 0.03));
            sum += value * w;
            weightSum += w;
        }
    }
    return weightSum > 1e-4 ? sum / weightSum : nearest;
}

float fogValue(vec3 relative) {
    float dist = FogShape == 0 ? length(relative) : max(length(relative.xz), abs(relative.y));
    if (dist <= FogStart) return 0.0;
    return (dist < FogEnd ? smoothstep(FogStart, FogEnd, dist) : 1.0) * FogColor.a;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy) - ViewportOrigin;
    vec3 scene = texelFetch(SceneColorSampler, pixel, 0).rgb;
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    bool afterPack = PackParams.x > 0.5;
    if (depth >= 1.0 || (afterPack && depth < 0.56)) {
        fragColor = vec4(DebugMode == 0 ? scene : vec3(0.0), 1.0);
        return;
    }

    vec3 p = viewPos((vec2(pixel) + 0.5) / ScreenSize, depth);
    vec4 light = ResolutionScale >= 0.999 ? texelFetch(IrradianceSampler, pixel, 0) : upsample(pixel, -p.z);

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
    float lumaBound = dot(unfogged, vec3(0.2126, 0.7152, 0.0722)) / AlbedoMax;
    float ambient = afterPack ? PackParams.y : max(light.a, dot(LightmapFloor, vec3(0.2126, 0.7152, 0.0722))) * shade;
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
