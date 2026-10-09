// What every fullscreen dynamic-light pass knows about the frame it lights: one block written once per frame
// (DynamicLightRenderer.writeView) and the StepParams each draw sets — LOCKSTEP. The members avoid the engine's
// names; the #defines below keep 1.21's shader bodies, so do not import minecraft:globals.glsl after this file.

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
};

layout(std140) uniform PhotonLightStep {
    vec4 StepParams;      // what this one draw needs: see each pass
};

#define ScreenSize LightScreen.xy
#define ResolutionScale LightScreen.z
#define DebugMode int(LightScreen.w + 0.5)
