# Changelog
## v26.2.2.9
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
* Fixed ara trail physics stutter
* Fixed speed and loop being ignored by per-particle animation phase
* Fixed removed FX objects' children staying in the project and coming back after reopening
* Fixed hierarchy shortcuts doing nothing right after a context menu action
* Fixed a context menu click also selecting the hierarchy row under the menu, putting an extra step into undo
* Fixed integer literals in float math breaking particle and bloom shaders on GLSL ES translators (mobile launchers)
* Fixed beam raycast crashing and look-at facing aiming at the origin in the editor
* Fixed bloom at full intensity blacking out everything below the threshold
* Fixed late-composited effects picking their layer and blend by what was drawn before them
* Fixed the editor crashing when more material previews are on screen than it keeps, or when the post effect preview is resized
* Fixed depth read through Photon's shader includes, such as soft particles, in custom shaders converted from 1.21
* Fixed the editor scene drawing nothing after a project is loaded or the scene mode is changed
* Bump up ldlib2 and kilagraph
