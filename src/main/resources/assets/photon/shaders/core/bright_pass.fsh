#version 150

uniform sampler2D DiffuseSampler;
uniform float Threshold;      // luma threhold
uniform float Knee;     // [0,1]：soft-knee

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main(){
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float br = dot(c, LUMA);
    // soft-knee
    float knee = Threshold * Knee;
    float soft = clamp((br - Threshold + knee) / (2.0 * knee), 0.0, 1.0);
    float bright = max(br - Threshold, 0.0) + soft * soft * 2.0 * knee;
    fragColor = vec4(c * (bright / max(br, 1e-5)), 1.0);  // 仅保留亮区
}
