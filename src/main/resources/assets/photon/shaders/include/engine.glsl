// Photon per-view engine uniforms (the 1.21 U_* dynamic uniforms), bound on every Photon draw; updated once
// per world frame (FrameGraphSetupEvent) and per editor-scene render (PhotonParticleManager).
layout(std140) uniform PhotonEngine {
    mat4 U_InverseProjectionMatrix;
    mat4 U_InverseViewMatrix;   // inverse of the camera-relative view rotation
    vec4 U_CameraPosition;      // xyz = world-space eye
    vec4 U_ViewPort;            // x, y, width, height
    // xy = depth -> NDC z (scale, bias), zw = near, far. Reverse-Z: depth 1 is the near plane.
    vec4 U_DepthParams;
};

/** A depth-buffer value as NDC z. */
float photon_ndc_depth(float depth) {
    return depth * U_DepthParams.x + U_DepthParams.y;
}

/** The view-space distance of a depth-buffer value. */
float photon_eye_depth(float depth) {
    vec4 view = U_InverseProjectionMatrix * vec4(0.0, 0.0, photon_ndc_depth(depth), 1.0);
    return -view.z / view.w;
}
