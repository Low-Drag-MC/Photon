# Changelog
## v2.2.7
* Added animated glTF models: rigged glb import, animation picker, per-particle animation phase and optional frame blend
* Added dynamic mesh injection, so other mods can feed geometry that changes while it is drawn
* Added optional soft particles to the texture and sprite materials
* Added mesh facing modes to the Model render mode, matching Unity's RenderAlignment
* Added editor keyboard shortcuts for the gizmo, scene view toggles and timeline transport
* Added a playback rate to FXRuntime, scaling the objects and the timeline clock together
* Improved the timeline transport with icon buttons, step and jump-to-end controls
* Fixed GPU instancing aborting the process on a GL 3.2 context
* Fixed a missing material drawing models untransformed under GPU instancing
* Fixed model particles ignoring their parent's rotation and using XYZ euler order instead of Unity's ZXY
* Fixed shader graph texture parameters dropping the wrap and filter picked on them
* Fixed timeline ruler labels being clipped at the edges
* Fixed incorrect first frame data while delayed
* Bump up ldlib2 and kilagraph
