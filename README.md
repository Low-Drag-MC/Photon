# Photon

<div align="center">

**A real-time VFX toolkit for Minecraft — particles, trails, beams, dynamic lights, timelines, shader graphs, and post-processing, all authored in-game.**

[![GitHub stars](https://img.shields.io/github/stars/low-drag-mc/photon?style=for-the-badge&logo=github)](https://github.com/Low-Drag-MC/Photon/stargazers)
[![CurseForge](https://img.shields.io/badge/CurseForge-Photon-F16436?style=for-the-badge&logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/photon)
[![Modrinth downloads](https://img.shields.io/modrinth/dt/photon-editor?style=for-the-badge&logo=modrinth&label=Modrinth)](https://modrinth.com/mod/photon-editor)
[![Latest Maven version](https://img.shields.io/maven-metadata/v?style=for-the-badge&label=latest&metadataUrl=https%3A%2F%2Fmaven.firstdark.dev%2Fsnapshots%2Fcom%2Flowdragmc%2Fphoton%2Fphoton-neoforge-1.21.1%2Fmaven-metadata.xml)](https://maven.firstdark.dev/#/snapshots/com/lowdragmc/photon/photon-neoforge-1.21.1)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1+-E04E14?style=for-the-badge)](https://neoforged.net/)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/license-CC%20BY--NC--SA%204.0-EF9421?style=for-the-badge)](LICENSE)

[Documentation](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/) |
[Getting Started](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/getting-started.html) |
[Commands](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/commands.html) |
[Java API](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/) |
[Discord](https://discord.com/invite/sDdf2yD9bh) |
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/photon) |
[Modrinth](https://modrinth.com/mod/photon-editor)

</div>

---

Photon is a VFX editor mod for Minecraft, inspired by Unity. It brings a Unity-style particle system, trail and beam rendering, dynamic lights with soft shadows, a non-linear timeline, node-based shader and post-processing graphs, and a full in-game editor to Minecraft — so mod authors can build effects for their content, and players can create and play effects with commands.

Its original intention is to let people who love VFX create without being blocked by technical skill and math problems. If you know Unity's particle system, you already know most of Photon.

## Showcase

<table>
<tr>
<td width="50%">
<a href="https://www.youtube.com/watch?v=jr800pFgZBw"><img src="https://img.youtube.com/vi/jr800pFgZBw/maxresdefault.jpg" alt="Photon 2.2 showcase"></a><br>
<strong><a href="https://www.youtube.com/watch?v=jr800pFgZBw">Photon 2.2 — Timeline, Shader Graph, Post-Processing</a></strong><br>
Sequencing effects on a timeline, authoring materials as node graphs, and stacking fullscreen post effects.
</td>
<td width="50%">
<a href="https://www.youtube.com/watch?v=1fXFaWheYvc"><img src="https://img.youtube.com/vi/1fXFaWheYvc/maxresdefault.jpg" alt="What is Photon"></a><br>
<strong><a href="https://www.youtube.com/watch?v=1fXFaWheYvc">Getting Started with Photon</a></strong><br>
An overview of the editor, the particle system, and how to build and play your first effect.
</td>
</tr>
</table>

## New Since the 2.2 Showcase

<table>
<tr>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/dynamic-lights/"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/lights-overview.webp" alt="A night square lit only by Photon lights"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/dynamic-lights/">Dynamic Lights</a></strong> <sub>2.2.8</sub><br>
Point and spot lights that light blocks, entities, and particles, with voxel soft shadows that never leak through walls. Place a Light object, let particles emit light with the Light Emission module, or add lights from Java. Works under Iris shader packs too.
</td>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/dynamic-lights/lights-and-fog.html#volumetric-lights"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/lights-volumetric-fog.webp" alt="Light shafts from a temple doorway through a low fog volume"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/dynamic-lights/lights-and-fog.html#volumetric-lights">Volumetric Lights and Fog Volumes</a></strong> <sub>2.2.8</sub><br>
Halos around point lights, beams from spot lights, and box or sphere fog volumes for them to shine through. Shadowed lights cast real light shafts through doors and windows.
</td>
</tr>
<tr>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/shaders-and-gpu/kila-material.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/kila-showcase.webp" alt="Kila Material presets glowing at night"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/shaders-and-gpu/kila-material.html">Kila Material</a></strong> <sub>2.2.8</sub><br>
An all-in-one VFX material: switch on flipbooks, masks, dissolve, distortion, color ramps, soft edges, fresnel, lighting, and decals instead of building a graph. Start from one of 27 presets, and export the material as a Shader Graph when you need more.
</td>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/fx-instances.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/fx-instance-editor.webp" alt="An FX instance in the editor hierarchy"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/fx-instances.html">FX Instances</a></strong> <sub>2.2.9</sub><br>
Unity-style prefabs for effects: place one <code>.fx</code> inside another, at any depth, then override, disable, delete, or add objects. Only your changes are saved, so the instance keeps following its source.
</td>
</tr>
<tr>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/particle-system/collision.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/collision-overview.webp" alt="Sparks bouncing and coming to rest on stairs and slabs"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/particle-system/collision.html">Particle Collision</a></strong> <sub>2.2.9</sub><br>
Swept collision against block shapes, fluid surfaces, entities, and Collider objects, so fast particles no longer pass through walls. Bounce, slide, stick, or die on contact; triggers report particles entering and leaving, and contacts can fire sub-emitters or a Java listener.
</td>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/particle-system/gltf-models.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/gltf-animated-swarm.webp" alt="A swarm of animated hologram foxes"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/particle-system/gltf-models.html">Animated glTF Models</a></strong> <sub>2.2.7</sub><br>
Drop a <code>.glb</code> into the editor and use it as a particle mesh or an emission shape. Rigged files play their animations, and a per-particle animation phase keeps a swarm from moving in lockstep.
</td>
</tr>
</table>

## Feature Highlights

<table>
<tr>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/editor-and-projects.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/photonn_editor.png" alt="Photon in-game FX editor"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/editor-and-projects.html">In-game FX Editor</a></strong><br>
A Unity-style editor with real-time preview, dockable views, a resource browser, an FX hierarchy, undo history, and keyboard shortcuts. Run <code>/photon_editor</code> and start building — no restart, no external tools.
</td>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/shaders-and-gpu/"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/ShaderMaterialInspector.png" alt="Shader material inspector"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/shaders-and-gpu/">Materials and Shader Graph</a></strong><br>
Author particle / trail / beam materials as node graphs instead of writing GLSL — scene color and depth, geometry and camera nodes, UVs, math, textures, curve and gradient values, dynamic lights, plus reusable subgraphs. Hand-written shaders still work.
</td>
</tr>
<tr>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/shaders-and-gpu/additional-gpu-data.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/GPUData.png" alt="Custom GPU data"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/shaders-and-gpu/additional-gpu-data.html">Custom GPU Data</a></strong><br>
Per-emitter custom data streams (vector / color functions) readable per particle in shader graphs and hand-written shaders, on tile, trail, beam, and ara-trail particles, with selectable time sources.
</td>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/particle-system/value-functions.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/assets/photon2/CurveAndGradient.png" alt="Curve and gradient editors"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/particle-system/value-functions.html">Curves, Gradients, and Modules</a></strong><br>
Almost every value is a curve, a gradient, a random range, or a constant, edited inline. Emitter modules mirror Unity's: shape, velocity / rotation / size / color over lifetime, noise, collision, sub-emitters, external forces, and more.
</td>
</tr>
</table>

## Getting Started

1. Install [LDLib2](https://modrinth.com/mod/ldlib) and Photon (Minecraft `1.21.1`, NeoForge `21.1+`). Photon `2.2.9` needs LDLib2 `2.2.43` or newer; KilaGraph is bundled inside Photon.
2. Enter a creative world and run `/photon_editor` to launch the editor.
3. Create a new FX project and start experimenting.
4. Play the result with `/photon` commands, or bind it from Java (below).

Photon focuses on effect *creation*, not usage logic — use commands or code to bind an effect to entities, blocks, or your own lifecycle manager. Full walkthrough in the [documentation](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/getting-started.html) and [command reference](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/commands.html).

## Java Integration

Photon, LDLib2, and KilaGraph are published to the FirstDark Maven snapshots repository.

```gradle
repositories {
    maven { url = "https://maven.firstdark.dev/snapshots" } // LDLib2, KilaGraph, Photon
}

dependencies {
    // LDLib2
    implementation("com.lowdragmc.ldlib2:ldlib2-neoforge-${minecraft_version}:${ldlib2_version}:all")

    // Photon: the `all` jar bundles KilaGraph
    implementation("com.lowdragmc.photon:photon-neoforge-${minecraft_version}:${photon_version}:all")
}
```

Photon's Maven metadata lists no compile dependencies, so declare LDLib2 yourself. Add KilaGraph as `compileOnly` only if your code imports KilaGraph classes. The [Java API guide](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/) covers the `neoforge.mods.toml` entries and Jar-in-Jar bundling.

Load an effect and bind it to a block or an entity:

```java
FX fx = FXHelper.getFX(ResourceLocation.parse("photon:fire"));

// bind it to a block
new BlockEffectExecutor(fx, level, pos).start();

// bind it to an entity
new EntityEffectExecutor(fx, level, entity, AutoRotate.NONE).start();
```

The first play of an effect loads its shaders and textures on the spot, which can hitch. Preload it beforehand:

```java
// in the background: file reads and decoding off-thread, GL work spread over frames
FXHelper.preload(ResourceLocation.parse("photon:fire"));

// right now, on the render thread (e.g. behind your own loading screen)
FXHelper.preloadNow(ResourceLocation.parse("photon:fire"));

// for the whole session: preloaded now and again behind the loading screen of every resource reload
FXHelper.keepPreloaded(ResourceLocation.parse("photon:fire"));
```

Add dynamic lights without an effect:

```java
// a light that follows an entity, 1 block above its feet
PhotonLights.Handle handle = PhotonLights.attach(entity, new DynamicLight().color(1f, .5f, .2f).range(8), new Vec3(0, 1, 0));

// a flash that fades out over 10 ticks
PhotonLights.flash(new DynamicLight().at(x, y, z).intensity(40).range(16), 10);
```

For custom lifecycles, implement `IEffectExecutor` and drive an `FXRuntime` yourself — see the [Java API guide](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/).

## Links

- [Documentation](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/)
- [GitHub repository](https://github.com/Low-Drag-MC/Photon)
- [CurseForge project](https://www.curseforge.com/minecraft/mc-mods/photon)
- [Modrinth project](https://modrinth.com/mod/photon-editor)
- [Discord community](https://discord.com/invite/sDdf2yD9bh)
- [LDLib2](https://github.com/Low-Drag-MC/LDLib2) — the library Photon is built on
- QQ group: `933426877`

## License

> **Please read [LICENSE](./LICENSE) before redistributing, forking, or porting Photon.**
> Using the mod is free. Redistributing or building on the *mod itself* comes with conditions, and they are enforced.

Photon by KilaBash is licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).

**What you can do freely:**

- Use Photon in single-player, on servers, and in non-commercial modpacks.
- Bundle it in your mod via jar-in-jar, as long as your mod is not sold or directly monetized.
- Share and adapt the mod, with attribution, a link to the license, and a note of what you changed.
- **Own everything you make with it.** Content created using Photon — `.fx` files, FX Packs, shader and effect graphs, configs, data packs, videos — is *not* covered by this license. License and sell your creations however you like.

**What needs permission or is not allowed:**

- Commercial use of the mod (paid downloads, paid access, monetized redistribution) requires explicit written permission.
- Forks and modified versions must stay under CC BY-NC-SA 4.0 and credit the original author.
- **Ports to Minecraft versions other than 1.21.x** need prior written consent from KilaBash, must be fully open source under the same license, must credit the original project, and may not be monetized in any form — including donations, sponsorships, and crowdfunding.

Licensing inquiries (commercial use, port permissions): **yefancy@foxmail.com**

See [LICENSE](./LICENSE) for the authoritative and complete terms.
