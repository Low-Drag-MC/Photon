#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float DiscardThreshold;
uniform vec4 HDR;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < DiscardThreshold) {
        discard;
    }
    color = vec4(color.rgb + HDR.a * HDR.rgb, color.a);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
