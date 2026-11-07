package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.TimelineSection;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Circle;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.ParticleLifetime;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** The effects the wiki recordings show: built in code, so every recording can be made again anywhere. */
final class WikiDemos {
    private WikiDemos() {
    }

    /** What a demo is made of; built afresh for every use, so no two effects ever share an object. */
    static final class Demo {
        final List<IFXObject> objects = new ArrayList<>();
        final List<Track> tracks = new ArrayList<>();
        final List<TimelineSection> sections = new ArrayList<>();
        String release;

        /** A control track per object, named after it. */
        void clip(String track, IFXObject target, int start, int duration, Clip.ExitMode exit, Clip.SeamMode seam) {
            var control = PhotonTrackTypes.CONTROL.create();
            control.displayName(track);
            control.clips().add(new Clip(start, duration, 1).targetId(target.id()).exitMode(exit).seamMode(seam));
            tracks.add(control);
        }

        void fill(FX fx, boolean withSections) {
            var data = fx.getFxData();
            data.objects().addAll(objects);
            data.timeline().tracks().addAll(tracks);
            if (withSections) {
                data.timeline().sections().addAll(sections);
                data.timeline().releaseSection(release).releaseNow(true);
            }
        }

        FX fx() {
            var fx = new FX();
            fill(fx, true);
            return fx;
        }
    }

    /** Appear [0,20), Idle [20,60) forever, Vanish [60,90) as the release section. */
    static Demo magicCircle() {
        var ring = particles("Ring", 0, 0.05f, 0);
        ring.config.setLooping(false);
        ring.config.setDuration(10_000);
        ring.config.setMaxParticles(72);
        ring.config.setStartLifetime(NumberFunction.constant(24));
        ring.config.setLifetimeMode(ParticleLifetime.Mode.Loop);
        ring.config.setStartSize(uniform(NumberFunction.constant(0.16f)));
        ring.config.setStartColor(NumberFunction.color(0xFF40E0FF));
        ring.config.shape.setShape(circle(1.6f, 0));
        burst(ring, 72);
        pulse(ring, 0.55f, 1);

        var inner = particles("Inner Ring", 0, 0.06f, 0);
        inner.config.setLooping(false);
        inner.config.setDuration(10_000);
        inner.config.setMaxParticles(40);
        inner.config.setStartLifetime(NumberFunction.constant(30));
        inner.config.setLifetimeMode(ParticleLifetime.Mode.PingPong);
        inner.config.setStartSize(uniform(NumberFunction.constant(0.12f)));
        inner.config.setStartColor(NumberFunction.color(0xFFB070FF));
        inner.config.shape.setShape(circle(0.9f, 0));
        burst(inner, 40);
        pulse(inner, 0.2f, 1);

        var open = particles("Opening", 0, 0.1f, 0);
        open.config.setLooping(false);
        open.config.setDuration(20);
        open.config.setMaxParticles(60);
        open.config.setStartLifetime(NumberFunction.constant(16));
        open.config.setStartSpeed(NumberFunction.constant(3));
        open.config.setStartSize(uniform(NumberFunction.constant(0.14f)));
        open.config.setStartColor(NumberFunction.color(0xFFE0FFFF));
        open.config.shape.setShape(circle(0.2f, 0));
        burst(open, 60);
        fade(open, 0.3f);

        var runes = particles("Runes", 0, 0.1f, 0);
        runes.config.setMaxParticles(200);
        runes.config.setStartLifetime(NumberFunction.constant(30));
        runes.config.setStartSize(uniform(NumberFunction.constant(0.1f)));
        runes.config.setStartColor(NumberFunction.color(0xFF90F0FF));
        runes.config.shape.setShape(circle(1.5f, 1));
        runes.config.emission.setEmissionRate(NumberFunction.constant(0.8f));
        rise(runes, 0.04f);
        fade(runes, 0.5f);

        var wave = particles("Wave", 0, 0.12f, 0);
        wave.config.setLooping(false);
        wave.config.setDuration(40);
        wave.config.setMaxParticles(48);
        wave.config.setStartLifetime(NumberFunction.constant(18));
        wave.config.setStartSpeed(NumberFunction.constant(1.8f));
        wave.config.setStartSize(uniform(NumberFunction.constant(0.12f)));
        wave.config.setStartColor(NumberFunction.color(0xFF60C0FF));
        wave.config.shape.setShape(circle(0.3f, 0));
        burst(wave, 48);
        fade(wave, 0.4f);

        var vanish = particles("Vanish", 0, 0.05f, 0);
        vanish.config.setLooping(false);
        vanish.config.setDuration(30);
        vanish.config.setMaxParticles(120);
        vanish.config.setStartLifetime(NumberFunction.constant(25));
        vanish.config.setStartSize(uniform(NumberFunction.constant(0.15f)));
        vanish.config.setStartColor(NumberFunction.color(0xFFFFD060));
        vanish.config.shape.setShape(circle(1.6f, 0));
        burst(vanish, 120);
        rise(vanish, 0.08f);
        fade(vanish, 0.3f);

        var demo = new Demo();
        demo.objects.addAll(List.of(ring, inner, open, runes, wave, vanish));
        demo.clip("Ring", ring, 0, 60, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Inner Ring", inner, 8, 52, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Opening", open, 0, 20, Clip.ExitMode.CUT, Clip.SeamMode.AUTO);
        demo.clip("Runes", runes, 20, 40, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Wave", wave, 20, 40, Clip.ExitMode.CUT, Clip.SeamMode.RESTART);
        demo.clip("Vanish", vanish, 60, 30, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.sections.add(new TimelineSection("Appear", 0));
        demo.sections.add(new TimelineSection("Idle", 20).repeat(TimelineSection.FOREVER));
        demo.sections.add(new TimelineSection("Vanish", 60));
        demo.release = "Vanish";
        return demo;
    }

    /** Charge [0,15), Fire [15,35) forever, Stop [35,55) as the release section; the beam runs 7 blocks along +x. */
    static Demo laser() {
        var charge = particles("Charge", 0, 1.2f, 0);
        charge.config.setMaxParticles(120);
        charge.config.setStartLifetime(NumberFunction.constant(12));
        charge.config.setStartSpeed(NumberFunction.constant(-1.8f));
        charge.config.setStartSize(uniform(NumberFunction.constant(0.09f)));
        charge.config.setStartColor(NumberFunction.color(0xFFFFA040));
        charge.config.shape.setShape(sphere(1.1f, 0));
        charge.config.emission.setEmissionRate(NumberFunction.constant(4));
        fade(charge, 0.7f);

        var core = particles("Core", 0, 1.2f, 0);
        core.config.setLooping(false);
        core.config.setDuration(10_000);
        core.config.setMaxParticles(1);
        core.config.setStartLifetime(NumberFunction.constant(8));
        core.config.setLifetimeMode(ParticleLifetime.Mode.PingPong);
        core.config.setStartSize(uniform(NumberFunction.constant(0.7f)));
        core.config.setStartColor(NumberFunction.color(0xFFFFE0A0));
        core.config.shape.setShape(sphere(0.01f, 1));
        burst(core, 1);
        pulse(core, 0.6f, 1);

        var beam = new BeamEmitter();
        beam.setName("Beam");
        beam.transform().localPosition(new Vector3f(0, 1.2f, 0));
        beam.getConfig().getEnd().set(7, 0, 0);
        beam.getConfig().setWidth(NumberFunction.constant(0.4f));
        beam.getConfig().setColor(NumberFunction.color(0xFF80F0FF));

        var sparks = particles("Sparks", 7, 1.2f, 0);
        sparks.config.setMaxParticles(200);
        sparks.config.setStartLifetime(NumberFunction.constant(10));
        sparks.config.setStartSpeed(NumberFunction.constant(3));
        sparks.config.setStartSize(uniform(NumberFunction.constant(0.08f)));
        sparks.config.setStartColor(NumberFunction.color(0xFFFFE080));
        sparks.config.shape.setShape(sphere(0.15f, 1));
        sparks.config.emission.setEmissionRate(NumberFunction.constant(3));
        fade(sparks, 0.4f);

        var dissolve = particles("Dissolve", 3.5f, 1.2f, 0);
        dissolve.config.setLooping(false);
        dissolve.config.setDuration(20);
        dissolve.config.setMaxParticles(90);
        dissolve.config.setStartLifetime(NumberFunction.constant(20));
        dissolve.config.setStartSize(uniform(NumberFunction.constant(0.12f)));
        dissolve.config.setStartColor(NumberFunction.color(0xFF80F0FF));
        dissolve.config.shape.setShape(new Box());
        dissolve.config.shape.setScale(new NumberFunction3(7, 0.15f, 0.15f));
        burst(dissolve, 90);
        rise(dissolve, 0.03f);
        fade(dissolve, 0.2f);

        var demo = new Demo();
        demo.objects.addAll(List.of(charge, core, beam, sparks, dissolve));
        demo.clip("Charge", charge, 0, 15, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Core", core, 0, 35, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Beam", beam, 15, 20, Clip.ExitMode.CUT, Clip.SeamMode.AUTO);
        demo.clip("Sparks", sparks, 15, 20, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Dissolve", dissolve, 35, 20, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.sections.add(new TimelineSection("Charge", 0));
        demo.sections.add(new TimelineSection("Fire", 15).repeat(TimelineSection.FOREVER));
        demo.sections.add(new TimelineSection("Stop", 35));
        demo.release = "Stop";
        return demo;
    }

    /** Two jets on [0,30): Cut on the left, Drain on the right. */
    static Demo exitModes() {
        var cut = fountain("Cut", -1.4f, 0xFF40E0FF);
        var drain = fountain("Drain", 1.4f, 0xFFFFA040);
        var demo = new Demo();
        demo.objects.addAll(List.of(cut, drain));
        demo.clip("Cut", cut, 0, 30, Clip.ExitMode.CUT, Clip.SeamMode.AUTO);
        demo.clip("Drain", drain, 0, 30, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        return demo;
    }

    /**
     * Start [0,10), Loop [10,40) forever, End [40,60) as the release section. Two looping emitters, each a burst and
     * then a steady stream, stay on across Loop's seam: Auto on the left, Restart on the right.
     */
    static Demo seamModes() {
        var auto = pulseFountain("Auto", -1.4f, 0xFF40E0FF);
        var restart = pulseFountain("Restart", 1.4f, 0xFFFFA040);
        var finish = particles("Finish", 0, 0.1f, 0);
        finish.config.setLooping(false);
        finish.config.setDuration(20);
        finish.config.setMaxParticles(64);
        finish.config.setStartLifetime(NumberFunction.constant(16));
        finish.config.setStartSpeed(NumberFunction.constant(4));
        finish.config.setStartSize(uniform(NumberFunction.constant(0.14f)));
        finish.config.setStartColor(NumberFunction.color(0xFFFFE080));
        finish.config.shape.setShape(circle(0.3f, 0));
        burst(finish, 64);
        fade(finish, 0.3f);
        var demo = new Demo();
        demo.objects.addAll(List.of(auto, restart, finish));
        demo.clip("Auto", auto, 10, 30, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Restart", restart, 10, 30, Clip.ExitMode.DRAIN, Clip.SeamMode.RESTART);
        demo.clip("Finish", finish, 40, 20, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.sections.add(new TimelineSection("Start", 0));
        demo.sections.add(new TimelineSection("Loop", 10).repeat(TimelineSection.FOREVER));
        demo.sections.add(new TimelineSection("End", 40));
        demo.release = "End";
        return demo;
    }

    /** Three balls of particles, Once, Loop and PingPong from left to right, blue at birth and red at the end of life; stopped at 120. */
    static Demo lifetimeModes() {
        var once = ball("Once", -1.6f, ParticleLifetime.Mode.Once);
        var loop = ball("Loop", 0, ParticleLifetime.Mode.Loop);
        var pingPong = ball("PingPong", 1.6f, ParticleLifetime.Mode.PingPong);
        var demo = new Demo();
        demo.objects.addAll(List.of(once, loop, pingPong));
        demo.clip("Once", once, 0, 120, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("Loop", loop, 0, 120, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        demo.clip("PingPong", pingPong, 0, 120, Clip.ExitMode.DRAIN, Clip.SeamMode.AUTO);
        return demo;
    }

    // ---- building blocks -------------------------------------------------------------------------------

    /** A jet of particles straight up. */
    private static ParticleEmitter fountain(String name, float x, int color) {
        var e = particles(name, x, 0.1f, 0);
        e.config.setMaxParticles(300);
        e.config.setStartLifetime(NumberFunction.constant(24));
        e.config.setStartSpeed(NumberFunction.constant(3));
        e.config.setStartSize(uniform(NumberFunction.constant(0.22f)));
        e.config.setStartColor(NumberFunction.color(color));
        var cone = new Cone();
        cone.setAngle(12);
        cone.setRadius(0.15f);
        e.config.shape.setShape(cone);
        e.config.emission.setEmissionRate(NumberFunction.constant(4));
        fade(e, 0.5f);
        return e;
    }

    /** A puff at the start, then a thin steady jet: a restart shows as a new puff. */
    private static ParticleEmitter pulseFountain(String name, float x, int color) {
        var e = fountain(name, x, color);
        e.config.setStartLifetime(NumberFunction.constant(30));
        e.config.emission.setEmissionRate(NumberFunction.constant(0.6f));
        burst(e, 50);
        return e;
    }

    private static ParticleEmitter ball(String name, float x, ParticleLifetime.Mode mode) {
        var e = particles(name, x, 1, 0);
        e.config.setLooping(false);
        e.config.setDuration(10_000);
        e.config.setMaxParticles(24);
        e.config.setStartLifetime(NumberFunction.constant(40));
        e.config.setLifetimeMode(mode);
        e.config.setStartSize(uniform(NumberFunction.constant(0.24f)));
        e.config.shape.setShape(sphere(0.5f, 0));
        burst(e, 24);
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(gradient(
                new float[][]{{0, 0.25f, 0.55f, 1}, {0.5f, 0.95f, 0.95f, 0.95f}, {1, 1, 0.3f, 0.2f}},
                new float[][]{{0, 1}, {1, 1}}));
        return e;
    }

    private static ParticleEmitter particles(String name, float x, float y, float z) {
        var e = new ParticleEmitter();
        e.setName(name);
        e.transform().localPosition(new Vector3f(x, y, z));
        var c = e.config;
        c.setLooping(true);
        c.setDuration(40);
        c.setStartSpeed(NumberFunction.constant(0));
        c.emission.setEmissionRate(NumberFunction.constant(0));
        return e;
    }

    private static Circle circle(float radius, float thickness) {
        var circle = new Circle();
        circle.setRadius(radius);
        circle.setRadiusThickness(thickness);
        return circle;
    }

    private static Sphere sphere(float radius, float thickness) {
        var sphere = new Sphere();
        sphere.setRadius(radius);
        sphere.setRadiusThickness(thickness);
        return sphere;
    }

    /** Fade out over the end of the life, after holding full alpha for {@code hold} of it. */
    private static void fade(ParticleEmitter e, float hold) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(gradient(
                new float[][]{{0, 1, 1, 1}, {1, 1, 1, 1}},
                new float[][]{{0, 1}, {hold, 1}, {1, 0}}));
    }

    /** Brighter in the middle of each cycle: with a cyclic lifetime, a pulse that never ends. */
    private static void pulse(ParticleEmitter e, float low, float high) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(gradient(
                new float[][]{{0, 0.7f, 0.7f, 0.7f}, {0.5f, 1, 1, 1}, {1, 0.7f, 0.7f, 0.7f}},
                new float[][]{{0, low}, {0.5f, high}, {1, low}}));
    }

    private static void rise(ParticleEmitter e, float perTick) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setLinear(new NumberFunction3(0, perTick, 0));
    }

    private static NumberFunction3 uniform(NumberFunction f) {
        return new NumberFunction3(f, f, f);
    }

    private static Gradient gradient(float[][] rgb, float[][] alpha) {
        var g = new GradientColor();
        g.getRgbP().clear();
        for (var k : rgb) g.addRGB(k[0], k[1], k[2], k[3]);
        g.getAP().clear();
        for (var k : alpha) g.addAlpha(k[0], k[1]);
        return new Gradient(g);
    }

    /** One burst of {@code count} at the start. */
    private static void burst(ParticleEmitter e, int count) {
        var b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(count));
        b.cycles = 1;
        b.interval = 1;
        b.probability = 1;
        e.config.emission.getBursts().add(b);
    }
}
