// What every fullscreen dynamic-light pass knows about the frame it lights: one block written once per frame
// (DynamicLightRenderer.writeView) and the StepParams each draw sets — LOCKSTEP. The members avoid the engine's
// names, which the #defines below give back to the pass bodies, so do not import minecraft:globals.glsl after this file.

layout(std140) uniform PhotonLightView {
    mat4 IProjMat;
    mat4 IViewMat;
    mat4 LightProjMat;
    vec4 LightScreen;     // full-resolution width, height, light-buffer resolution scale, debug view
    vec4 PackParams;      // x: over a shader pack's final image, y: assumed darkest pack lighting, z: vanilla fog applies
    vec4 ShadowParams;    // mode (0 off, 1 screen, 2 voxel), steps, occluder thickness, max march
    vec4 ContactParams;   // contact steps, contact distance, soft shadows
    vec4 CameraFrac;      // camera position minus its block position
    ivec4 VoxelOrigin;    // voxel region min corner in blocks, relative to the camera block
    vec4 FaceShade;       // vanilla's face shading: down, up, north/south, east/west
    vec4 LightParams;     // albedo max, forward scattering, pieces per side of a volumetric light, shadowed volumes
    // depth -> NDC z (scale, bias), the depth of the near plane, the depth of the sky: read off the projection each
    // frame, as vanilla's reverse-Z or whatever a shader pack renders with, so nothing below assumes a convention
    vec4 DepthParams;
};

layout(std140) uniform PhotonLightStep {
    vec4 StepParams;      // what this one draw needs: see each pass
};

// nothing was drawn here: the depth buffer still holds its clear value
bool isSky(float depth) {
    return DepthParams.w > 0.5 ? depth >= 1.0 : depth <= 0.0;
}

#define ScreenSize LightScreen.xy
#define ResolutionScale LightScreen.z
#define DebugMode int(LightScreen.w + 0.5)
