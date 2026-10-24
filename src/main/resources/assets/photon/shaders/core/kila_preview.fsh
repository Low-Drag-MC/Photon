#version 330

// One KilaMaterial texture slot drawn alone for the inspector, through the material's own kila_lib uv chain and noise.
// KILA_SLOT_PREVIEW leaves the material's block out of kila_lib: the slot has this one of its own.

#moj_import <photon:kila_lib.glsl>

uniform sampler2D Sampler0;

// ⚠️ MIRRORED FROM KilaSlotPreview.SlotState.prepare
layout(std140) uniform KilaSlot {
    vec4 KilaPreviewST;
    vec4 KilaPreviewAnim;
    vec4 KilaPreviewPolar;
    // x = uv flags, y = what the slot reads (0 colour, 1 one channel, 2 red and green), z = colour mode,
    // w = 1 when the slot is a procedural noise
    ivec4 KilaPreviewOpts;
    vec4 KilaPreviewChannel;
    vec4 KilaPreviewNoise;
};

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    int flags = KilaPreviewOpts.x;
    vec2 uv = kila_uv(texCoord0, KilaPreviewST, KilaPreviewAnim, KilaPreviewPolar, flags, vec2(0.0), vec2(0.0));
    float clip = kila_clip(uv, flags);
    bool procedural = KilaPreviewOpts.w == 1;
    int reads = KilaPreviewOpts.y;
    if (reads == 0) {
        vec4 c = kila_color_mode(texture(Sampler0, kila_wrap(uv, flags)), KilaPreviewOpts.z);
        vec2 cell = floor(gl_FragCoord.xy / 4.0);
        vec3 checker = mod(cell.x + cell.y, 2.0) < 0.5 ? vec3(0.34) : vec3(0.22);
        fragColor = vec4(mix(checker, c.rgb, clamp(c.a * clip, 0.0, 1.0)), 1.0);
    } else if (reads == 1) {
        float v = procedural ? kila_noise(uv, KilaPreviewNoise) : dot(texture(Sampler0, kila_wrap(uv, flags)), KilaPreviewChannel);
        fragColor = vec4(vec3(clamp(v * clip, 0.0, 1.0)), 1.0);
    } else {
        vec2 n = procedural ? kila_noise2(uv, KilaPreviewNoise) : texture(Sampler0, uv).rg;
        fragColor = vec4(clamp(n, 0.0, 1.0) * clip, 0.5 * clip, 1.0);
    }
}
