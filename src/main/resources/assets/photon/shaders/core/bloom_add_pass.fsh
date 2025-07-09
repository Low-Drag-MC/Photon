#version 150

uniform sampler2D inputA;
uniform sampler2D inputB;
uniform float BloomIntensive;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 lowRes = texture(inputA, texCoord).rgb;
    vec3 highRes = texture(inputB, texCoord).rgb;
    fragColor = vec4(lowRes * BloomIntensive + highRes, 1.0);
}
