package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXPreloader;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.TrailsSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.KilaGraphExport;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.client.render.PhotonParticleGroup;
import com.lowdragmc.photon.core.mixins.accessor.DeviceCachesAccessor;
import com.lowdragmc.photon.core.mixins.accessor.GpuDeviceAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ParticleEngineAccessor;
import com.lowdragmc.photon.core.mixins.accessor.TextureManagerAccessor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * An effect played after a preload compiles no program and loads no texture; a control that was not preloaded does.
 * A pipeline compiles on its first draw, so "a program compiled" is a Photon pipeline the device had not compiled
 * before.
 */
@LDLRegisterClient(name = "fx_preload", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxPreloadScenario implements UIScenario {
    private static final String ROOT = "uitest_preload";
    // fresh per run: textures outlive the definitions, so a rerun in the same client must not reuse names
    private static String dir = ROOT;

    @Override
    public void define(ScenarioBuilder s) {
        s.step("write the fixtures", FxPreloadScenario::writeFixtures)
                .step("look straight ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .frames(4)

                .step("preload the parent asynchronously", ctx -> {
                    ctx.put("preloadStart", System.nanoTime());
                    ctx.put("compiledBeforePreload", compiledPipelines());
                    ctx.put("preload", FXHelper.preload(fx("parent")));
                })
                .timeoutMs(60_000)
                .waitUntil("the async preload completes", ctx -> ctx.<CompletableFuture<FX>>get("preload").isDone())
                .step("the async preload warmed the parent and the child it spawns", ctx -> {
                    var ms = (System.nanoTime() - ctx.<Long>get("preloadStart")) / 1_000_000;
                    var compiled = newPipelines(ctx, "compiledBeforePreload").size();
                    ctx.log("async preload: %d ms, %d programs compiled".formatted(ms, compiled));
                    var result = ctx.<CompletableFuture<FX>>get("preload").getNow(null);
                    ctx.check("the preload completed with the definition", result != null
                            && result == FXHelper.getCachedFX(fx("parent")), "the cached parent", result);
                    var child = FXHelper.getCachedFX(fx("child"));
                    ctx.check("the sub-emitter's effect was loaded too", child != null, "cached", child);
                    ctx.check("no render-thread work is left over", FXPreloader.pendingJobs() == 0,
                            0, FXPreloader.pendingJobs());
                    // l: a shader graph's sampler, which KilaGraph binds itself; m: a texture with no file, which
                    // loads as the missing texture, as a first bind would
                    checkTexturesLoaded(ctx, List.of("a", "b", "c", "d", "e", "f", "g", "h", "l", "m"));
                })

                .step("play the preloaded parent", ctx -> play(ctx, "parent"))
                .ticks(30)
                .step("playing it compiled and loaded nothing", ctx -> {
                    checkNothingLoaded(ctx, "parent");
                    checkDrew(ctx, "parent");
                    var particles = childParticles(ctx);
                    ctx.check("the sub-emitter spawned its effect", particles > 0, "> 0 particles", particles);
                })
                .step("stop the parent", FxPreloadScenario::stop)

                .step("play a control that was never preloaded", ctx -> play(ctx, "control"))
                .ticks(30)
                .step("the measurement catches what the control loads lazily", ctx -> {
                    var compiled = newPipelines(ctx, "compiledBeforePlay");
                    ctx.check("the control compiled programs while playing", !compiled.isEmpty(), "> 0", compiled.size());
                    var added = newTextures(ctx);
                    ctx.check("the control loaded its texture while playing", added.contains(texture("i")),
                            texture("i"), added);
                    checkDrew(ctx, "control");
                })
                .step("stop the control", FxPreloadScenario::stop)

                .step("preloadNow does everything on the spot", ctx -> {
                    ctx.put("compiledBeforePreloadNow", compiledPipelines());
                    var start = System.nanoTime();
                    var fx = FXHelper.preloadNow(fx("sync"));
                    ctx.log("preloadNow: %d ms, %d programs compiled".formatted((System.nanoTime() - start) / 1_000_000,
                            newPipelines(ctx, "compiledBeforePreloadNow").size()));
                    ctx.check("preloadNow returned the definition", fx != null, "an FX", fx);
                    ctx.check("nothing was queued for later frames", FXPreloader.pendingJobs() == 0,
                            0, FXPreloader.pendingJobs());
                    checkTexturesLoaded(ctx, List.of("j"));
                })
                .step("play the effect preloadNow warmed", ctx -> play(ctx, "sync"))
                .ticks(30)
                .step("playing it compiled and loaded nothing", ctx -> {
                    checkNothingLoaded(ctx, "sync");
                    checkDrew(ctx, "sync");
                })
                .step("stop the sync effect", FxPreloadScenario::stop)

                .step("keep an effect preloaded", ctx -> FXHelper.keepPreloaded(fx("kept")))
                .timeoutMs(60_000)
                .waitUntil("the kept effect is preloaded right away", ctx -> FXHelper.getCachedFX(fx("kept")) != null
                        && FXPreloader.pendingJobs() == 0)
                .step("reload the resources, which drops every loaded effect",
                        ctx -> ctx.put("reload", ctx.mc().reloadResourcePacks()))
                .timeoutMs(180_000)
                .waitUntil("the reload finishes", ctx -> ctx.<CompletableFuture<Void>>get("reload").isDone()
                        && ctx.mc().gui.overlay() == null)
                .frames(4)
                .step("the reload preloaded the kept effect behind the loading screen", ctx -> {
                    var kept = FXHelper.getCachedFX(fx("kept"));
                    ctx.check("the kept effect is loaded", kept != null, "cached", kept);
                    checkTexturesLoaded(ctx, List.of("k"));
                })
                .step("play the kept effect", ctx -> play(ctx, "kept"))
                .ticks(30)
                .step("playing it compiled and loaded nothing", ctx -> {
                    checkNothingLoaded(ctx, "kept");
                    checkDrew(ctx, "kept");
                })
                .step("stop the kept effect", FxPreloadScenario::stop);

        s.teardown("stop the effect and delete the fixtures", ctx -> {
            stop(ctx);
            // kept for the rest of the session otherwise: every later invalidation would preload it again in the
            // background, reading through the mounted packs while a later scenario rewrites or deletes one
            unkeep(fx("kept"));
            deleteTree(new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + ROOT));
            deleteTree(new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/textures/" + ROOT));
            var graph = graphFile();
            if (graph.delete()) ShaderGraphRuntime.invalidate(new FilePath(graph));
        });
    }

    private static void unkeep(Identifier fxLocation) {
        try {
            var kept = FXPreloader.class.getDeclaredField("KEPT");
            kept.setAccessible(true);
            ((Set<?>) kept.get(null)).remove(fxLocation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not stop keeping " + fxLocation, e);
        }
    }

    private static Identifier fx(String name) {
        return Photon.id(dir + "/" + name);
    }

    private static Identifier texture(String name) {
        return Photon.id("textures/" + dir + "/" + name + ".png");
    }

    private static void writeFixtures(TestContext ctx) {
        dir = ROOT + "/" + Long.toString(System.nanoTime() & 0xffffffL, 36);
        var colors = new int[]{0xff3050ff, 0xff30ff50, 0xffff5030, 0xffffd030, 0xff30d0ff, 0xffd030ff,
                0xffffffff, 0xff8080ff, 0xff80ff80, 0xffff8080, 0xff808080, 0xff40a0a0};
        var names = "abcdefghijkl";
        try {
            for (int i = 0; i < names.length(); i++) {
                var file = new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/textures/" + dir + "/" + names.charAt(i) + ".png");
                Files.createDirectories(file.getParentFile().toPath());
                try (var image = new NativeImage(16, 16, false)) {
                    image.fillRect(0, 0, 16, 16, colors[i]);
                    image.writeToFile(file.toPath());
                }
            }

            var spawner = particles("spawner", new TextureMaterial(texture("g")), false);
            spawner.config.emission.setEmissionRate(NumberFunction.constant(0));
            spawner.config.setStartLifetime(NumberFunction.constant(200));
            var burst = new EmissionSetting.Burst();
            burst.setCount(NumberFunction.constant(1));
            burst.cycles = 1;
            spawner.config.emission.getBursts().add(burst);
            spawner.config.subEmitters.setEnable(true);
            var sub = new SubEmittersSetting.Emitter();
            sub.setFxLocation(fx("child"));
            sub.setEvent(SubEmittersSetting.Event.Birth);
            sub.setEmitProbability(NumberFunction.constant(1));
            spawner.config.subEmitters.getEmitters().add(sub);

            var masked = particles("instanced_mask", new TextureMaterial(texture("b")), true);
            masked.config.renderer.getCustomMask().setEnable(true);

            var model = particles("model", new TextureMaterial(texture("c")), true);
            model.config.renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
            model.config.renderer.setModel(new MeshData(new ObjModelSource(Photon.id("models/sphere.obj"))));

            var trails = particles("trails", new TextureMaterial(texture("d")), true);
            trails.config.trails.setEnable(true);
            trails.config.trails.config.renderer.setUseGPUInstance(true);
            setMaterial(trails.config.trails.config.renderer.getMaterials(), new TextureMaterial(texture("e")));

            var ara = particles("ara", new TextureMaterial(texture("d")), false);
            ara.config.trails.setEnable(true);
            ara.config.trails.setTrailType(TrailsSetting.TrailType.ARA_TRAIL);
            ara.config.trails.araConfig.renderer.setUseGPUInstance(true);
            setMaterial(ara.config.trails.araConfig.renderer.getMaterials(), new TextureMaterial(texture("f")));

            var beamConfig = new BeamConfig();
            setMaterial(beamConfig.renderer.getMaterials(), new TextureMaterial(texture("g")));
            var beam = new BeamEmitter(beamConfig);
            beam.setName("beam");

            var graphSource = new KilaMaterial();
            graphSource.main.texture.texture = texture("l");
            var graph = particles("shader_graph", graphMaterial(graphSource), true);
            var missing = particles("missing_texture", new TextureMaterial(texture("m")), false);

            write("parent", particles("cpu", new TextureMaterial(texture("a")), false), masked, graph, missing,
                    particles("kila_instanced", KilaPresets.FIRE.create(), true),
                    particles("kila_stacked", KilaPresets.JET_EXHAUST.create(), false),
                    particles("custom_shader", new CustomShaderMaterial(Photon.id("circle")), true),
                    model, trails, ara, beam, spawner);
            var childKila = particles("child_kila", KilaPresets.SMOKE.create(), true);
            var childTexture = particles("child_texture", new TextureMaterial(texture("h")), true);
            // spawned by the parent as a runtime of its own, which stopping the parent does not end
            childKila.config.setLooping(false);
            childTexture.config.setLooping(false);
            write("child", childKila, childTexture);
            write("control", particles("control_custom", new CustomShaderMaterial(Photon.id("circle")), true),
                    particles("control_texture", new TextureMaterial(texture("i")), true),
                    particles("control_kila", KilaPresets.LIGHTNING.create(), false));
            write("sync", particles("sync_custom", new CustomShaderMaterial(Photon.id("circle")), true),
                    particles("sync_texture", new TextureMaterial(texture("j")), false),
                    particles("sync_kila", KilaPresets.PORTAL.create(), true));
            write("kept", particles("kept_custom", new CustomShaderMaterial(Photon.id("circle")), false),
                    particles("kept_texture", new TextureMaterial(texture("k")), true),
                    particles("kept_kila", KilaPresets.HEAT_HAZE.create(), true));
        } catch (IOException e) {
            ctx.require("the fixtures were written: " + e, false);
        }
    }

    /** {@code kila} exported as a shader graph resource, as a material reading it. */
    private static ShaderGraphMaterial graphMaterial(KilaMaterial kila) throws IOException {
        var file = graphFile();
        Files.createDirectories(file.getParentFile().toPath());
        var tag = new CompoundTag();
        tag.put("data", ShaderGraphResource.INSTANCE.serializeGraph(KilaGraphExport.export(kila).graph()));
        tag.putString("type", ShaderGraphResource.INSTANCE.getName());
        NbtIo.write(tag, file.toPath());
        ShaderGraphRuntime.invalidate(new FilePath(file));
        return new ShaderGraphMaterial(new FilePath(file));
    }

    private static File graphFile() {
        return new File(LDLib2.getAssetsDir(), "ldlib2/resources/global/" + dir.replace('/', '_')
                + ShaderGraphResource.INSTANCE.getFileExtension());
    }

    private static ParticleEmitter particles(String name, IMaterial material, boolean instanced) {
        var emitter = new ParticleEmitter();
        emitter.setName(name);
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(40);
        config.setStartLifetime(NumberFunction.constant(30));
        config.setStartSpeed(NumberFunction.constant(0.1f));
        config.setStartSize(new NumberFunction3(0.4, 0.4, 0.4));
        config.setMaxParticles(16);
        config.emission.setEmissionRate(NumberFunction.constant(0.5f));
        config.renderer.setUseGPUInstance(instanced);
        setMaterial(config.renderer.getMaterials(), material);
        return emitter;
    }

    private static void setMaterial(List<MaterialSetting> materials, IMaterial material) {
        materials.clear();
        materials.add(new MaterialSetting(material));
    }

    private static void write(String name, IFXObject... objects) throws IOException {
        var fx = new FX();
        fx.getFxData().objects().addAll(List.of(objects));
        var tag = fx.serializeNBT(Platform.getFrozenRegistry());
        tag.putInt("version", FXProject.VERSION);
        var file = new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + dir + "/" + name + FX.SUFFIX);
        Files.createDirectories(file.getParentFile().toPath());
        NbtIo.writeCompressed(tag, file.toPath());
    }

    private static void play(TestContext ctx, String name) {
        stop(ctx);
        ctx.put("compiledBeforePlay", compiledPipelines());
        ctx.put("texturesBeforePlay", Set.copyOf(textures(ctx).keySet()));
        var fx = FXHelper.getFX(fx(name));
        ctx.require("the " + name + " effect loads", fx != null);
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition().relative(player.getDirection(), 5));
        executor.setOffset(0, 1.5f, 0);
        executor.setAllowMulti(true);
        executor.start();
        ctx.put("executor", executor);
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    private static Map<Identifier, AbstractTexture> textures(TestContext ctx) {
        return ((TextureManagerAccessor) ctx.mc().getTextureManager()).photon$byPath();
    }

    /** The Photon pipelines the device has compiled. */
    private static Set<RenderPipeline> compiledPipelines() {
        var device = (DeviceCachesAccessor) ((GpuDeviceAccessor) RenderSystem.getDevice()).photon$backend();
        var compiled = new HashSet<RenderPipeline>();
        for (var pipeline : device.photon$pipelineCache().keySet()) {
            if (Photon.MOD_ID.equals(pipeline.getLocation().getNamespace())) compiled.add(pipeline);
        }
        return compiled;
    }

    /** The locations of the Photon pipelines compiled since the snapshot under {@code key}. */
    private static List<String> newPipelines(TestContext ctx, String key) {
        var before = ctx.<Set<RenderPipeline>>get(key);
        return compiledPipelines().stream().filter(pipeline -> !before.contains(pipeline))
                .map(pipeline -> pipeline.getLocation().toString()).sorted().toList();
    }

    private static Set<Identifier> newTextures(TestContext ctx) {
        var added = new TreeSet<>(textures(ctx).keySet());
        added.removeAll(ctx.<Set<Identifier>>get("texturesBeforePlay"));
        return added;
    }

    private static void checkTexturesLoaded(TestContext ctx, List<String> names) {
        var loaded = textures(ctx);
        var missing = names.stream().map(FxPreloadScenario::texture)
                .filter(location -> !loaded.containsKey(location)).toList();
        ctx.check("its textures were loaded", missing.isEmpty(), "all " + names.size(), "missing " + missing);
    }

    private static void checkNothingLoaded(TestContext ctx, String name) {
        var compiled = newPipelines(ctx, "compiledBeforePlay");
        ctx.check("no program was compiled while " + name + " played", compiled.isEmpty(), "none", compiled);
        var added = newTextures(ctx);
        ctx.check("no texture was loaded while " + name + " played", added.isEmpty(), "none", added);
    }

    private static void checkDrew(TestContext ctx, String name) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        var runtime = executor == null ? null : executor.getRuntime();
        ctx.require("the " + name + " effect is running", runtime != null);
        var idle = runtime.objects.values().stream()
                .filter(object -> object instanceof IParticleEmitter emitter && emitter.getParticleAmount() == 0)
                .map(IFXObject::getName).toList();
        ctx.check("every emitter of " + name + " has particles to draw", idle.isEmpty(), "none idle", idle);
    }

    private static int childParticles(TestContext ctx) {
        var engine = (ParticleEngineAccessor) ctx.mc().particleEngine;
        var total = 0;
        for (var queue : engine.getParticles().values()) {
            if (!(queue instanceof PhotonParticleGroup group)) continue;
            for (var particle : group.objects()) {
                if (particle instanceof IParticleEmitter emitter && particle instanceof IFXObject object
                        && object.getName().startsWith("child_")) {
                    total += emitter.getParticleAmount();
                }
            }
        }
        return total;
    }

    private static void deleteTree(File root) {
        if (!root.exists()) return;
        try (var walk = Files.walk(root.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
