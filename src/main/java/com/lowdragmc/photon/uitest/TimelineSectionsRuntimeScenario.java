package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
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
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.PhotonSignals;
import com.lowdragmc.photon.client.fx.timeline.SectionControl;
import com.lowdragmc.photon.client.fx.timeline.Signal;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.TimelineSection;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timeline sections at runtime with real objects: a forever section loops with what spans it carrying on and what
 * finished each pass starting over; jump, queue and advance; an end in the middle; a gentle destroy playing the release
 * section first; a start section; and an FX instance playing its own release when its effect is stopped.
 */
@LDLRegisterClient(name = "timeline_sections_runtime", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TimelineSectionsRuntimeScenario implements UIScenario {
    private static final String ROOT = "uitest_sections";
    private static String dir = ROOT;
    private static final UUID BASE = id("base");
    private static final UUID APPEAR = id("appear");
    private static final UUID IDLE = id("idle");
    private static final UUID RUNES = id("runes");
    private static final UUID PULSE = id("pulse");
    private static final UUID VANISH = id("vanish");
    private static final UUID FIZZLE = id("fizzle");
    private static final UUID BLAST = id("blast");
    private static final UUID I = id("i");

    @Override
    public void define(ScenarioBuilder s) {
        s.step("look straight ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .step("listen to signals", TimelineSectionsRuntimeScenario::listen)

                .step("play the circle: Appear, Idle (forever), Vanish (release)", ctx -> play(ctx, circle(), null))
                .timeoutMs(20_000)
                .waitUntil("Idle has looped three times", ctx -> runtime(ctx).sections().pass() >= 3)
                .step("Idle loops: what spans it carries on, what finished each pass starts over", ctx -> {
                    var sections = runtime(ctx).sections();
                    ctx.check("in Idle", "Idle".equals(sections.current()), "Idle", sections.current());
                    ctx.check("the clock stays inside Idle", clock(ctx) >= 20 && clock(ctx) < 60, "[20, 60)", clock(ctx));
                    ctx.check("the base spanning every section never restarted", emitter(ctx, BASE).getAge() > 120,
                            "> 120", emitter(ctx, BASE).getAge());
                    ctx.check("the idle loop spanning Idle never restarted", emitter(ctx, IDLE).getAge() > 100,
                            "> 100", emitter(ctx, IDLE).getAge());
                    ctx.check("the one-shot runes started over this pass", emitter(ctx, RUNES).getAge() < 40,
                            "< 40", emitter(ctx, RUNES).getAge());
                    ctx.check("the pulse set to restart every pass did", emitter(ctx, PULSE).getAge() < 40,
                            "< 40", emitter(ctx, PULSE).getAge());
                    ctx.check("Idle's signal fired every pass", fired(ctx, "loop") >= 3, ">= 3", fired(ctx, "loop"));
                    ctx.check("Vanish's start never fired", fired(ctx, "vanish") == 0, 0, fired(ctx, "vanish"));
                    ctx.check("the vanish emitter is off since its flash at the start", !emitter(ctx, VANISH).isActive(),
                            "inactive", "active");
                })
                .step("jump to Vanish", ctx -> ctx.check("accepted", runtime(ctx).sections().jumpTo("Vanish"), true, false))
                .ticks(3)
                .step("Vanish plays, the idle loop drains, the base carries on", ctx -> {
                    ctx.check("in Vanish", "Vanish".equals(runtime(ctx).sections().current()), "Vanish",
                            runtime(ctx).sections().current());
                    ctx.check("Vanish started", emitter(ctx, VANISH).isActive(), "active", "inactive");
                    ctx.check("the idle loop drains, not cut", emitter(ctx, IDLE).isActive() && emitter(ctx, IDLE).isWindingDown(),
                            "active, winding down", emitter(ctx, IDLE).isActive());
                    ctx.check("the base did not restart", emitter(ctx, BASE).getAge() > 120, "> 120", emitter(ctx, BASE).getAge());
                    ctx.check("Vanish's start fired once", fired(ctx, "vanish") == 1, 1, fired(ctx, "vanish"));
                    ctx.check("a release after reaching it does nothing", !runtime(ctx).sections().release(), false, true);
                })
                .timeoutMs(20_000)
                .waitUntil("the effect finishes after Vanish", ctx -> runtime(ctx).isFinished())

                .step("play the circle again and queue Vanish in Idle", ctx -> play(ctx, circle(), null))
                .waitUntil("in Idle", ctx -> clock(ctx) >= 25)
                .step("queue", ctx -> ctx.check("accepted", runtime(ctx).sections().queue("Vanish"), true, false))
                .ticks(3)
                .step("the pass in progress plays out", ctx ->
                        ctx.check("still Idle", "Idle".equals(runtime(ctx).sections().current()), "Idle", runtime(ctx).sections().current()))
                .timeoutMs(20_000)
                .waitUntil("Vanish at the seam", ctx -> "Vanish".equals(runtime(ctx).sections().current()))
                .step("it came in at Vanish's start", ctx ->
                        ctx.check("at 60", clock(ctx) >= 60 && clock(ctx) < 62, "60", clock(ctx)))

                .step("play the circle again and advance in Idle", ctx -> play(ctx, circle(), null))
                .waitUntil("in Idle", ctx -> clock(ctx) >= 25)
                .step("advance", ctx -> ctx.check("accepted", runtime(ctx).sections().advance(), true, false))
                .timeoutMs(20_000)
                .waitUntil("Idle stops repeating", ctx -> "Vanish".equals(runtime(ctx).sections().current()))
                .step("advancing went on to Vanish, as Idle's then says", ctx ->
                        ctx.check("one pass only", runtime(ctx).sections().pass() == 0, 0, runtime(ctx).sections().pass()))

                .step("play the charge and jump to Fizzle, which ends the timeline", ctx -> {
                    play(ctx, charge(), null);
                    runtime(ctx).sections().jumpTo("Fizzle");
                })
                .timeoutMs(20_000)
                .waitUntil("the timeline ran into Fizzle's end", ctx -> runtime(ctx).timelinePlayer.isFinished())
                .step("Blast, after Fizzle in time, never played", ctx -> {
                    ctx.check("Fizzle played", emitter(ctx, FIZZLE).getAge() > 0, "> 0", emitter(ctx, FIZZLE).getAge());
                    ctx.check("Blast never started", !emitter(ctx, BLAST).isActive() && emitter(ctx, BLAST).getParticleAmount() == 0,
                            "inactive", emitter(ctx, BLAST).isActive());
                    ctx.check("the clock stopped at the end", clock(ctx) <= 30, "<= 30", clock(ctx));
                })

                .step("play the circle and stop it gently in Idle", ctx -> play(ctx, circle(), null))
                .waitUntil("in Idle", ctx -> clock(ctx) >= 30)
                .step("destroy(false)", ctx -> {
                    runtime(ctx).destroy(false);
                    ctx.check("no longer valid to its owner", !runtime(ctx).isValid(), "invalid", "valid");
                    ctx.check("but playing its release", runtime(ctx).isReleasing(), "releasing", "not");
                    ctx.put("stoppedAt", gameTime(ctx));
                })
                .ticks(3)
                .step("the release section plays", ctx -> {
                    ctx.check("in Vanish", "Vanish".equals(runtime(ctx).sections().current()), "Vanish", runtime(ctx).sections().current());
                    ctx.check("Vanish started", emitter(ctx, VANISH).isActive(), "active", "inactive");
                    ctx.check("nothing else is accepted", !runtime(ctx).sections().jumpTo("Idle"), false, true);
                })
                .timeoutMs(20_000)
                .waitUntil("then everything winds down and ends", ctx -> runtime(ctx).isFinished())
                .step("it ended through the release", ctx -> {
                    var took = gameTime(ctx) - ctx.<Long>get("stoppedAt");
                    ctx.check("it took at least Vanish's length", took >= 25, ">= 25", took);
                    ctx.check("no longer releasing", !runtime(ctx).isReleasing(), "done", "releasing");
                })

                .step("play the circle from Idle", ctx -> play(ctx, circle(), "Idle"))
                .ticks(2)
                .step("it started in Idle", ctx -> {
                    ctx.check("in Idle", "Idle".equals(runtime(ctx).sections().current()), "Idle", runtime(ctx).sections().current());
                    ctx.check("Appear never played", !emitter(ctx, APPEAR).isActive() && emitter(ctx, APPEAR).getAge() == 0,
                            "never", emitter(ctx, APPEAR).getAge());
                    ctx.check("the idle loop runs", emitter(ctx, IDLE).isActive(), "active", "inactive");
                })

                .step("play the circle on a block and jump by command", ctx -> play(ctx, circle(), null))
                .waitUntil("in Idle", ctx -> clock(ctx) >= 25)
                .step("/photon fx section block <pos> jump Vanish", ctx -> {
                    var pos = ctx.<BlockEffectExecutor>get("executor").pos;
                    ctx.requirePlayer().connection.sendCommand("photon fx section block %d %d %d jump Vanish"
                            .formatted(pos.getX(), pos.getY(), pos.getZ()));
                })
                .ticks(8)
                .step("it jumped at once, long before Idle's seam", ctx ->
                        ctx.check("in Vanish", "Vanish".equals(runtime(ctx).sections().current()), "Vanish",
                                runtime(ctx).sections().current()))

                .step("write a holder with the circle in it", TimelineSectionsRuntimeScenario::writeFixtures)
                .step("play the holder", ctx -> {
                    var fx = FXHelper.getFX(Photon.id(dir + "/holder"));
                    ctx.require("the holder loads", fx != null);
                    play(ctx, fx, null);
                })
                .timeoutMs(20_000)
                .waitUntil("the circle inside is in Idle", ctx -> "Idle".equals(instanceSections(ctx).current()))
                .step("the instance takes requests for its own sections", ctx ->
                        ctx.check("queue accepted", instanceSections(ctx).queue("Idle"), true, false))
                .step("stop the holder gently: it has no release of its own", ctx -> runtime(ctx).destroy(false))
                .ticks(3)
                .step("the circle inside plays its own release", ctx -> {
                    ctx.check("the circle is in Vanish", "Vanish".equals(instanceSections(ctx).current()), "Vanish",
                            instanceSections(ctx).current());
                    var vanish = emitter(ctx, InstanceIds.xor(I, VANISH));
                    // it flashed at the start and was cut, frozen with its particles: only a restart makes it young again
                    ctx.check("its Vanish started again, fresh", vanish.isActive() && vanish.getAge() < 8,
                            "active, age < 8", vanish.getAge());
                })
                .timeoutMs(20_000)
                .waitUntil("and then it all ends", ctx -> runtime(ctx).isFinished())
                .step("stop", TimelineSectionsRuntimeScenario::stop);

        s.teardown("stop and delete the fixtures", ctx -> {
            stop(ctx);
            PhotonSignals.Listener listener = ctx.get("listener");
            if (listener != null) PhotonSignals.unregister(listener);
            deleteTree(new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + ROOT));
            FXHelper.clearCache();
        });
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    /** Appear [0,20), Idle [20,60) forever, Vanish [60,85) as the release. */
    private static FX circle() {
        var base = emitter("base", BASE, true, 20);
        var appear = emitter("appear", APPEAR, false, 10);
        var idle = emitter("idle", IDLE, true, 20);
        var runes = emitter("runes", RUNES, false, 5);
        runes.config.setDuration(10);
        var pulse = emitter("pulse", PULSE, true, 10);
        var vanish = emitter("vanish", VANISH, false, 10);
        vanish.config.setDuration(10);
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 90, 1).targetId(BASE));
        control.clips().add(new Clip(0, 20, 1).targetId(APPEAR));
        control.clips().add(new Clip(20, 40, 1).targetId(IDLE).exitMode(Clip.ExitMode.DRAIN));
        control.clips().add(new Clip(20, 40, 1).targetId(RUNES));
        control.clips().add(new Clip(20, 40, 1).targetId(PULSE).seamMode(Clip.SeamMode.RESTART));
        control.clips().add(new Clip(0, 10, 1).targetId(VANISH));
        control.clips().add(new Clip(60, 25, 1).targetId(VANISH));
        var signals = (SignalTrack) PhotonTrackTypes.SIGNAL.create();
        signals.displayName("circle");
        signals.signals().add(new Signal(20, "loop"));
        signals.signals().add(new Signal(60, "vanish"));
        var fx = fx(List.of(base, appear, idle, runes, pulse, vanish), List.of(control, signals));
        var timeline = fx.getFxData().timeline();
        timeline.sections().add(new TimelineSection("Appear", 0));
        timeline.sections().add(new TimelineSection("Idle", 20).repeat(TimelineSection.FOREVER));
        timeline.sections().add(new TimelineSection("Vanish", 60));
        timeline.releaseSection("Vanish");
        return fx;
    }

    /** Start [0,10), Charge [10,20) forever, Fizzle [20,30) ending the timeline, Blast [30,40). */
    private static FX charge() {
        var fizzle = emitter("fizzle", FIZZLE, false, 5);
        var blast = emitter("blast", BLAST, false, 5);
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(20, 10, 1).targetId(FIZZLE));
        control.clips().add(new Clip(30, 10, 1).targetId(BLAST));
        var fx = fx(List.of(fizzle, blast), List.of(control));
        var timeline = fx.getFxData().timeline();
        timeline.sections().add(new TimelineSection("Start", 0));
        timeline.sections().add(new TimelineSection("Charge", 10).repeat(TimelineSection.FOREVER));
        timeline.sections().add(new TimelineSection("Fizzle", 20).then(TimelineSection.Then.END));
        timeline.sections().add(new TimelineSection("Blast", 30));
        return fx;
    }

    private static void writeFixtures(TestContext ctx) {
        dir = ROOT + "/" + Long.toString(System.nanoTime() & 0xffffffL, 36);
        FXHelper.clearCache();
        try {
            write("circle", circle());
            var instance = new FXInstanceObject(Photon.id(dir + "/circle"));
            instance.setName("i");
            instance.transform()._setInternalID(I);
            write("holder", fx(List.of(instance), List.of()));
        } catch (IOException e) {
            throw new IllegalStateException("could not write the fixtures", e);
        }
    }

    private static void write(String name, FX fx) throws IOException {
        var tag = fx.serializeNBT(Platform.getFrozenRegistry());
        tag.putInt("version", FXProject.VERSION);
        var file = new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + dir + "/" + name + FX.SUFFIX);
        Files.createDirectories(file.getParentFile().toPath());
        NbtIo.writeCompressed(tag, file.toPath());
    }

    private static FX fx(List<? extends IFXObject> objects, List<Track> tracks) {
        var fx = new FX();
        fx.getFxData().objects().addAll(objects);
        fx.getFxData().timeline().tracks().addAll(tracks);
        return fx;
    }

    private static ParticleEmitter emitter(String name, UUID id, boolean looping, int lifetime) {
        var emitter = new ParticleEmitter();
        emitter.setName(name);
        emitter.transform()._setInternalID(id);
        var config = emitter.config;
        config.setLooping(looping);
        config.setDuration(40);
        config.setStartLifetime(NumberFunction.constant(lifetime));
        config.setMaxParticles(20);
        config.emission.setEmissionRate(NumberFunction.constant(1));
        return emitter;
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("timeline_sections_runtime/" + name).getBytes());
    }

    // ---- playing -----------------------------------------------------------------------------------

    private static void play(TestContext ctx, FX fx, String startSection) {
        stop(ctx);
        ctx.<Map<String, Integer>>get("fired").clear();
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition().relative(player.getDirection(), 5));
        executor.setOffset(0, 1.5f, 0);
        executor.setAllowMulti(true);
        executor.setStartSection(startSection);
        ctx.put("executor", executor);
        executor.start();
        ctx.put("runtime", executor.getRuntime());
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    private static void listen(TestContext ctx) {
        var fired = new ConcurrentHashMap<String, Integer>();
        ctx.put("fired", fired);
        PhotonSignals.Listener listener = (effect, channel, name, data, time) -> {
            if (effect != null && effect == ctx.get("executor")) fired.merge(name, 1, Integer::sum);
        };
        PhotonSignals.register(listener);
        ctx.put("listener", listener);
    }

    private static int fired(TestContext ctx, String name) {
        return ctx.<Map<String, Integer>>get("fired").getOrDefault(name, 0);
    }

    private static FXRuntime runtime(TestContext ctx) {
        return ctx.get("runtime");
    }

    private static double clock(TestContext ctx) {
        return runtime(ctx).timelinePlayer.localTime();
    }

    private static long gameTime(TestContext ctx) {
        return ctx.requirePlayer().level().getGameTime();
    }

    private static ParticleEmitter emitter(TestContext ctx, UUID id) {
        var object = runtime(ctx).objects.get(id);
        ctx.require("object " + id + " is in the runtime", object instanceof ParticleEmitter);
        return (ParticleEmitter) object;
    }

    private static SectionControl instanceSections(TestContext ctx) {
        var object = runtime(ctx).objects.get(I);
        ctx.require("the instance is in the runtime", object instanceof FXInstanceObject);
        var sections = ((FXInstanceObject) object).sections();
        ctx.require("the instance has a timeline", sections != null);
        return sections;
    }

    private static void deleteTree(File root) {
        if (!root.exists()) return;
        try (var walk = Files.walk(root.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
