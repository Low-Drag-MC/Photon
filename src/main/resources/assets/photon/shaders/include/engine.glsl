// Photon per-frame engine uniforms (the 1.21 U_* dynamic uniforms).
// Bound per custom-shader draw by Photon's RenderTypeMixin; updated once per world frame
// (FrameGraphSetupEvent) and per editor-scene render (PhotonParticleManager).
layout(std140) uniform PhotonEngine {
    mat4 U_InverseProjectionMatrix;
    mat4 U_InverseViewMatrix;   // inverse of the camera-relative view rotation
    vec4 U_CameraPosition;      // xyz = world-space eye
    vec4 U_ViewPort;            // x, y, width, height
};
