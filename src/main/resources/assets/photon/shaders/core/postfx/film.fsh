#version 150

// three.js FilmShader: animated grain + scanlines. GameTime is engine-driven (0..1 per day cycle).

uniform sampler2D DiffuseSampler;

layout(std140) uniform PhotonPass {
    float NoiseIntensity;
    float ScanlineIntensity;
    float ScanlineCount;
    float GameTime;
};

in vec2 texCoord;
out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);
    float time = GameTime * 24000.0;
    float dx = rand(texCoord + fract(time));
    vec3 result = texel.rgb + texel.rgb * clamp(0.1 + dx, 0.0, 1.0);
    vec2 sc = vec2(sin(texCoord.y * ScanlineCount), cos(texCoord.y * ScanlineCount));
    result += texel.rgb * vec3(sc.x, sc.y, sc.x) * ScanlineIntensity;
    result = texel.rgb + clamp(NoiseIntensity, 0.0, 1.0) * (result - texel.rgb);
    fragColor = vec4(result, texel.a);
}
