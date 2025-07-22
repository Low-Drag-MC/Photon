#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform vec4 BloomColor;
uniform float FogStart;
uniform float DiscardThreshold;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragData[2];

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    color = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);

    if (color.a < DiscardThreshold) {
        discard;
    }

    //main target
    fragData[0] = color;

    //bloom target
    fragData[1] = color * BloomColor;
}