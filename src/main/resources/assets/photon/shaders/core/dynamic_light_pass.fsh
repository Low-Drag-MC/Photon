#version 150

// Irradiance from the clustered lights at ResolutionScale, position and normal from depth. Alpha carries
// the lights' contribution-weighted vanilla ambient, which the composite divides out of the frame.

#moj_import <photon:dynamic_light.glsl>
#moj_import <photon:dynamic_light_depth.glsl>
#moj_import <photon:dynamic_light_voxel.glsl>

uniform mat4 ProjMat;
uniform vec4 ShadowParams;   // mode (0 off, 1 screen, 2 voxel), steps, occluder thickness, max march
uniform vec4 ContactParams;  // contact steps, contact distance, soft shadows, unused
uniform vec4 PackParams;     // x: drawing over a shader pack's final image
uniform int DebugMode;

out vec4 fragColor;

float pixelFootprint(float viewZ) {
    return 2.0 * -viewZ / (ProjMat[1][1] * ScreenSize.y);
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// the cell's point on the disk the light's sphere shows to `origin`; x = ring, y = sector
vec3 lightSample(vec3 lightPos, vec3 origin, float radius, int light, ivec2 cell) {
    vec3 w = normalize(lightPos - origin);
    vec3 a = abs(w.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 u = normalize(cross(a, w));
    vec3 v = cross(w, u);
    float r = radius * sqrt((float(cell.x) + 0.5) / 4.0);
    float phi = (float(cell.y) + 0.5 + 0.25 * float(cell.x)) * 1.5707963 + float(light) * 1.3;
    return lightPos + (u * cos(phi) + v * sin(phi)) * r;
}

// 1 = lit. Leaving the screen or passing the near plane counts as lit. Starts a few pixels out and
// stops short of the light: whatever draws the source is not its own occluder.
float screenShadow(vec3 origin, vec3 target, float maxLen, int steps, float noise, float slope) {
    vec3 delta = target - origin;
    float len = length(delta);
    float start = min(3.0 * pixelFootprint(origin.z), len * 0.5);
    float marchLen = max(min(len - 0.3, maxLen) - start, 0.0);
    vec3 dir = delta / len;
    for (int i = 0; i < steps; i++) {
        vec3 s = origin + dir * (start + (float(i) + noise) / float(steps) * marchLen);
        if (s.z > -0.05) break;
        vec4 clip = ProjMat * vec4(s, 1.0);
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
        float behind = viewPos(uv, texture(SceneDepthSampler, uv).r).z - s.z;
        float bias = 0.02 + 2.0 * pixelFootprint(s.z) / slope;
        if (behind > bias && behind < ShadowParams.z) return 0.0;
    }
    return 1.0;
}

void main() {
    ivec2 pixel = scenePixel(ivec2(gl_FragCoord.xy));
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    // Iris draws the hand with a z-scaled projection; packs tell it apart by depth the same way
    if (depth >= 1.0 || (PackParams.x > 0.5 && depth < 0.56)) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 p = viewPos((vec2(pixel) + 0.5) / ScreenSize, depth);
    vec3 n = reconstructNormal(pixel, p);
    mat3 viewToWorld = mat3(IViewMat);
    vec3 nw = normalize(viewToWorld * n);
    vec3 an = abs(nw);
    float major = max(an.x, max(an.y, an.z));
    if (major > 0.985) {
        nw = an.y == major ? vec3(0.0, sign(nw.y), 0.0) : an.x == major ? vec3(sign(nw.x), 0.0, 0.0) : vec3(0.0, 0.0, sign(nw.z));
        n = transpose(viewToWorld) * nw;
    }

    float viewDepth = -p.z;
    bool soft = ContactParams.z > 0.5;
    ivec2 cell = tileCell();
    float noise = soft ? tileStratum() : ign(gl_FragCoord.xy);
    vec3 origin = p + n * (0.03 + 0.002 * viewDepth);
    float slope = max(abs(dot(n, normalize(-p))), 0.15);
    vec3 originBlock = voxelPos(origin);
    int mode = int(ShadowParams.x + 0.5);

    vec3 irradiance = vec3(0.0);
    vec3 unshadowed = vec3(0.0);
    float ambientSum = 0.0;
    float weightSum = 0.0;
    ivec2 cluster = photon_cluster(pixel, viewDepth);
    for (int k = 0; k < cluster.y; k++) {
        int light = photon_clusterLight(cluster.x + k);
        vec4 t0, t1, t2, t3;
        photon_fetchLight(light, t0, t1, t2, t3);
        vec3 toLight = t0.xyz - p;
        float d2 = dot(toLight, toLight);
        float attenuation = photon_attenuation(d2, t0.w);
        if (attenuation <= 0.0) continue;
        vec3 l = toLight * inversesqrt(d2);
        float ndl = dot(n, l);
        if (ndl <= 0.0) continue;
        attenuation *= photon_spot(l, t1, t2, t3);
        if (attenuation <= 0.0) continue;
        vec3 contribution = t1.rgb * (attenuation * ndl);
        unshadowed += contribution;
        float visible = 1.0;
        if (mode > 0 && t3.y > 0.5) {
            vec3 target = soft && t3.w > 0.0 ? lightSample(t0.xyz, origin, t3.w, light, cell) : t0.xyz;
            if (mode == 2) {
                visible = voxelShadow(originBlock, voxelPos(target));
                if (visible > 0.0 && ContactParams.x > 0.5) {
                    visible = screenShadow(origin, target, ContactParams.y, int(ContactParams.x), noise, slope);
                }
            } else {
                visible = screenShadow(origin, target, ShadowParams.w, int(ShadowParams.y), noise, slope);
            }
        }
        vec3 lit = contribution * visible;
        irradiance += lit;
        float weight = dot(lit, vec3(0.2126, 0.7152, 0.0722));
        ambientSum += weight * t3.z;
        weightSum += weight;
    }

    float ambient = weightSum > 1e-6 ? ambientSum / weightSum : 0.0;
    if (DebugMode == 3) {
        float total = dot(unshadowed, vec3(1.0));
        float visible = total > 1e-4 ? dot(irradiance, vec3(1.0)) / total : 1.0;
        fragColor = vec4(mix(vec3(1.0, 0.1, 0.1), vec3(1.0), visible) * min(total * 4.0, 1.0), ambient);
    } else if (DebugMode == 5) {
        fragColor = vec4(vec3(float(cluster.y) / 16.0), ambient);
    } else {
        // RGBA16F tops out at 65504; past that a texel turns to Inf, and Inf * 0 in the blur to NaN
        fragColor = vec4(min(irradiance, vec3(6e4)), ambient);
    }
}
