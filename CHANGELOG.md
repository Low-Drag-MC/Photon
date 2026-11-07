# Changelog
## v26.2.2.11
* Added timeline sections (like Unreal montage sections): split a timeline into named sections that play once, repeat or loop until told to move on, so one effect can hold a start, a loop of any length and an end; edit and preview them in the timeline editor, and switch them from Java (FXRuntime.sections()) or with /photon fx section
* Added release sections: stopping an effect without force (such as destroy(false) or /photon fx remove ... false) plays its release section first, then lets it wind down
* Added a Drain exit mode to control clips: when the clip ends, its object stops emitting and the particles fade out instead of vanishing
* Added Loop and PingPong lifetime modes to particles, which keep cycling through their lifetime and only die once their emitter is stopped
* Fixed effects stopped without force misbehaving while they faded out: their timeline kept running and brought objects back, particles with a Start Lifetime of 0 never died, and lights went out early
* Fixed timeline animations stuttering when an effect plays at a rate other than 1
* Fixed /photon fx remove entity skipping the remaining targets after one that had no effects

## v26.2.2.10
* Allowed creating a ParticleEmitter directly from a ParticleConfig
* Fixed glTF models not reloading after their file is edited in the assets folder
* Fixed billboards not spinning when Rotation over Lifetime uses world or custom space
* Fixed the FXPack manager overflowing with many effects; it is now a resizable, scrollable window
* Fixed pasting onto a curve field reverting
* Fixed the FX instance Revert option missing when right-clicking inside an override row
* Fixed missing translations and some editor labels
* Bump up ldlib2

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
