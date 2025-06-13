#version 150

#moj_import <fog.glsl>

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float DiscardThreshold;
uniform float Radius;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float dist = distance(vec2(0.5, 0.5), texCoord0);
    vec4 color = vertexColor * ColorModulator;
    color.a = smoothstep(0., 1., 1. - dist / Radius) * color.a;
    if (color.a < DiscardThreshold) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
