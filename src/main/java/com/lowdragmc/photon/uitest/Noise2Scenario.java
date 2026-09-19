package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Particle integration needs the client registries; Unity field references remain JUnit tests. */
@LDLRegisterClient(name = "noise2", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class Noise2Scenario implements UIScenario {
    private ParticleEmitter emitter() {
        var emitter = new ParticleEmitter();
        emitter.setRandomSeed(12345);
        var config = emitter.config;
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(0.2f, 0.2f, 0.2f));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        config.noise2.setEnable(true);
        config.noise2.setStrengthAxes(new NumberFunction3(0, 0, 0));
        config.physics.setEnable(true);
        config.physics.setBounceChance(NumberFunction.constant(0));
        config.physics.setCollidedFriction(NumberFunction.constant(1));
        return emitter;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("substep collisions preserve the render origin", ctx -> {
            var emitter = emitter();
            var particle = new WallParticle(emitter);
            particle.setSimPos(0, 0, 0, true);
            particle.setInternalVelocity(new Vector3f(1, 0, 0));
            particle.syncOrigin();
            particle.updateTick(1);
            ctx.require("two collision sweeps", particle.collisionBoxes.size() == 2);
            ctx.check("first sweep starts at spawn", Math.abs(particle.collisionBoxes.get(0).getCenter().x) < 1e-6);
            ctx.check("second sweep starts at the first substep's endpoint",
                    Math.abs(particle.collisionBoxes.get(1).getCenter().x - 0.5) < 1e-6);
            ctx.check("particle stops at the wall", Math.abs(particle.getSimPosWithoutNoise(1).x - 0.65f) < 1e-6);
            ctx.check("render interpolation keeps the frame's origin", particle.getSimPosWithoutNoise(0).x == 0);
            ctx.check("collision was recorded", particle.isCollided());
        }).step("legacy simulation keeps a single step", ctx -> {
            var emitter = emitter();
            emitter.config.noise2.setEnable(false);
            var particle = new WallParticle(emitter);
            particle.collisionBoxes.clear();
            particle.setSimPos(0, 0, 0, true);
            particle.setInternalVelocity(new Vector3f(1, 0, 0));
            particle.updateTick(1);
            ctx.check("one collision sweep", particle.collisionBoxes.size() == 1);
            ctx.check("legacy collision still stops at the wall", Math.abs(particle.getSimPosWithoutNoise(1).x - 0.65f) < 1e-6);
        }).step("billboard supports both rotation dimensions", ctx -> {
            for (boolean rotation3D : new boolean[]{false, true}) {
                var emitter = emitter();
                emitter.config.noise2.setStrengthAxes(new NumberFunction3(1, 1, 1));
                emitter.config.noise2.setPositionAmount(NumberFunction.constant(0));
                emitter.config.noise2.setRotationAmount(NumberFunction.constant(30));
                emitter.config.noise2.setRotation3D(rotation3D);
                emitter.config.renderer.setRenderMode(ParticleRendererSetting.Mode.Billboard);
                var particle = new TileParticle(emitter, emitter.config);
                particle.setSimPos(new Vector3f(.31f, .57f, -.83f), true);
                emitter.runtime().noise2.advance(emitter, 1, true);
                particle.updateTick(1);
                var rotation = particle.getRealRotation(1);
                ctx.check("Z rotates in mode " + rotation3D, rotation.z != 0);
                ctx.check("X/Y follow rotation dimension " + rotation3D,
                        rotation3D ? rotation.x != 0 && rotation.y != 0 : rotation.x == 0 && rotation.y == 0);
            }
        }).step("emitter pauses scrolling across an empty interval", ctx -> {
            var emitter = emitter();
            emitter.config.noise2.setStrengthAxes(new NumberFunction3(1, 1, 1));
            emitter.config.noise2.setPositionAmount(NumberFunction.constant(0));
            emitter.config.noise2.setScrollSpeed(NumberFunction.constant(1));
            emitter.runtime().noise2.advance(emitter, 0, false);
            var position = new Vector3f(.31f, .57f, -.83f);
            var before = emitter.runtime().noise2.sample(position, 0, () -> .5f, 1, new Vector3f());
            emitter.emitParticle(1);
            var empty = emitter.runtime().noise2.sample(position, 0, () -> .5f, 1, new Vector3f());
            ctx.check("empty emitter does not advance noise", before.equals(empty));
            var particle = new TileParticle(emitter, emitter.config);
            emitter.emitParticle(particle);
            emitter.emitParticle(1);
            var active = emitter.runtime().noise2.sample(position, 0, () -> .5f, 1, new Vector3f());
            ctx.check("newly emitted particle starts scrolling", !empty.equals(active));
            particle.setRemoved(true);
            emitter.emitParticle(1);
            var paused = emitter.runtime().noise2.sample(position, 0, () -> .5f, 1, new Vector3f());
            ctx.check("removed particles do not keep scrolling", active.equals(paused));
        });
    }

    private static class WallParticle extends TileParticle {
        final List<AABB> collisionBoxes = new ArrayList<>();

        WallParticle(ParticleEmitter emitter) {
            super(emitter, emitter.config);
        }

        @Override
        protected Vec3 collideMovement(Vec3 movement, AABB box) {
            collisionBoxes.add(box);
            // Static wall fixture. The production caller chooses the origin and integrates the result.
            return new Vec3(Math.min(movement.x, Math.max(0, 0.75 - box.maxX)), movement.y, movement.z);
        }
    }
}
