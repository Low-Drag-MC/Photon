#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float DiscardThreshold;
uniform vec4 HDR;
uniform int HDRMode;
uniform float Bits;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float bits = max(Bits, 1.0);
    vec2 uv = (floor(texCoord0 * bits) + 0.5) / bits;
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    if (color.a < DiscardThreshold) {
        discard;
    }
    if (HDRMode == 0) {
        color.rgb += HDR.a * HDR.rgb;
    } else {
        color.rgb *= HDR.a * HDR.rgb;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
