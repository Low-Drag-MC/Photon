#version 150

// Unsharp mask: center*5 - cross neighbors, Amount = 0..~2 strength.

uniform sampler2D DiffuseSampler;
uniform vec4 DiffuseSampler_TexelSize; // (w, h, 1/w, 1/h)
uniform float Amount;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 o = DiffuseSampler_TexelSize.zw;
    vec4 texel = texture(DiffuseSampler, texCoord);
    vec3 sum = texture(DiffuseSampler, texCoord + vec2(o.x, 0.0)).rgb
             + texture(DiffuseSampler, texCoord - vec2(o.x, 0.0)).rgb
             + texture(DiffuseSampler, texCoord + vec2(0.0, o.y)).rgb
             + texture(DiffuseSampler, texCoord - vec2(0.0, o.y)).rgb;
    vec3 sharpened = texel.rgb + (texel.rgb * 4.0 - sum) * Amount;
    fragColor = vec4(max(sharpened, 0.0), texel.a);
}
