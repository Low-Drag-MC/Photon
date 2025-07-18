const float INV_TEX_SIZE = 1.0 / 128.0;
const float MAX_U = 1.0 - 0.5 * INV_TEX_SIZE;

float getCurveValue(sampler2D curveTexture, int curveIndex, float x) {
    return texture(curveTexture, vec2(clamp(x, 0.0, MAX_U), (float(curveIndex) + 0.5) * INV_TEX_SIZE)).r;
}

vec4 getGradientValue(sampler2D gradientTexture, int gradientIndex, float x) {
    return texture(gradientTexture, vec2(clamp(x, 0.0, MAX_U), (float(gradientIndex) + 0.5) * INV_TEX_SIZE));
}
