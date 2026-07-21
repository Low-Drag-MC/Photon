# Changelog
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
- OBJ and JSON model sources, plus built-in primitives.

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
