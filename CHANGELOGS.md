## ChangeLogs
## v26.1.2.0
* Photon2 for 26.1+

## v2.2.2
* Fixed cloud rendering
* Added more iris compact
* Added node descriptions

## v2.2.1
* Fixed cloud rendering issue
* Fixed blit blend while bloom off
* Fixed gradient drop deep copy
* Fixed VelocityOverLifetime space and cleanup
* Added simulation space for VelocityOverLifetime
* Improved sub emitter searching
* Added AudioClip volume pitch curve support
* Fixed opaque bloom behavior

## v2.2.0
A major release that grows Photon from a particle editor into a full real-time VFX toolkit:
a Unity-style Timeline, node-based Shader Graph and Post-Processing editors, a formal
simulation-space and force-field model, mesh/model particles, a GPU-instanced render pipeline,
and a way to package and ship finished effects.

### Timeline
A non-linear timeline for animating effects over time.
- **Animation tracks** — keyframe almost any emitter/module property with curve, gradient, and
  expression clips; keyframes, tangent handles, and color stops are directly editable.
- **Record mode** — play the effect and capture live edits as keyframes.
- **Signal, Seed, and Audio tracks** — fire events, re-roll randomization, and drive sound; tracks
  can be grouped.
- **Post-Process track** — schedule post-processing effects as clips.
- Multi-select, edge snapping, no-overlap placement, and smooth scrubbing.

### Shader Graph
Author particle / trail / beam materials as node graphs instead of writing GLSL.
- Full node palette: scene color/depth, geometry (position/normal/view) across object/world/view
  space, camera, UVs, math, and texture nodes.
- Curve / gradient / sampler value types with inline editors, plus reusable function subgraphs.

### Post-Processing
An Effect Graph chains fullscreen passes, each pass its own Fullscreen Shader Graph.
- A library of built-in effects: bloom, depth of field, blurs, vignette, chromatic aberration,
  glitch, film grain, outline, color grading, and more.
- Requested as timeline clips with animatable weight and per-parameter overrides; overlapping
  requests blend by weight.
- **Custom Mask / Custom Depth** — per-object masking (Unreal CustomDepth/Stencil style): flag
  emitters into named groups, then cull or outline effects to just those pixels.
- **Custom texture inputs** — feed external images (LUTs, noise, ramps) into effects, baked into
  the graph or exposed as a clip-settable sampler parameter.
- Hand-written core-shader passes work alongside graph passes.

### Simulation & Forces
- **Simulation space** — Local / World / Custom; particles are stored in their simulation space,
  so moving or rotating the whole effect behaves correctly.
- **Force Field object** — mirrors Unity's ParticleSystemForceField core set (directional,
  gravity, drag, vortex).
- **External Forces module** — emitters respond to force fields in the scene.

### Models & Meshes
- Particles can render as 3D meshes; a universal mesh input backs both the Shape module and a
  Model render mode.
- OBJ and JSON model sources, plus built-in primitives. yeah, you don't need to use a JSON file to load objs anymore.

### Custom GPU Data
- Per-emitter custom data streams (vector/color functions) readable per particle in shader graphs
  and hand-written shaders, on tile / trail / beam / ara-trail, with selectable time sources.

### Rendering
- Rebuilt into dedicated renderer classes with **GPU instancing** for tile, trail, and beam
  particles (toggleable per emitter).
- **Per-instance render overrides** — override an emitter's material/renderer at runtime and from
  the timeline, with no data copies.
- Smoother AraTrail tails with UV fixes; flat and tube trail rendering.

### Distribution
- **FX Packs** — export an effect and everything it references (materials, graphs, meshes,
  textures, shaders) into a single `.fxpack` (a standard resource-pack zip). Shared resources are
  content-addressed and deduplicated, and packed textures stay reskinnable.

### Quality of Life
- Reworked timeline UI, curve-editor improvements and rendering fixes, clearer FX hierarchy
  interaction, new icons, better number-function configuration, and clip right-click menus / paste.

### Performance
- Parallel particle updates with a safe light-cache handshake and small-queue threshold, emitter
  update micro-optimizations, and coalesced scrubbing with fast-seek replay.

### Stability
- Fixes for sub-emitter thread-safety and gating, looping bursts, bounce, lifecycle, NaN guards,
  GPU light ordering, UV sheet wrapping, and rotation-axis parity.

# v2.1.5
* Added billboard facing, render mode None, and shape arc controls (Thanks for the PR #50, @Shane77)
* Added auto load models under the photon models
* Improved zh_cn lang (Thanks for the PR #48, @Moflop)
* Added billboard stretch (Thanks for the PR #48, @Moflop)
* Bump up ldlib2

# v2.1.4
* bump up ldlib2 and remove deprecated APIs
* added command to covert photon1 fx into photon2 fx format (thanks @Cdogsnappy)
    * `/photon_client convert`, which will grab all `.fx` files from the directory in `ldlib2/assets/photon/fx_old` and convert them to their photon 2 equivalent, placing the result in the neighboring /fx directory.

# v2.1.3.a
* Added animation tiles compat for previous versions
* Added ViewPort uniform

# v2.1.3
* Bump up ldlib to v2.1.7, better editor performance, and qol.
* Added collided friction
* Added random value for additional gpu data

# v2.1.2
* Bump up ldlib to v2.1.4, better shader support

# v2.1.1
* Bump up LDLib2 version to 2.1.3+
* Fix the timer exception of BeamEmitter and any bugs of AraTrail. (Thanks @dfdyz)
* Added `colorOverSegmentTime` and `thicknessOverSegmentTime` for AraTrail. (Thanks @dfdyz)

# v2.1.0
* Bump up LDLib2 version to 2.1.0+
* Fixed UV Animation tiles definition and behavior
* Fixed GPU Instance rendering error while having multiple particles greater than max particle number
* Added zh_cn localization (Thanks @Arcomit)
* Added lang for config (Thanks @hi4444)
* Added Raycast mode for beam particles (Thanks @OmbreMoon)

# v2.0.6
* bump up ldlib2 to fix ResourcePack loading

# v2.0.5
* add mesh sorter for all vertex format
* merge materials to reduce useless drawcall
* better initial config value

# v2.0.4
* Added AraTrail
* Added SpriteMaterial
* Added wireframe mode
* Added multiple materials rendering supports
* Added DataFixer for LTS
* Added Particle Emitter prewarm
* Added Particle Radial Velocity
* Added Animation Tool Panel

# v2.0.3.b
* Fixed Try to access MeshData during reloading

# v2.0.3.a
* Fixed Random Color crash
* Fixed Gradient Resource drag drop

# v2.0.3
* Fixed crash
* Fixed delay doesn't work
* Fixed invalid immediately buffer after using GPU Instance
* Fixed Function Shape with zero speed
* Bump up ldlib2

# v2.0.2
* Fixed uv animation settings crash
* Added more lang entries for configs

# v2.0.1
* Cache draw target depth texture
* Fixed Registries loading
* Added Game Time Control
* Fixed resource releasing
* Check LDLib2 version