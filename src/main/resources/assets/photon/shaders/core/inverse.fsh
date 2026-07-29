#version 150

#moj_import <fog.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D SamplerSceneColor;

out vec4 fragColor;

void main() {
    vec2 screenUV = gl_FragCoord.xy / ScreenSize;
    vec4 color = texture(SamplerSceneColor, screenUV);

    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(vec3(step(luminance, 0.5)), 1.0);
}
