#version 150

#moj_import <fog.glsl>
#moj_import <photon:engine.glsl>

uniform sampler2D SamplerSceneColor;

out vec4 fragColor;

void main() {
    // U_ViewPort.zw, NOT the window size: gl_FragCoord is relative to the framebuffer being drawn
    // into, and the scene capture is sized after it. In the editor that is the PIP texture (the
    // widget's rect x guiScale), so dividing by the window size sampled a corner of the capture and
    // stretched it — and the result changed whenever the panel was resized.
    vec2 screenUV = gl_FragCoord.xy / U_ViewPort.zw;
    vec4 color = texture(SamplerSceneColor, screenUV);

    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(vec3(step(luminance, 0.5)), 1.0);
}
