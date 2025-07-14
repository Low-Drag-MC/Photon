#version 150

uniform sampler2D inputA;
uniform sampler2D inputB;
uniform float BloomIntensive;
uniform float BloomScatter;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 lowRes = texture(inputA, texCoord).rgb;
    vec3 highRes = texture(inputB, texCoord).rgb;
    fragColor = vec4(mix(highRes, lowRes * BloomIntensive, BloomScatter), 1.0);
}
