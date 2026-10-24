# Changelog
## v26.1.2.9
* Added FX instances (like Unity prefabs): place an .fx inside another effect, at any depth, and override, disable or delete what it places; only the overrides are saved, so it keeps following its source
* Added a new particle collision system with colliders, triggers, entities and fluids; collision is swept, so fast particles no longer pass through walls
* Added preloading effects ahead of their first play (FXHelper.preload), warming their shaders, textures and sub-emitter effects in the background
* Added converting an .fx into an editable project, from the File menu or the FXPack manager, and removing effects from an FXPack
* Added dynamic lights: clustered point and spot lights with voxel soft shadows, a Light object, particle light emission and lit particles
* Added volumetric lights and fog volumes
* Added dynamic lights to shader graphs (Dynamic Light node) and custom shaders
* Added KilaMaterial, a built-in all-in-one VFX material, with 27 presets grouped by category
* Added exporting a KilaMaterial as a shader graph, and an optional render state on shader graph materials
* Added a custom space to force, velocity, rotation and ara trail gravity
* Added copy, paste and duplicate for FX objects
* Fixed FXPack updates not taking effect until a resource reload
* Fixed stacked screen distortion falling back under after-pack shader packs
* Fixed ara trail physics stutter
* Fixed speed and loop being ignored by per-particle animation phase
* Fixed removed FX objects' children staying in the project and coming back after reopening
* Fixed hierarchy shortcuts doing nothing right after a context menu action
* Fixed integer literals in float math breaking particle and bloom shaders on GLSL ES translators (mobile launchers)
* Fixed fullscreen post effects drawing only the top-right quadrant
* Fixed beam raycast crashing and look-at facing aiming at the origin in the editor
* Fixed voxel shadows breaking into a grid while the view bobs
* Fixed light markers drawn with the view rotation applied twice
* Fixed a shadowed volumetric light breaking the editor scene
* Fixed bloom at full intensity blacking out everything below the threshold
* Fixed late-composited effects picking their layer and blend by what was drawn before them
* Bump up ldlib2 and kilagraph

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
