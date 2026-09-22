# Changelog
## v26.1.2.3
* Added animated glTF models: rigged glb import, animation picker, per-particle animation phase and optional frame blend
* Added dynamic mesh injection, so other mods can feed geometry that changes while it is drawn
* Added optional soft particles to the texture and sprite materials
* Added mesh facing modes to the Model render mode, matching Unity's RenderAlignment
* Added editor keyboard shortcuts for the gizmo, scene view toggles and timeline transport
* Added a playback rate to FXRuntime, scaling the objects and the timeline clock together
* Improved the timeline transport with icon buttons, step and jump-to-end controls
* Fixed a missing material drawing models untransformed under GPU instancing
* Fixed model particles ignoring their parent's rotation and using XYZ euler order instead of Unity's ZXY
* Fixed shader graph texture parameters dropping the wrap and filter picked on them
* Fixed timeline ruler labels being clipped at the edges
* Fixed incorrect first frame data while delayed
* Bump up ldlib2 and kilagraph

## v26.1.2.2
* Added vertex tangent support
* Added glTF models
* Added FXSceneOptions
* Fixed object space on the Transform and View Direction nodes
* Fixed creating curves, materials and gradients from the asset browser

## v26.1.2.1
* Added HDR supports
* Added more shader nodes
* Cached the FX listing and dropped it on resource reload
* Added project icon
* Fixed sun bloom
* Rendered effects into the surface being drawn on rather than the game window

## v26.1.2.0
* Photon2 for 26.1+

## v2.2.2
* Fixed cloud rendering
* Added more iris compact
* Added node descriptions
