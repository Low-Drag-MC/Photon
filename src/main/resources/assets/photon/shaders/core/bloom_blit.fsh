#version 150

// The ADD half of 1.21 bloom_final_scatter_pass. Its formula OUTPUT = fx + I*(bloom - highlight)
// assumed a separate OUTPUT target; 26.1 composites in place on the main color attachment (which
// cannot be sampled while bound), so the formula is split into two blend draws: bright_pass with
// OUTPUT_SCALE=I under GL_FUNC_REVERSE_SUBTRACT removes I*highlight, then this pass adds I*bloom
// (ONE, ONE; OUTPUT_SCALE also decodes the chain's encoded values). Subtract runs first so the sum
// never clamps. bloom_final_scatter_pass.fsh itself stays pristine for the M3 postfx-stack port.

uniform sampler2D inputSampler;

#ifndef OUTPUT_SCALE
#define OUTPUT_SCALE 1.0
#endif

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(inputSampler, texCoord).rgb * OUTPUT_SCALE, 1.0);
}
