#version 150

#moj_import <fog.glsl>

uniform vec4 ColorModulator;
uniform vec4 BloomColor;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float DiscardThreshold;
uniform float Radius;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragData[2];

void main() {
    float dist = distance(vec2(0.5, 0.5), texCoord0);
    vec4 color = vertexColor * ColorModulator;
    color.a = smoothstep(0., 1., 1. - dist / Radius) * color.a;
    if (color.a < DiscardThreshold) {
        discard;
    }

    color = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);

    //main target
    fragData[0] = color;

    //bloom target
    fragData[1] = color * BloomColor;
}