# Changelog
## v26.3.2.10
* hello 26.3!
* Photon's FX now draw at two points Photon makes free of a render pass, around the level's passes and an editor scene's: after the opaque draws and after every translucent one, with both classic and improved (order-independent) transparency — 26.3 draws the level inside vanilla's own passes, where Photon's stages (HDR target, bloom, distortion, post effects) cannot run
* Translucent FX now join Minecraft's Improved Transparency (order-independent transparency): with it on, effects blend with water, glass, clouds, vanilla particles and each other per pixel whatever order they are drawn in, so an effect behind stained glass is tinted by it instead of being hidden or drawn over it. Works with both composite modes and with texture, sprite, Kila, shader graph and custom shader materials (old shader graphs and custom shaders need no changes), and bloom is kept. Effects that multiply or subtract, that distort the scene, or that run under a shader pack keep the previous behaviour. Config: order_independent_transparency; per emitter: the renderer's Order-Independent Transparency (AUTO / OFF)
* Translucent FX are drawn after the clouds and the weather, so a cloud behind an effect no longer paints over it; water and glass in front of an effect still hide it unless it is composited late
* Fixed opaque FX disappearing under a shader pack: they now go into the FX layer the pack's translucent FX are merged with
* Custom shaders written for 26.1/26.2 keep working: they are adapted to 26.3's GLSL rules when their pipeline is built (#include, explicit interface locations, gl_VertexIndex/gl_InstanceIndex)
* A pipeline that fails to compile now skips its draw instead of failing the frame
* Shader graph materials no longer compile KilaGraph's order-independent transparency pipelines, which Photon never draws with
* The FX editor's scene uses Improved Transparency too when it is on: an effect behind glass shows through it, tinted, with its bloom (LDLib2 scenes now draw with order-independent transparency)
* Shader graph materials with a premultiplied blend now join Improved Transparency, and faint or zero-alpha additive and premultiplied graph surfaces no longer write the far plane into the depth
* Faster rendering: an emitter with several materials builds its geometry once; draws that Improved Transparency makes order-independent are grouped by material and merged; GPU-instanced emitters that draw alike are merged into one draw; runtime values are resolved once per tick and frame rather than per particle
* New emitters draw with GPU instancing by default (about 1.5x faster on OpenGL and 2x on Vulkan for billboards); effects saved before keep their setting
* Particle geometry and instance data are generated on worker threads when a frame has many particles (config: parallel_bake), up to about 1.8x the frame rate with many emitters
* Iris 1.11.7 support on NeoForge 26.3
* Bump up ldlib2 and kilagraph

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
