# Photon

<div align="center">

**A real-time VFX toolkit for Minecraft — particles, trails, beams, timelines, shader graphs, and post-processing, all authored in-game.**

[![GitHub stars](https://img.shields.io/github/stars/low-drag-mc/photon?style=for-the-badge&logo=github)](https://github.com/Low-Drag-MC/Photon/stargazers)
[![CurseForge](https://img.shields.io/badge/CurseForge-Photon-F16436?style=for-the-badge&logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/photon)
[![Modrinth downloads](https://img.shields.io/modrinth/dt/photon-editor?style=for-the-badge&logo=modrinth&label=Modrinth)](https://modrinth.com/mod/photon-editor)
[![Latest Maven version](https://img.shields.io/maven-metadata/v?style=for-the-badge&label=latest&metadataUrl=https%3A%2F%2Fmaven.firstdark.dev%2Fsnapshots%2Fcom%2Flowdragmc%2Fphoton%2Fphoton-neoforge-1.21.1%2Fmaven-metadata.xml)](https://maven.firstdark.dev/#/snapshots/com/lowdragmc/photon/photon-neoforge-1.21.1)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1+-E04E14?style=for-the-badge)](https://neoforged.net/)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/license-CC%20BY--NC--SA%204.0-EF9421?style=for-the-badge)](LICENSE)

[Documentation](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/) |
[Commands](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/commands.html) |
[Java Integration](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/Java%20Integration/) |
[Discord](https://discord.com/invite/sDdf2yD9bh) |
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/photon) |
[Modrinth](https://modrinth.com/mod/photon-editor)

</div>

---

Photon is a VFX editor mod for Minecraft, inspired by Unity. It brings a Unity-style particle system, trail and beam rendering, a non-linear timeline, node-based shader and post-processing graphs, and a full in-game editor to Minecraft — so mod authors can build effects for their content, and players can create and play effects with commands.

Its original intention is to let people who love VFX create without being blocked by technical skill and math problems. If you know Unity's particle system, you already know most of Photon.

## Showcase

<table>
<tr>
<td width="50%">
<a href="https://www.youtube.com/watch?v=jr800pFgZBw"><img src="https://img.youtube.com/vi/jr800pFgZBw/maxresdefault.jpg" alt="Photon 2.2 showcase"></a><br>
<strong><a href="https://www.youtube.com/watch?v=jr800pFgZBw">Photon 2.2 — Timeline, Shader Graph, Post-Processing</a></strong><br>
The latest release in action: sequencing effects on a timeline, authoring materials as node graphs, and stacking fullscreen post effects.
</td>
<td width="50%">
<a href="https://www.youtube.com/watch?v=1fXFaWheYvc"><img src="https://img.youtube.com/vi/1fXFaWheYvc/maxresdefault.jpg" alt="What is Photon"></a><br>
<strong><a href="https://www.youtube.com/watch?v=1fXFaWheYvc">Getting Started with Photon</a></strong><br>
An overview of the editor, the particle system, and how to build and play your first effect.
</td>
</tr>
</table>

## Feature Highlights

<table>
<tr>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/en/photon2/assets/photonn_editor.png" alt="Photon in-game FX editor"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/">In-game FX Editor</a></strong><br>
A Unity-style editor with real-time preview, dockable views, a resource browser, and an FX hierarchy. Run <code>/photon_editor</code> and start building — no restart, no external tools.
</td>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/Materials/"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/en/photon2/assets/ShaderMaterialInspector.png" alt="Shader material inspector"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/Materials/">Materials and Shader Graph</a></strong><br>
Author particle / trail / beam materials as node graphs instead of writing GLSL — scene color and depth, geometry and camera nodes, UVs, math, textures, curve and gradient values, plus reusable subgraphs. Hand-written shaders still work.
</td>
</tr>
<tr>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/Materials/CustomShaderMaterial/AdditionalGPUData.html"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/en/photon2/assets/GPUData.png" alt="Custom GPU data"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/Materials/CustomShaderMaterial/AdditionalGPUData.html">Custom GPU Data</a></strong><br>
Per-emitter custom data streams (vector / color functions) readable per particle in shader graphs and hand-written shaders, on tile, trail, beam, and ara-trail particles, with selectable time sources.
</td>
<td width="50%">
<a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/"><img src="https://raw.githubusercontent.com/Low-Drag-MC/LowDragMC-Doc/v2/docs/en/photon2/assets/CurveAndGradient.png" alt="Curve and gradient editors"></a><br>
<strong><a href="https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/">Curves, Gradients, and Modules</a></strong><br>
Almost every value is a curve, a gradient, a random range, or a constant, edited inline. Emitter modules mirror Unity's: shape, velocity / rotation / size / color over lifetime, noise, collision, sub-emitters, external forces, and more.
</td>
</tr>
</table>

### Also in Photon 2.2

- **Timeline** — a non-linear timeline for sequencing effects: animation tracks that keyframe almost any emitter property (curve, gradient, and expression clips), record mode, signal / seed / audio / speed / control tracks, and post-process clips.
- **Post-Processing** — an Effect Graph chains fullscreen passes, each pass its own shader graph. Ships with bloom, depth of field, blurs, vignette, chromatic aberration, glitch, film grain, outline, color grading, and more. Effects are requested with an animatable weight and per-parameter overrides; overlapping requests blend by weight.
- **Custom Mask / Custom Depth** — Unreal-style per-object masking: flag emitters into named groups, then cull or outline post effects to just those pixels.
- **Simulation space and force fields** — Local / World / Custom simulation space, a Force Field object (directional, gravity, drag, vortex), and an External Forces module.
- **Models and meshes** — particles can render as 3D meshes; OBJ and JSON model sources plus built-in primitives back both the Shape module and the Model render mode.
- **GPU instancing** — dedicated renderers for tile, trail, and beam particles, with per-instance render overrides drivable from the timeline.
- **FX Packs** — export an effect and everything it references (materials, graphs, meshes, textures, shaders) into a single `.fxpack`, a standard resource-pack zip with content-addressed shared resources.

See [CHANGELOGS.md](CHANGELOGS.md) for the full history.

## Getting Started

1. Install [LDLib2](https://modrinth.com/mod/ldlib) and Photon (Minecraft `1.21.1`, NeoForge `21.1+`).
2. Enter a creative world and run `/photon_editor` to launch the editor.
3. Create a new FX project and start experimenting.
4. Play the result with `/photon` commands, or bind it from Java (below).

Photon focuses on effect *creation*, not usage logic — use commands or code to bind an effect to entities, blocks, or your own lifecycle manager. Full walkthrough in the [documentation](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/) and [command reference](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/commands.html).

## Java Integration

Photon is published to the FirstDark Maven snapshots repository.

```gradle
repositories {
    maven { url = "https://maven.firstdark.dev/snapshots" } // LDLib2, Photon
}

dependencies {
    // LDLib2
    implementation("com.lowdragmc.ldlib2:ldlib2-neoforge-${minecraft_version_vague}:${ldlib2_version}:all")

    // Photon
    implementation("com.lowdragmc.photon:photon-neoforge-${minecraft_version_vague}:${photon_version}") {
        transitive = false
    }
}
```

The artifacts are published per *minor* Minecraft version, so the coordinate carries `26.1`, not the
full `26.1.x`:

```properties
minecraft_version_vague=26.1
ldlib2_version=26.1.2.39
photon_version=26.1.2.2
```

Load an effect and bind it to a block or an entity:

```java
FX fx = FXHelper.getFX(ResourceLocation.parse("photon:fire"));

// bind it to a block
new BlockEffectExecutor(fx, level, pos).start();

// bind it to an entity
new EntityEffectExecutor(fx, level, entity, AutoRotate.NONE).start();
```

For custom lifecycles, implement `IEffectExecutor` and drive an `FXRuntime` yourself — see the [Java Integration guide](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/Java%20Integration/).

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
