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
uniform vec4 U_SpriteUV; // uo vo u1 v1

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 uv = vec2(
        U_SpriteUV.x + texCoord0.x * (U_SpriteUV.z - U_SpriteUV.x),
        U_SpriteUV.y + texCoord0.y * (U_SpriteUV.w - U_SpriteUV.y)
    );
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
