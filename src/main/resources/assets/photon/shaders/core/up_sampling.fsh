#version 150

uniform sampler2D inputSampler;
uniform float filterRadius;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // The filter kernel is applied with a radius, specified in texture
    // coordinates, so that the radius will vary across mip resolutions.
    float x = filterRadius;
    float y = filterRadius;

    // Take 9 samples around current texel:
    // a - b - c
    // d - e - f
    // g - h - i
    // === ('e' is the current texel) ===
    vec3 a = texture(inputSampler, vec2(texCoord.x - x, texCoord.y + y)).rgb;
    vec3 b = texture(inputSampler, vec2(texCoord.x,     texCoord.y + y)).rgb;
    vec3 c = texture(inputSampler, vec2(texCoord.x + x, texCoord.y + y)).rgb;

    vec3 d = texture(inputSampler, vec2(texCoord.x - x, texCoord.y)).rgb;
    vec3 e = texture(inputSampler, vec2(texCoord.x,     texCoord.y)).rgb;
    vec3 f = texture(inputSampler, vec2(texCoord.x + x, texCoord.y)).rgb;

    vec3 g = texture(inputSampler, vec2(texCoord.x - x, texCoord.y - y)).rgb;
    vec3 h = texture(inputSampler, vec2(texCoord.x,     texCoord.y - y)).rgb;
    vec3 i = texture(inputSampler, vec2(texCoord.x + x, texCoord.y - y)).rgb;

    // Apply weighted distribution, by using a 3x3 tent filter:
    //  1   | 1 2 1 |
    // -- * | 2 4 2 |
    // 16   | 1 2 1 |
    vec3 upsample = e*4.0;
    upsample += (b+d+f+h)*2.0;
    upsample += (a+c+g+i);
    upsample *= 1.0 / 16.0;
    fragColor = vec4(upsample, 1.);
}
