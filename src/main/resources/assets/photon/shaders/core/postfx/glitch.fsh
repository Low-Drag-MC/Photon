#version 150

// Procedural digital glitch (three.js DigitalGlitch-inspired, no noise texture): time-hashed
// row displacement bursts + RGB split + fine line noise. Intensity 0..1 drives everything.

uniform sampler2D DiffuseSampler;
uniform float GameTime;
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    float time = floor(GameTime * 24000.0 * 4.0);
    vec2 uv = texCoord;
    // occasional heavy frames; scaled so Intensity 0 is fully clean
    float burst = step(1.0 - Intensity * 0.5, rand(vec2(time, 9.1)));
    float rows = 8.0 + rand(vec2(time, 1.3)) * 24.0;
    float row = floor(uv.y * rows);
    float shift = (rand(vec2(time, row)) - 0.5) * 0.2 * Intensity * (0.3 + burst);
    shift *= step(0.6, rand(vec2(row, time + 2.0))); // only some rows tear
    uv.x = clamp(uv.x + shift, 0.0, 1.0);
    float split = (0.003 + 0.015 * burst) * Intensity;
    vec4 cr = texture(DiffuseSampler, clamp(uv + vec2(split, 0.0), 0.0, 1.0));
    vec4 cg = texture(DiffuseSampler, uv);
    vec4 cb = texture(DiffuseSampler, clamp(uv - vec2(split, 0.0), 0.0, 1.0));
    float lineNoise = rand(vec2(floor(texCoord.y * 400.0), time)) * burst * 0.15 * Intensity;
    fragColor = vec4(vec3(cr.r, cg.g, cb.b) + lineNoise, cg.a);
}
