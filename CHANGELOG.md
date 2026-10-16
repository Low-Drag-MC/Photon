# Changelog
## v2.2.8
* Added dynamic lights: clustered point and spot lights with voxel soft shadows, a Light object, particle light emission and lit particles
* Added volumetric lights and fog volumes
* Added dynamic lights to shader graphs (Dynamic Light node) and custom shaders
* Added KilaMaterial, a built-in all-in-one VFX material, with 27 presets grouped by category
* Added exporting a KilaMaterial as a shader graph, and an optional render state on shader graph materials
* Added a custom space to force, velocity, rotation and ara trail gravity
* Added copy, paste and duplicate for FX objects
* Fixed ara trail physics stutter
* Fixed speed and loop being ignored by per-particle animation phase
* Fixed removed FX objects' children staying in the project and coming back after reopening
* Fixed hierarchy shortcuts doing nothing right after a context menu action
* Fixed integer literals in float math breaking particle and bloom shaders on GLSL ES translators (mobile launchers)
