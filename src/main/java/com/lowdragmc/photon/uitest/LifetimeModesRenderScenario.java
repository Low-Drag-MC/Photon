package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaDriver;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.export.KilaGraphExport;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.ParticleLifetime;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.client.render.PhotonBakeWork;
import com.lowdragmc.photon.client.render.PhotonOit;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Looping and ping-pong particle lifetimes as 26.3 draws them. Four quads in a row, one particle each: a looping and a
 * ping-pong one with a 40-tick cycle, and two that play once, with lifetimes of 200 and 66, as references. The effect
 * is frozen once they are 50 ticks old: the looping quad is then at T 0.25 and the ping-pong one at 0.75, on its way
 * back, where the references are at 0.25 and 0.76. Each quad's brightness follows its T: colour over lifetime for
 * the texture material, and the T channel ({@code addition_gpu_data.t}) for a Kila material and the shader graph
 * exported from it. The references calibrate brightness against T, and the cyclic quads must land on that line.
 * <p>
 * Every material is drawn on the CPU path and GPU-instanced, with classic and with improved transparency. The texture
 * material is also drawn next to a crowd that is hidden behind a wall and makes the frame bake on worker threads. A
 * last round stops the effect without force: the cyclic quads play out the cycle they are in, then vanish. Under a
 * shader pack, only the order of the brightnesses is checked.
 */
@LDLRegisterClient(name = "photon_lifetime_modes_render", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifetimeModesRenderScenario implements UIScenario {

    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final String PACK = "BSL_v10.1.3.zip";
    private static final File GRAPH = new File(LDLib2.getAssetsDir(),
            "ldlib2/resources/global/uitest_photon_lifetime_t" + ShaderGraphResource.INSTANCE.getFileExtension());
    /** The age the effect is frozen at. */
    private static final int FREEZE_AGE = 50;
    /** How far from the line the references draw a cyclic quad may be, in 8-bit green levels. */
    private static final int TOLERANCE = 28;

    /** One quad: its emitter's x on the row, its lifetime (one cycle) and mode. */
    private enum Slot {
        LOOP(-3, 40, ParticleLifetime.Mode.Loop),
        PING_PONG(-1, 40, ParticleLifetime.Mode.PingPong),
        ONCE_LOW(1, 200, ParticleLifetime.Mode.Once),
        ONCE_HIGH(3, 66, ParticleLifetime.Mode.Once);

        final float x;
        final int lifetime;
        final ParticleLifetime.Mode mode;
        final UUID id;

        Slot(float x, int lifetime, ParticleLifetime.Mode mode) {
            this.x = x;
            this.lifetime = lifetime;
            this.mode = mode;
            this.id = UUID.nameUUIDFromBytes(("photon_lifetime_modes_render/" + name()).getBytes());
        }
    }

    private enum Material {
        /** White texture, coloured black to green over the lifetime: T read on the CPU. */
        TEXTURE,
        /** Kila, green, intensity = T: T read from the particle data on the GPU. */
        KILA,
        /** The Kila material exported to a shader graph: T read by the graph's particle data node. */
        GRAPH
    }

    /** {@code crowd}: thousands of particles behind the wall, so the frame bakes on worker threads. */
    private record Round(String tag, Material material, boolean instanced, boolean crowd, boolean oit, boolean pack) {
    }

    private static final List<Round> ROUNDS = rounds();

    private static List<Round> rounds() {
        var rounds = new ArrayList<Round>();
        for (var oit : new boolean[]{false, true}) {
            for (var material : Material.values()) {
                for (var instanced : new boolean[]{false, true}) {
                    var tag = (oit ? "oit_" : "classic_") + material.name().toLowerCase() + (instanced ? "_instanced" : "_cpu");
                    rounds.add(new Round(tag, material, instanced, false, oit, false));
                    if (material == Material.TEXTURE) {
                        rounds.add(new Round(tag + "_parallel", material, instanced, true, oit, false));
                    }
                }
            }
        }
        rounds.add(new Round("pack_texture_cpu", Material.TEXTURE, false, false, false, true));
        rounds.add(new Round("pack_texture_instanced", Material.TEXTURE, true, false, false, true));
        rounds.add(new Round("pack_kila_instanced", Material.KILA, true, false, false, true));
        rounds.add(new Round("pack_graph_instanced", Material.GRAPH, true, false, false, true));
        return List.copyOf(rounds);
    }

    /** What a round saw when its capture was taken. */
    private record Seen(Map<Slot, Float> t, int oitDraws, int parallelDrains) {
    }

    private static final Map<String, Seen> SEEN = new ConcurrentHashMap<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render").requiresWorld(true).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui, no section fade-in, no bloom", ctx -> {
                    HiddenGui.hide(ctx.mc());
                    LightTestConfig.set(ctx.mc().options.chunkSectionFadeInTime(), 0.0);
                    LightTestConfig.set(PhotonConfig.INSTANCE.enableBloom, false);
                    LightTestConfig.set(PhotonConfig.INSTANCE.parallelBake, true);
                })
                // Iris turns improved transparency off while its shaders are enabled
                .step("shaders off", ctx -> {
                    if (IrisCompat.isModInstalled()) LightTestConfig.selectShaderPack(null);
                })
                .frames(5)
                .server("build the scene", sc -> {
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    build(sc.level(), origin);
                    TestClock.set(sc.level(), 6000);
                    var player = sc.player();
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo(sc.level(), origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + 0.5,
                            Set.<Relative>of(), 0f, 0f, true);
                })
                .serverTicks(10)
                .step("write the graph", LifetimeModesRenderScenario::writeGraph);

        for (var oit : new boolean[]{false, true}) {
            s.step("improved transparency " + (oit ? "on" : "off"), ctx -> {
                        LightTestConfig.set(PhotonConfig.INSTANCE.orderIndependentTransparency, true);
                        LightTestConfig.set(ctx.mc().options.improvedTransparency(), oit);
                    })
                    .ticks(5)
                    .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                    .frames(10)
                    .step("the level is drawn " + (oit ? "with" : "without") + " order-independent transparency",
                            ctx -> ctx.require("improved transparency is " + (oit ? "on" : "off"),
                                    ctx.mc().gameRenderer.useImprovedTransparency() == oit));
            for (var round : ROUNDS) {
                if (round.oit() == oit && !round.pack()) frozenRound(s, round, 10);
            }
            if (oit) drainRound(s);
        }

        if (LightTestConfig.hasShaderPack(PACK)) {
            int[] waited = {0};
            s.step("improved transparency off",
                            ctx -> LightTestConfig.set(ctx.mc().options.improvedTransparency(), false))
                    .step("load " + PACK, ctx -> LightTestConfig.selectShaderPack(PACK))
                    .step("wait for the pack", ctx -> {
                        if (!IrisCompat.isUsingShaderPack() && ++waited[0] < 600) ctx.repeat("pack");
                    })
                    .frames(120);
            for (var round : ROUNDS) {
                if (round.pack()) frozenRound(s, round, 30);
            }
        } else {
            s.step("no " + PACK, ctx -> LightTestConfig.missingShaderPack(ctx, PACK));
        }

        s.step("every quad is as bright as its T", LifetimeModesRenderScenario::check);
        s.teardown("stop the effect", ctx -> {
            stop(ctx);
            SEEN.clear();
            LightTestConfig.restore();
            //noinspection ResultOfMethodCallIgnored
            GRAPH.delete();
            ShaderGraphRuntime.invalidate(new FilePath(GRAPH));
        });
        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
    }

    /** Play the round, freeze it at {@link #FREEZE_AGE}, capture it and note what it drew with. */
    private static void frozenRound(ScenarioBuilder s, Round round, int frames) {
        s.step("effect: " + round.tag(), ctx -> start(ctx, round))
                .timeoutMs(20_000)
                .waitUntil(round.tag() + ": its particles are " + FREEZE_AGE + " ticks old", ctx -> {
                    var loop = particle(ctx, Slot.LOOP);
                    return loop != null && loop.getAge() >= FREEZE_AGE;
                })
                .step("freeze " + round.tag(), ctx -> {
                    runtime(ctx).setRate(0);
                    ctx.put("drainsBefore", PhotonBakeWork.parallelDrains());
                })
                .frames(frames)
                .screenshot(round.tag())
                .step("stop " + round.tag(), ctx -> {
                    SEEN.put(round.tag(), new Seen(tOf(ctx), PhotonOit.drawsLastFrame(),
                            PhotonBakeWork.parallelDrains() - ctx.<Integer>get("drainsBefore")));
                    stop(ctx);
                });
    }

    /** Stopped without force at {@link #FREEZE_AGE}: the cyclic quads play out their cycle, to age 80, then go. */
    private static void drainRound(ScenarioBuilder s) {
        var round = new Round("drain", Material.TEXTURE, true, false, true, false);
        s.step("effect: drain", ctx -> start(ctx, round))
                .timeoutMs(20_000)
                .waitUntil("drain: its particles are " + FREEZE_AGE + " ticks old", ctx -> {
                    var loop = particle(ctx, Slot.LOOP);
                    return loop != null && loop.getAge() >= FREEZE_AGE;
                })
                .step("stop it without force", ctx -> {
                    ctx.put("drainStop", tOf(ctx));
                    runtime(ctx).destroy(false);
                })
                .timeoutMs(20_000)
                .waitUntil("drain: 66 ticks old", ctx -> {
                    var loop = particle(ctx, Slot.LOOP);
                    return loop == null || loop.getAge() >= 66;
                })
                .screenshot("drain_mid")
                .step("note the cycle's progress", ctx -> {
                    ctx.put("drainMid", tOf(ctx));
                    var loop = particle(ctx, Slot.LOOP);
                    ctx.put("drainLoopAlive", loop != null);
                })
                .timeoutMs(20_000)
                .waitUntil("drain: the cyclic particles died", ctx -> {
                    var loop = particle(ctx, Slot.LOOP);
                    var pingPong = particle(ctx, Slot.PING_PONG);
                    var low = particle(ctx, Slot.ONCE_LOW);
                    if (low != null && (loop != null || pingPong != null)) ctx.put("drainLastAge", low.getAge());
                    return loop == null && pingPong == null;
                })
                .frames(5)
                .screenshot("drain_after")
                .step("note what is left", ctx -> {
                    ctx.put("drainAfter", tOf(ctx));
                    stop(ctx);
                });
    }

    // ---- the effect --------------------------------------------------------------------------------

    private static void start(TestContext ctx, Round round) {
        stop(ctx);
        var fx = new FX();
        for (var slot : Slot.values()) {
            fx.getFxData().objects().add(quad(slot, round));
        }
        if (round.crowd()) {
            fx.getFxData().objects().add(crowd(round, -4));
            fx.getFxData().objects().add(crowd(round, 4));
        }
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), ORIGIN.get().offset(0, 0, 9));
        executor.setOffset(0.5, 2.6, 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("effect", executor);
    }

    private static ParticleEmitter quad(Slot slot, Round round) {
        var emitter = new ParticleEmitter();
        emitter.setName(slot.name().toLowerCase());
        emitter.transform()._setInternalID(slot.id);
        emitter.transform().localPosition(new Vector3f(slot.x, 0, 0));
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(slot.lifetime));
        config.setLifetimeMode(slot.mode);
        config.setStartSpeed(NumberFunction.constant(0));
        // a half size: 1.4 blocks across, so the quads 2 apart do not overlap — where they did, improved
        // transparency averaged the two (they are at one depth), and a quad's own colour was not what was sampled
        config.setStartSize(new NumberFunction3(0.7f, 0.7f, 0.7f));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        burst(config.emission, 1);
        if (round.material() == Material.TEXTURE) {
            config.setStartColor(NumberFunction.color(0xFFFFFFFF));
            config.colorOverLifetime.setEnable(true);
            config.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFF000000, 0xFF00FF00)));
        } else {
            config.setStartColor(NumberFunction.color(0xFFFFFFFF));
        }
        render(config.renderer, material(round.material()), round.instanced());
        return emitter;
    }

    /** 2500 small particles behind the wall: drawn, but hidden by it. */
    private static ParticleEmitter crowd(Round round, float x) {
        var emitter = new ParticleEmitter();
        emitter.setName("crowd");
        emitter.transform().localPosition(new Vector3f(x, 0, 6));
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(0.1f, 0.1f, 0.1f));
        config.setStartColor(NumberFunction.color(0xFFFF00FF));
        config.setMaxParticles(2500);
        config.shape.setScale(new NumberFunction3(2, 2, 1));
        burst(config.emission, 2500);
        render(config.renderer, new TextureMaterial(Photon.id("textures/particle/white.png")), round.instanced());
        return emitter;
    }

    private static void burst(EmissionSetting emission, int count) {
        emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(count));
        burst.cycles = 1;
        emission.getBursts().add(burst);
    }

    private static void render(ParticleRendererSetting renderer, IMaterial material, boolean instanced) {
        renderer.getMaterials().clear();
        renderer.getMaterials().add(new MaterialSetting(material));
        renderer.setLayer(RendererSetting.Layer.Translucent);
        renderer.setUseGPUInstance(instanced);
    }

    private static IMaterial material(Material material) {
        return switch (material) {
            case TEXTURE -> new TextureMaterial(Photon.id("textures/particle/white.png"));
            case KILA -> kila();
            case GRAPH -> new ShaderGraphMaterial(new FilePath(GRAPH));
        };
    }

    private static KilaMaterial kila() {
        var material = new KilaMaterial();
        material.main.texture.texture = KilaTextures.WHITE;
        material.color = new HDRColor(0f, 1f, 0f, 1f, 1f);
        material.intensity.value = 0;
        material.intensity.bind(KilaDriver.Source.LIFE, 1);
        return material;
    }

    private static void writeGraph(TestContext ctx) {
        try {
            Files.createDirectories(GRAPH.getParentFile().toPath());
            var tag = new CompoundTag();
            tag.put("data", ShaderGraphResource.INSTANCE.serializeGraph(KilaGraphExport.export(kila()).graph()));
            tag.putString("type", ShaderGraphResource.INSTANCE.getName());
            NbtIo.write(tag, GRAPH.toPath());
            ShaderGraphRuntime.invalidate(new FilePath(GRAPH));
        } catch (IOException e) {
            ctx.require("the graph was written: " + e, false);
        }
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("effect");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("effect");
    }

    private static FXRuntime runtime(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("effect");
        ctx.require("an effect is playing", executor != null && executor.getRuntime() != null);
        return executor.getRuntime();
    }

    /** The slot's living particle. */
    @Nullable
    private static TileParticle particle(TestContext ctx, Slot slot) {
        if (!(runtime(ctx).objects.get(slot.id) instanceof ParticleEmitter emitter)) return null;
        for (var queue : emitter.getParticles().values()) {
            for (var particle : queue) {
                if (particle instanceof TileParticle tile && !tile.isRemoved()) return tile;
            }
        }
        return null;
    }

    /** Each living slot's T, mid-frame. */
    private static Map<Slot, Float> tOf(TestContext ctx) {
        var t = new EnumMap<Slot, Float>(Slot.class);
        for (var slot : Slot.values()) {
            var particle = particle(ctx, slot);
            if (particle != null) t.put(slot, particle.getT(0.5f));
        }
        return t;
    }

    /** The camera at the origin's top, looking along +z at the row; a wall behind it hides the crowd. */
    private static void build(ServerLevel level, BlockPos origin) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -2; z <= 20; z++) {
                for (int y = 1; y <= 10; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        for (int x = -10; x <= 10; x++) {
            for (int y = 1; y <= 10; y++) {
                level.setBlock(origin.offset(x, y, 13), Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    // ---- checks ------------------------------------------------------------------------------------

    private static Vec3 centre(Slot slot) {
        return Vec3.atLowerCornerOf(ORIGIN.get()).add(0.5 + slot.x, 2.6, 9.5);
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static void check(TestContext ctx) {
        for (var round : ROUNDS) {
            var tag = round.tag();
            var seen = SEEN.get(tag);
            if (seen == null) {
                if (!round.pack()) ctx.check(tag + " ran", false, "ran", "missing");
                continue;
            }
            var image = ScreenshotCompare.load(ctx, tag);
            if (image == null) {
                ctx.check(tag + " was captured", false, "an image", "missing");
                continue;
            }
            var t = seen.t();
            if (t.size() != Slot.values().length) {
                ctx.check(tag + ": every quad is alive when frozen", false, Slot.values().length, t.keySet());
                continue;
            }
            // the CPU side: where the modes put T
            ctx.check(tag + ": the looping quad started over", t.get(Slot.LOOP) < 0.4f, "< 0.4", t.get(Slot.LOOP));
            ctx.check(tag + ": the ping-pong quad is on its way back", t.get(Slot.PING_PONG) > 0.6f, "> 0.6",
                    t.get(Slot.PING_PONG));
            var rgb = new EnumMap<Slot, Integer>(Slot.class);
            for (var slot : Slot.values()) {
                rgb.put(slot, FrameSeamsScenario.sample(ctx, image, centre(slot)));
            }
            var describe = describe(rgb, t);
            var low = green(rgb.get(Slot.ONCE_LOW));
            var high = green(rgb.get(Slot.ONCE_HIGH));
            ctx.check(tag + ": the references are as bright as their T", high > low + 60, "brighter at T "
                    + t.get(Slot.ONCE_HIGH) + " than at " + t.get(Slot.ONCE_LOW), describe);
            if (round.pack()) {
                // the pack lights and grades the frame: only the order holds
                ctx.check(tag + ": the ping-pong quad is brighter than the looping one",
                        green(rgb.get(Slot.PING_PONG)) > green(rgb.get(Slot.LOOP)) + 40, "brighter", describe);
                continue;
            }
            var slope = (high - low) / (t.get(Slot.ONCE_HIGH) - t.get(Slot.ONCE_LOW));
            for (var slot : new Slot[]{Slot.LOOP, Slot.PING_PONG}) {
                var expected = low + (t.get(slot) - t.get(Slot.ONCE_LOW)) * slope;
                var actual = green(rgb.get(slot));
                ctx.check(tag + ": the " + slot.name().toLowerCase() + " quad is drawn at its T",
                        Math.abs(actual - expected) <= TOLERANCE,
                        String.format("%.0f +- %d", expected, TOLERANCE), actual + " (" + describe + ")");
            }
            if (round.oit()) {
                ctx.check(tag + ": it went through the OIT passes", seen.oitDraws() > 0, "> 0", seen.oitDraws());
            }
            if (round.crowd()) {
                if (Runtime.getRuntime().availableProcessors() > 3) {
                    ctx.check(tag + ": the frame baked on worker threads", seen.parallelDrains() > 0, "> 0",
                            seen.parallelDrains());
                } else {
                    ctx.log(tag + ": too few cores for the workers; the parallel bake is not checked");
                }
            }
        }
        checkDrain(ctx);
    }

    private static void checkDrain(TestContext ctx) {
        Map<Slot, Float> stop = ctx.get("drainStop");
        Map<Slot, Float> mid = ctx.get("drainMid");
        Map<Slot, Float> after = ctx.get("drainAfter");
        if (stop == null || mid == null || after == null) {
            ctx.check("the drain round ran", false, "ran", "missing");
            return;
        }
        boolean loopAlive = Boolean.TRUE.equals(ctx.get("drainLoopAlive"));
        ctx.check("drain: the looping quad lived on after the stop", loopAlive, "alive", loopAlive ? "alive" : "gone");
        if (stop.containsKey(Slot.LOOP) && mid.containsKey(Slot.LOOP)) {
            ctx.check("drain: its T went on up through the cycle", mid.get(Slot.LOOP) > stop.get(Slot.LOOP) + 0.3f,
                    "> " + (stop.get(Slot.LOOP) + 0.3f), mid.get(Slot.LOOP));
        }
        Float lastAge = ctx.get("drainLastAge");
        ctx.check("drain: the cyclic quads died at the end of their cycle, at age 80",
                lastAge != null && lastAge >= 78 && lastAge <= 82, "80", lastAge);
        ctx.check("drain: the long reference lives on", after.containsKey(Slot.ONCE_LOW), "alive", after.keySet());
        var midImage = ScreenshotCompare.load(ctx, "drain_mid");
        var afterImage = ScreenshotCompare.load(ctx, "drain_after");
        if (midImage == null || afterImage == null) {
            ctx.check("the drain was captured", false, "images", "missing");
            return;
        }
        var midLoop = FrameSeamsScenario.sample(ctx, midImage, centre(Slot.LOOP));
        ctx.check("drain: the stopped looping quad is still drawn", FrameSeamsScenario.greenish(midLoop), "green",
                FrameSeamsScenario.hex(midLoop));
        var afterLoop = FrameSeamsScenario.sample(ctx, afterImage, centre(Slot.LOOP));
        var afterPingPong = FrameSeamsScenario.sample(ctx, afterImage, centre(Slot.PING_PONG));
        var afterLow = FrameSeamsScenario.sample(ctx, afterImage, centre(Slot.ONCE_LOW));
        ctx.check("drain: then the looping quad is gone", !FrameSeamsScenario.greenish(afterLoop), "the wall",
                FrameSeamsScenario.hex(afterLoop));
        ctx.check("drain: and the ping-pong one", !FrameSeamsScenario.greenish(afterPingPong), "the wall",
                FrameSeamsScenario.hex(afterPingPong));
        ctx.check("drain: the long reference is still drawn", FrameSeamsScenario.greenish(afterLow), "green",
                FrameSeamsScenario.hex(afterLow));
    }

    private static String describe(Map<Slot, Integer> rgb, Map<Slot, Float> t) {
        var out = new StringBuilder();
        for (var slot : Slot.values()) {
            if (!out.isEmpty()) out.append(", ");
            out.append(slot.name().toLowerCase()).append(" T ").append(String.format("%.3f", t.get(slot)))
                    .append(' ').append(FrameSeamsScenario.hex(rgb.get(slot)));
        }
        return out.toString();
    }
}
