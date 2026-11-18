#version 150

// Depth-of-field composite: blend toward the blurred image by how far a pixel is off the focus.
// The focus is FocusDistance blocks out, or the depth under Center (autofocus) while it is below 0.
// Off the focus is linear-depth distance / FocusRange (blocks); with Lens on it is that distance as
// a share of the pixel's own depth / FocusRange — fast up close, levelling off far away, as a lens
// blurs. Near/Far mirror the projection planes.

uniform sampler2D DiffuseSampler;
uniform sampler2D BlurSampler;
uniform sampler2D DepthSampler;
uniform vec2 Center;
uniform float FocusDistance;
uniform float FocusRange;
uniform float Lens;
uniform float Near;
uniform float Far;

in vec2 texCoord;
out vec4 fragColor;

float linearize(float depth) {
    return (Near * Far) / (Far - depth * (Far - Near));
}

void main() {
    float z = linearize(texture(DepthSampler, texCoord).r);
    float focus = FocusDistance >= 0.0 ? FocusDistance : linearize(texture(DepthSampler, Center).r);
    float off = abs(z - focus);
    if (Lens > 0.5) {
        off /= max(z, 0.01);
    }
    float coc = clamp(off / max(FocusRange, 0.0001), 0.0, 1.0);
    fragColor = mix(texture(DiffuseSampler, texCoord), texture(BlurSampler, texCoord), coc);
}
