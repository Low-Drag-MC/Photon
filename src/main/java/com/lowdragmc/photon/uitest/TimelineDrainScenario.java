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
import com.lowdragmc.photon.client.fx.timeline.Signal;
import com.lowdragmc.photon.client.fx.timeline.SignalTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.FXObject;
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
import java.util.UUID;

/**
 * A control clip that drains: leaving it stops the object starting anything new and keeps it running and showing
 * until what it shows has ended, then stops it (a cut clip stops and hides it at once). Nothing new starts inside a
 * draining object; it starts again fresh when its clip comes round again. An FX instance's source draining its root
 * drains what the instance placed and ends with that, not with the instance's own clock. A destroyed effect's
 * remnants keep their light.
 */
@LDLRegisterClient(name = "timeline_drain", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TimelineDrainScenario implements UIScenario {
    private static final String ROOT = "uitest_drain";
    private static String dir = ROOT;
    private static final UUID D = id("d");
    private static final UUID C = id("c");
    private static final UUID P = id("p");
    private static final UUID K = id("k");
    private static final UUID S = id("s");
    private static final UUID R = id("r");
    private static final UUID I = id("i");

    @Override
    public void define(ScenarioBuilder s) {
        s.step("look straight ahead", ctx -> {
                    var player = ctx.requirePlayer();
                    player.setXRot(0);
                    player.xRotO = 0;
                })

                .step("play D draining and C cut, both on [0,20)", ctx -> play(ctx, drainOrCut()))
                .timeoutMs(20_000)
                .timeoutMs(20_000)
                .waitUntil("23 ticks after it started", ctx -> elapsed(ctx) >= 23)
                .step("after its clip D still runs but emits nothing new; C stopped at once", ctx -> {
                    var d = emitter(ctx, D);
                    ctx.check("D runs", d.isActive(), "active", "inactive");
                    ctx.check("D still shows its particles", d.getParticleAmount() > 0, "> 0", d.getParticleAmount());
                    ctx.check("D is winding down", d.isWindingDown(), "winding down", "running");
                    ctx.check("C is stopped", !emitter(ctx, C).isActive(), "inactive", "active");
                    ctx.put("countAt23", d.getParticleAmount());
                })
                .timeoutMs(20_000)
                .waitUntil("30 ticks after it started", ctx -> elapsed(ctx) >= 30)
                .step("D emitted nothing since", ctx -> {
                    int before = ctx.get("countAt23");
                    var now = emitter(ctx, D).getParticleAmount();
                    ctx.check("no more particles than at 23", now <= before, "<= " + before, now);
                })
                .timeoutMs(20_000)
                .waitUntil("70 ticks after it started", ctx -> elapsed(ctx) >= 70)
                .step("D's particles ended and so did D", ctx -> {
                    var d = emitter(ctx, D);
                    ctx.check("no particle is left", d.getParticleAmount() == 0, 0, d.getParticleAmount());
                    ctx.check("D is stopped", !d.isActive(), "inactive", "active");
                    ctx.check("the effect finished", runtime(ctx).isFinished(), "finished", "running");
                })

                .step("play P draining on [0,20), its child K emitting and its child S cut on [0,10) and on again on [30,40)",
                        ctx -> play(ctx, drainingParent()))
                .timeoutMs(20_000)
                .waitUntil("15 ticks after it started", ctx -> elapsed(ctx) >= 15)
                .step("S was cut at 10, its particles kept hidden", ctx -> {
                    var sEmitter = emitter(ctx, S);
                    ctx.check("S is off", !sEmitter.isActive(), "inactive", "active");
                    ctx.check("S holds particles", sEmitter.getParticleAmount() > 0, "> 0", sEmitter.getParticleAmount());
                    ctx.put("sAt15", sEmitter.getParticleAmount());
                })
                .timeoutMs(20_000)
                .waitUntil("33 ticks after it started", ctx -> elapsed(ctx) >= 33)
                .step("inside a draining P nothing new starts: S's next clip neither started nor showed it", ctx -> {
                    ctx.check("P still drains, K's particles alive", emitter(ctx, K).getParticleAmount() > 0, "> 0",
                            emitter(ctx, K).getParticleAmount());
                    var sEmitter = emitter(ctx, S);
                    ctx.check("S stayed off", !sEmitter.isActive(), "inactive", "active");
                    ctx.check("S stayed hidden", !sEmitter.isVisible(), "hidden", "visible");
                    int before = ctx.get("sAt15");
                    ctx.check("S still holds just what it had when cut", sEmitter.getParticleAmount() == before, before,
                            sEmitter.getParticleAmount());
                    ctx.check("S stayed wound down", sEmitter.isWindingDown(), "winding down", "restarted");
                })

                .step("play R on [0,10) draining and again on [50,70)", ctx -> play(ctx, drainThenAgain()))
                .timeoutMs(20_000)
                .waitUntil("55 ticks after it started", ctx -> elapsed(ctx) >= 55)
                .step("its next clip started it again, fresh", ctx -> {
                    var r = emitter(ctx, R);
                    ctx.check("R runs", r.isActive(), "active", "inactive");
                    ctx.check("R is no longer winding down", !r.isWindingDown(), "running", "winding down");
                    ctx.check("R emits again", r.getParticleAmount() > 0, "> 0", r.getParticleAmount());
                })

                .step("write an instance whose source drains its root on [0,20)", TimelineDrainScenario::writeFixtures)
                .step("play it", ctx -> {
                    var fx = FXHelper.getFX(Photon.id(dir + "/drain_outer"));
                    ctx.require("the outer effect loads", fx != null);
                    play(ctx, fx);
                })
                .timeoutMs(20_000)
                .waitUntil("33 ticks after it started", ctx -> elapsed(ctx) >= 33)
                .step("what it placed drains, and nothing new starts in it", ctx -> {
                    var k = emitter(ctx, InstanceIds.xor(I, K));
                    var placedS = emitter(ctx, InstanceIds.xor(I, S));
                    ctx.check("the placed K still shows its particles", k.getParticleAmount() > 0, "> 0", k.getParticleAmount());
                    ctx.check("the placed S did not start", placedS.getParticleAmount() == 0, 0, placedS.getParticleAmount());
                    ctx.check("nor was it switched on", !placedS.isActive(), "inactive", "active");
                    ctx.check("the instance is still on", instance(ctx).isSelfActive(), "active", "inactive");
                })
                .timeoutMs(20_000)
                .waitUntil("75 ticks after it started", ctx -> elapsed(ctx) >= 75)
                .step("the drain ended with what it placed, not with its clock", ctx -> {
                    var instance = instance(ctx);
                    var nested = instance.nestedPlayer();
                    ctx.check("the placed K's particles ended", emitter(ctx, InstanceIds.xor(I, K)).getParticleAmount() == 0,
                            0, emitter(ctx, InstanceIds.xor(I, K)).getParticleAmount());
                    ctx.check("the instance's root is off", !instance.isSelfActive(), "inactive", "active");
                    ctx.check("while its own clock runs on to 200", nested != null && !nested.isFinished()
                            && nested.localTime() < 200, "< 200", nested == null ? "no player" : nested.localTime());
                })

                .step("play D emitting, then destroy it gently at 10", ctx -> play(ctx, drainOrCut()))
                .timeoutMs(20_000)
                .waitUntil("10 ticks after it started", ctx -> elapsed(ctx) >= 10)
                .step("destroy", ctx -> {
                    ctx.put("stoppedAt", gameTime(ctx));
                    runtime(ctx).destroy(false);
                })
                .waitUntil("3 ticks pass", ctx -> gameTime(ctx) >= ctx.<Long>get("stoppedAt") + 3)
                .step("its remnants still show, light included", ctx -> {
                    var d = emitter(ctx, D);
                    ctx.check("D's particles drain", d.getParticleAmount() > 0, "> 0", d.getParticleAmount());
                    ctx.check("not discarded, so they keep their light", !discarded(d), "kept", "discarded");
                    runtime(ctx).destroy(true);
                    ctx.put("stoppedAt", gameTime(ctx));
                })
                .waitUntil("5 ticks pass", ctx -> gameTime(ctx) >= ctx.<Long>get("stoppedAt") + 5)
                .step("dropped at once, it is discarded", ctx ->
                        ctx.check("discarded", discarded(emitter(ctx, D)), "discarded", "kept"))
                .step("stop", TimelineDrainScenario::stop);

        s.teardown("stop the effect and delete the fixtures", ctx -> {
            stop(ctx);
            deleteTree(new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + ROOT));
            FXHelper.clearCache();
        });
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private static FX drainOrCut() {
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 20, 1).targetId(D).exitMode(Clip.ExitMode.DRAIN));
        control.clips().add(new Clip(0, 20, 1).targetId(C));
        return fx(List.of(emitter("d", D, 40), emitter("c", C, 40)), List.of(control));
    }

    private static FX drainingParent() {
        var p = named(new EmptyFXObject(), "p", P);
        var k = emitter("k", K, 40);
        var sEmitter = emitter("s", S, 40);
        under(k, p);
        under(sEmitter, p);
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 20, 1).targetId(P).exitMode(Clip.ExitMode.DRAIN));
        control.clips().add(new Clip(0, 10, 1).targetId(S));
        control.clips().add(new Clip(30, 10, 1).targetId(S));
        return fx(List.of(p, k, sEmitter), List.of(control));
    }

    private static FX drainThenAgain() {
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 10, 1).targetId(R).exitMode(Clip.ExitMode.DRAIN));
        control.clips().add(new Clip(50, 20, 1).targetId(R));
        return fx(List.of(emitter("r", R, 20)), List.of(control));
    }

    private static void writeFixtures(TestContext ctx) {
        dir = ROOT + "/" + Long.toString(System.nanoTime() & 0xffffffL, 36);
        FXHelper.clearCache();
        var k = emitter("k", K, 40);
        var sEmitter = emitter("s", S, 40);
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 20, 1).targetId(FXRuntime.ROOT_UUID).exitMode(Clip.ExitMode.DRAIN));
        control.clips().add(new Clip(30, 10, 1).targetId(S));
        var signals = (SignalTrack) PhotonTrackTypes.SIGNAL.create();
        signals.signals().add(new Signal(200, "late"));
        var instance = named(new FXInstanceObject(Photon.id(dir + "/drain_src")), "i", I);
        try {
            write("drain_src", fx(List.of(k, sEmitter), List.of(control, signals)));
            write("drain_outer", fx(List.of(instance), List.of()));
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

    private static ParticleEmitter emitter(String name, UUID id, int lifetime) {
        var emitter = named(new ParticleEmitter(), name, id);
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(40);
        config.setStartLifetime(NumberFunction.constant(lifetime));
        config.setMaxParticles(40);
        config.emission.setEmissionRate(NumberFunction.constant(1));
        return emitter;
    }

    private static <T extends IFXObject> T named(T object, String name, UUID id) {
        object.setName(name);
        object.transform()._setInternalID(id);
        return object;
    }

    private static void under(IFXObject child, IFXObject parent) {
        child.transform()._setInternalParentID(parent.id());
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("timeline_drain/" + name).getBytes());
    }

    // ---- playing -----------------------------------------------------------------------------------

    private static void play(TestContext ctx, FX fx) {
        stop(ctx);
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition().relative(player.getDirection(), 5));
        executor.setOffset(0, 1.5f, 0);
        executor.setAllowMulti(true);
        ctx.put("executor", executor);
        executor.start();
        ctx.put("runtime", executor.getRuntime());
        ctx.put("playedAt", gameTime(ctx));
    }

    private static long elapsed(TestContext ctx) {
        return gameTime(ctx) - ctx.<Long>get("playedAt");
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) {
            executor.getRuntime().destroy(true);
        }
        ctx.state().remove("executor");
    }

    private static FXRuntime runtime(TestContext ctx) {
        return ctx.get("runtime");
    }

    private static long gameTime(TestContext ctx) {
        return ctx.requirePlayer().level().getGameTime();
    }

    private static ParticleEmitter emitter(TestContext ctx, UUID id) {
        var object = runtime(ctx).objects.get(id);
        ctx.require("object " + id + " is in the runtime", object instanceof ParticleEmitter);
        return (ParticleEmitter) object;
    }

    private static FXInstanceObject instance(TestContext ctx) {
        var object = runtime(ctx).objects.get(I);
        ctx.require("the instance is in the runtime", object instanceof FXInstanceObject);
        return (FXInstanceObject) object;
    }

    private static boolean discarded(FXObject object) {
        try {
            var method = FXObject.class.getDeclaredMethod("isDiscarded");
            method.setAccessible(true);
            return (boolean) method.invoke(object);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(File root) {
        if (!root.exists()) return;
        try (var walk = Files.walk(root.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
