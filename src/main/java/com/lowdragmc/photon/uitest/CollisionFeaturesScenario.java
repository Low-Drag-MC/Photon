package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.collision.CollisionEvent;
import com.lowdragmc.photon.client.collision.CollisionQuery;
import com.lowdragmc.photon.client.collision.CollisionWorld;
import com.lowdragmc.photon.client.collision.Contact;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.collider.ColliderConfig;
import com.lowdragmc.photon.client.gameobject.collider.ColliderObject;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.GroundSnapSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.Set;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * The collision features end to end in the real client level, on the {@link CollisionBenchScenario} site.
 * Emitters are built in code and ticked by hand, as in the bench.
 */
@LDLRegisterClient(name = "collision_features", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CollisionFeaturesScenario implements UIScenario {
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();
    private static final AtomicReference<BlockPos> TOP = new AtomicReference<>();
    private static final AtomicReference<UUID> STAND = new AtomicReference<>();
    private static final float SIZE = 0.2f;
    /** the collision half extent of a {@link #SIZE} particle */
    private static final double HALF = SIZE / 2;
    private static final double EPS = 1e-3;

    @Override
    public void configure(ScenarioOptions options) {
        options.scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.server("build the site", sc -> {
                    var level = sc.level();
                    var origin = LightSite.claim(sc.player());
                    ORIGIN.set(origin);
                    CollisionBenchScenario.buildSite(level, origin);
                    // in front of the armor stand, for the beam that has to choose between them
                    level.setBlock(origin.offset(17, 1, 22), Blocks.STONE.defaultBlockState(), 2);
                    // alone in their section, and neither has a collision shape: a torch and a layer of snow
                    level.setBlock(origin.offset(30, 20, 30), Blocks.TORCH.defaultBlockState(), 2);
                    level.setBlock(origin.offset(32, 20, 30), Blocks.SNOW.defaultBlockState(), 2);
                    // a pool two blocks deep: only its top is a surface
                    for (int x = 4; x < 8; x++)
                        for (int z = 47; z < 51; z++)
                            for (int y = -1; y <= 0; y++)
                                level.setBlock(origin.offset(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                    var stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    if (stand != null) {
                        stand.snapTo(origin.getX() + 20.5, origin.getY() + 1, origin.getZ() + 22.5, 0, 0);
                        stand.setNoGravity(true);
                        level.addFreshEntity(stand);
                        STAND.set(stand.getUUID());
                    }
                    sc.player().teleportTo(level, origin.getX() + 24.5, origin.getY() + 14, origin.getZ() - 8.5, Set.<Relative>of(), 0, 30, true);
                })
                .serverTicks(40)
                .ticks(10)
                .waitUntil("site and armor stand loaded on the client", ctx -> ctx.level() != null
                        && ctx.level().getBlockState(CollisionBenchScenario.platformBlock(ORIGIN.get())).is(Blocks.STONE)
                        && ctx.level().getBlockState(ORIGIN.get().offset(17, 1, 22)).is(Blocks.STONE)
                        && stand(ctx) != null)
                .step("a plane collider holds particles up", CollisionFeaturesScenario::planeCollider)
                .step("a rising plane carries them up", CollisionFeaturesScenario::risingPlane)
                .step("Local particles ride a plane that moves with them", CollisionFeaturesScenario::ridingPlane)
                .step("a turned box collider is never entered", CollisionFeaturesScenario::boxCollider)
                .step("a trigger sphere reports every crossing", CollisionFeaturesScenario::triggerSphere)
                .step("a trigger can kill on entry", CollisionFeaturesScenario::triggerKill)
                .step("born inside a trigger: entered, then left", CollisionFeaturesScenario::triggerBornInside)
                .step("particles land on an armor stand", CollisionFeaturesScenario::entitySolid)
                .step("an entity as a trigger", CollisionFeaturesScenario::entityTrigger)
                .step("water surfaces", CollisionFeaturesScenario::fluidSurface)
                .step("block filters", CollisionFeaturesScenario::blockFilter)
                .step("ground snap", CollisionFeaturesScenario::groundSnap)
                .step("ground snap along the emitter's -Y", CollisionFeaturesScenario::groundSnapSideways)
                .step("ground snap with nothing in reach", CollisionFeaturesScenario::groundSnapMissing)
                .step("beam raycasts end where vanilla's clip does", CollisionFeaturesScenario::beamBlocks)
                .step("beam raycasts against entities", CollisionFeaturesScenario::beamEntities)
                .step("Ara trail points lie on the ground", CollisionFeaturesScenario::araTrail)
                .step("Local-space particles dragged into the ground", CollisionFeaturesScenario::localDrag)
                .step("stuck particles stay where they hit a wall", CollisionFeaturesScenario::stickToWall)
                .step("stuck particles ride a collider, and fall when it goes", CollisionFeaturesScenario::stickToCollider)
                .step("stuck particles turn with a turning collider", CollisionFeaturesScenario::stickToTurningCollider)
                .step("stuck particles that grow and wobble hold on as blocks change", CollisionFeaturesScenario::stuckThroughBlockChanges)
                .step("ground snap under a low ceiling", CollisionFeaturesScenario::groundSnapUnderCeiling)
                .step("a slowed effect's collider still sweeps its whole tick", CollisionFeaturesScenario::slowedSweep)
                .step("sub-emitters filter collisions by surface", CollisionFeaturesScenario::surfaceFilter)
                .step("an executor's owner is not collided with", CollisionFeaturesScenario::executorOwner)
                .step("entity type lists: only, and except on top", CollisionFeaturesScenario::entityTypeLists)
                .step("entities around where the effect starts are ignored", CollisionFeaturesScenario::sourceExclusion)
                .step("impacts de-duplicate per emitter and per particle", CollisionFeaturesScenario::dedupeImpacts)
                .step("emitters sharing a group de-duplicate together", CollisionFeaturesScenario::dedupeGroups)
                .step("impacts and contact deaths de-duplicate apart", CollisionFeaturesScenario::dedupeImpactsAndDeaths)
                .step("de-duplication and surfaces gate real sub-emitter spawns", CollisionFeaturesScenario::dedupeSubEmitters)
                .step("parallel updates match serial ones", CollisionFeaturesScenario::determinism)
                .step("collision settings survive saving and loading", CollisionFeaturesScenario::persistence)
                .server("a stone in the topmost section of the world", sc -> {
                    var origin = ORIGIN.get();
                    var top = new BlockPos(origin.getX() + 2, sc.level().getMaxY(), origin.getZ() + 2);
                    TOP.set(top);
                    sc.level().setBlock(top, Blocks.STONE.defaultBlockState(), 2);
                })
                .serverTicks(5)
                .waitUntil("the stone reached the client", ctx -> ctx.level().getBlockState(TOP.get()).is(Blocks.STONE))
                .step("the topmost section collides", CollisionFeaturesScenario::topSection);
        s.teardownServer("put the world back", sc -> {
            var top = TOP.getAndSet(null);
            if (top != null) sc.level().setBlock(top, Blocks.AIR.defaultBlockState(), 2);
            var id = STAND.getAndSet(null);
            var stand = id == null ? null : sc.level().getEntity(id);
            if (stand != null) stand.discard();
            var origin = ORIGIN.get();
            if (origin != null) {
                CollisionBenchScenario.clearSite(sc.level(), origin);
                for (int x = 4; x < 8; x++)
                    for (int z = 47; z < 51; z++) {
                        sc.level().setBlock(origin.offset(x, -1, z), Blocks.DIRT.defaultBlockState(), 2);
                        sc.level().setBlock(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    }
            }
            LightSite.release(sc.player());
        });
    }


    private static void planeCollider(TestContext ctx) {
        var emitter = column(400, 3);
        var plane = new ColliderObject();
        plane.config.setShape(ColliderConfig.Shape.Plane);
        plane.transform().localPosition(new Vector3f(0, -3, 0));
        var impacts = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            if (event.type() == CollisionEvent.Type.IMPACT && event.target() == plane) impacts.incrementAndGet();
        });
        var rig = new Rig(ctx.level(), at(6, 7, 6), emitter, plane);
        rig.tick(60);
        int landings = impacts.getAndSet(0);
        rig.tick(40);
        var particles = alive(emitter);
        ctx.check("all 400 rest on the plane, their half extent above it",
                particles.size() == 400 && spread(particles, y(4) + HALF) < EPS, y(4) + HALF, describe(particles));
        ctx.check("each landing was one impact on the plane", landings == 400, 400, landings);
        ctx.check("resting on it reports nothing more", impacts.get() == 0, 0, impacts.get());
        rig.remove();
    }

    private static void risingPlane(TestContext ctx) {
        var emitter = column(200, 2);
        var plane = new ColliderObject();
        plane.config.setShape(ColliderConfig.Shape.Plane);
        plane.transform().localPosition(new Vector3f(0, -3, 0));
        var rig = new Rig(ctx.level(), at(14, 7, 14), emitter, plane);
        rig.tick(40);
        // then the plane climbs a twentieth of a block a tick for two seconds
        for (int i = 1; i <= 40; i++) {
            plane.transform().localPosition(new Vector3f(0, -3 + i * 0.05f, 0));
            rig.tick(1);
        }
        var particles = alive(emitter);
        double top = y(6) + HALF;
        ctx.check("all 200 rode the plane up to its new height", particles.size() == 200 && spread(particles, top) < 0.01,
                top, describe(particles));
        rig.remove();
    }

    private static void ridingPlane(TestContext ctx) {
        var emitter = column(200, 2);
        emitter.config.setSimulationSpace(ParticleConfig.Space.Local);
        var plane = new ColliderObject();
        plane.config.setShape(ColliderConfig.Shape.Plane);
        plane.transform().localPosition(new Vector3f(0, -3, 0));
        var rig = new Rig(ctx.level(), at(22, 7, 6), emitter, plane);
        rig.tick(40);
        // the whole effect climbs, as one following a jumping entity would
        for (int i = 1; i <= 20; i++) {
            rig.runtime.root.updatePos(at(22, 7 + i * 0.1, 6));
            rig.tick(1);
        }
        // a particle the plane had shoved would still be flying a couple of ticks after it stopped
        rig.tick(2);
        var particles = alive(emitter);
        double top = y(6) + HALF;
        ctx.check("all 200 still rest on the plane, not flung off it", particles.size() == 200 && spread(particles, top) < 0.01,
                top, describe(particles));
        rig.remove();
    }

    private static void boxCollider(TestContext ctx) {
        var emitter = column(400, 4);
        var box = new ColliderObject();
        box.config.setShape(ColliderConfig.Shape.Box);
        box.config.setSize(new Vector3f(2, 1, 2));
        box.transform().localPosition(new Vector3f(0, -4.5f, 0));
        box.transform().localRotation(new Quaternionf().rotationY((float) Math.toRadians(45)));
        var rig = new Rig(ctx.level(), at(6, 7, 14), emitter, box);
        rig.tick(100);
        var particles = alive(emitter);
        var center = at(6, 2.5, 14);
        var toBox = new Quaternionf().rotationY((float) Math.toRadians(-45));
        int entered = 0, onTop = 0, onGround = 0;
        for (var particle : particles) {
            var p = particle.getWorldPos(1);
            var local = toBox.transform(new Vector3f(p).sub(center));
            double dx = Math.max(Math.abs(local.x) - 1, 0), dy = Math.max(Math.abs(local.y) - 0.5, 0),
                    dz = Math.max(Math.abs(local.z) - 1, 0);
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < HALF - EPS) entered++;
            if (Math.abs(p.y - (y(3) + HALF)) < EPS) onTop++;
            if (Math.abs(p.y - (y(1) + HALF)) < EPS) onGround++;
        }
        ctx.log("box collider: %d on top, %d on the ground, %d alive".formatted(onTop, onGround, particles.size()));
        ctx.check("no particle is inside the box", entered == 0, 0, entered);
        ctx.check("some rest on its top face", onTop > 50, "> 50", onTop);
        ctx.check("the rest went past it to the ground", onTop + onGround == particles.size() && onGround > 50,
                particles.size(), onTop + " + " + onGround);
        rig.remove();
    }

    private static ColliderObject triggerSphere() {
        var sphere = new ColliderObject();
        sphere.config.setShape(ColliderConfig.Shape.Sphere);
        sphere.config.setRadius(1);
        sphere.config.setTrigger(true);
        sphere.transform().localPosition(new Vector3f(0, -4, 0));
        return sphere;
    }

    private static void triggerSphere(TestContext ctx) {
        var emitter = column(100, 0.4f);
        var sphere = triggerSphere();
        var crossings = new IdentityHashMap<IParticle, int[]>();
        var foreign = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            var type = event.type();
            if (type != CollisionEvent.Type.TRIGGER_ENTER && type != CollisionEvent.Type.TRIGGER_EXIT) return;
            if (event.target() != sphere || event.kind() != Contact.COLLIDER) foreign.incrementAndGet();
            crossings.computeIfAbsent(particle, p -> new int[2])[type == CollisionEvent.Type.TRIGGER_ENTER ? 0 : 1]++;
        });
        var rig = new Rig(ctx.level(), at(6, 8, 22), emitter, sphere);
        rig.tick(80);
        var particles = alive(emitter);
        int once = 0;
        for (var count : crossings.values()) {
            if (count[0] == 1 && count[1] == 1) once++;
        }
        ctx.check("all 100 fell through it to the ground",
                particles.size() == 100 && spread(particles, y(1) + HALF) < EPS, y(1) + HALF, describe(particles));
        ctx.check("each entered it once and left it once", once == 100 && crossings.size() == 100, 100,
                once + " of " + crossings.size());
        ctx.check("every event names the sphere", foreign.get() == 0, 0, foreign.get());
        rig.remove();
    }

    private static void triggerBornInside(TestContext ctx) {
        var emitter = column(100, 0.4f);
        var sphere = triggerSphere();
        // the sphere sits on the emitter: every particle starts inside it
        sphere.transform().localPosition(new Vector3f(0, 0, 0));
        var enters = new AtomicInteger();
        var exits = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            if (event.type() == CollisionEvent.Type.TRIGGER_ENTER) enters.incrementAndGet();
            if (event.type() == CollisionEvent.Type.TRIGGER_EXIT) exits.incrementAndGet();
        });
        var rig = new Rig(ctx.level(), at(10, 8, 22), emitter, sphere);
        rig.tick(80);
        ctx.check("each entered once and left once", enters.get() == 100 && exits.get() == 100, "100 / 100",
                enters.get() + " / " + exits.get());
        rig.remove();
    }

    private static void triggerKill(TestContext ctx) {
        var emitter = column(100, 0.4f);
        emitter.config.physics.collision.setKillOnTriggerEnter(true);
        var sphere = triggerSphere();
        var enters = new AtomicInteger();
        var deaths = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            if (event.type() == CollisionEvent.Type.TRIGGER_ENTER) enters.incrementAndGet();
            if (event.type() == CollisionEvent.Type.DEATH) deaths.incrementAndGet();
        });
        var rig = new Rig(ctx.level(), at(6, 8, 22), emitter, sphere);
        rig.tick(80);
        int left = alive(emitter).size();
        ctx.check("no particle got past it", left == 0, 0, left);
        ctx.check("each reported entering, then dying", enters.get() == 100 && deaths.get() == 100, "100 / 100",
                enters.get() + " / " + deaths.get());
        rig.remove();
    }


    private static void entitySolid(TestContext ctx) {
        var stand = stand(ctx);
        ctx.require("the armor stand is on the client", stand != null);
        var emitter = column(100, 0.2f);
        emitter.config.physics.collision.setEntities(true);
        var hits = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            if (event.type() == CollisionEvent.Type.IMPACT && event.kind() == Contact.ENTITY && event.target() == stand) {
                hits.incrementAndGet();
            }
        });
        var rig = new Rig(ctx.level(), at(20.5, 6, 22.5), emitter);
        rig.tick(80);
        var particles = alive(emitter);
        double top = stand.getBoundingBox().maxY + HALF;
        ctx.check("all 100 rest on its head", particles.size() == 100 && spread(particles, top) < EPS, top,
                describe(particles));
        ctx.check("each landing was one impact on the stand", hits.get() == 100, 100, hits.get());
        rig.remove();
    }

    private static void entityTrigger(TestContext ctx) {
        var stand = stand(ctx);
        ctx.require("the armor stand is on the client", stand != null);
        var emitter = column(100, 0.2f);
        var collision = emitter.config.physics.collision;
        collision.setEntities(true);
        collision.filters.setEntityResponse(CollisionSetting.EntityResponse.Trigger);
        var enters = new AtomicInteger();
        var exits = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            if (event.target() != stand) return;
            if (event.type() == CollisionEvent.Type.TRIGGER_ENTER) enters.incrementAndGet();
            if (event.type() == CollisionEvent.Type.TRIGGER_EXIT) exits.incrementAndGet();
        });
        var rig = new Rig(ctx.level(), at(20.5, 6, 22.5), emitter);
        rig.tick(80);
        var particles = alive(emitter);
        ctx.check("all 100 fell through it to the ground",
                particles.size() == 100 && spread(particles, y(1) + HALF) < EPS, y(1) + HALF, describe(particles));
        // they come to rest inside its box (it reaches down to the ground), so none has left it
        ctx.check("each entered it once and none left", enters.get() == 100 && exits.get() == 0, "100 / 0",
                enters.get() + " / " + exits.get());
        rig.remove();
    }


    private static void fluidSurface(TestContext ctx) {
        var floating = settle(ctx, at(36, 5, 49.5), 200, 3, collision -> collision.setFluids(true));
        var sunk = settle(ctx, at(36, 5, 49.5), 200, 3, collision -> collision.setFluids(false));
        // a source block with air above: 8/9 of a block of water
        double surface = y(0) + 8 / 9.0;
        ctx.check("with fluids on they float on the water", floating.size() == 200 && spread(floating, surface + HALF) < EPS,
                surface + HALF, describe(floating));
        ctx.check("with fluids off they sink to the bottom", sunk.size() == 200 && spread(sunk, y(0) + HALF) < EPS,
                y(0) + HALF, describe(sunk));
        // started under the surface of deep water: the layer below is water too, not a surface
        var underwater = settle(ctx, at(6, -0.3, 49), 100, 2, collision -> collision.setFluids(true));
        double deepTop = y(-1) + 8 / 9.0;
        ctx.check("under the surface they sink to the floor of deep water", underwater.size() == 100
                && spread(underwater, y(-1) + HALF) < EPS, y(-1) + HALF, describe(underwater));
        var onDeep = settle(ctx, at(6, 4, 49), 100, 2, collision -> collision.setFluids(true));
        ctx.check("and from above they float on its top", onDeep.size() == 100 && spread(onDeep, y(0) + 8 / 9.0 + HALF) < EPS,
                y(0) + 8 / 9.0 + HALF, describe(onDeep) + " (a layer surface would be " + (deepTop + HALF) + ")");
    }

    private static void blockFilter(TestContext ctx) {
        var except = settle(ctx, at(36, 14, 12), 200, 4, collision -> collision.filters.getExceptBlocks().add("minecraft:stone"));
        var only = settle(ctx, at(36, 14, 12), 200, 4, collision -> collision.filters.getOnlyBlocks().add("minecraft:stone"));
        ctx.check("all blocks but stone: through the stone platform, onto the grass",
                except.size() == 200 && spread(except, y(1) + HALF) < EPS, y(1) + HALF, describe(except));
        ctx.check("stone only: on the platform", only.size() == 200 && spread(only, y(10) + HALF) < EPS,
                y(10) + HALF, describe(only));
        // the bottom stone slabs at z 34..36 over the grass
        var slabs = settle(ctx, at(20, 4, 35.5), 100, 2,
                collision -> collision.filters.getOnlyBlocks().addAll(List.of("#minecraft:slabs", "minecraft:grass_block")));
        var notStoneSlabs = settle(ctx, at(20, 4, 35.5), 100, 2, collision -> {
            collision.filters.getOnlyBlocks().addAll(List.of("#minecraft:slabs", "minecraft:grass_block"));
            collision.filters.getExceptBlocks().add("minecraft:stone_slab");
        });
        ctx.check("only slabs and grass: on the slabs", slabs.size() == 100 && spread(slabs, y(1.5) + HALF) < EPS,
                y(1.5) + HALF, describe(slabs));
        ctx.check("only slabs and grass, except stone slabs: through them onto the grass",
                notStoneSlabs.size() == 100 && spread(notStoneSlabs, y(1) + HALF) < EPS, y(1) + HALF, describe(notStoneSlabs));
        var blank = settle(ctx, at(36, 14, 12), 200, 4, collision -> collision.filters.getOnlyBlocks().add(""));
        ctx.check("a blank entry, as the editor's add button leaves, restricts nothing",
                blank.size() == 200 && spread(blank, y(10) + HALF) < EPS, y(10) + HALF, describe(blank));
    }

    /** 26.1's getMaxSectionY is the last section itself, where 1.21's getMaxSection was one past it. */
    private static void topSection(TestContext ctx) {
        var top = TOP.get();
        var contact = new Contact();
        double x = top.getX() + 0.5, z = top.getZ() + 0.5;
        int hit = CollisionQuery.get().raycast(CollisionWorld.of(ctx.level()), x, top.getY() + 3.5, z, x, top.getY() - 2, z,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, contact);
        ctx.check("a ray down onto it stops on its top", hit == CollisionQuery.HIT && Math.abs(contact.py - (top.getY() + 1)) < 1e-4,
                top.getY() + 1, hit == CollisionQuery.HIT ? contact.py : "no hit");
    }

    /** Drop a column, let it settle, and return what is left alive. */
    private static List<TileParticle> settle(TestContext ctx, Vector3f at, int count, float width,
                                             Consumer<CollisionSetting> setup) {
        var emitter = column(count, width);
        setup.accept(emitter.config.physics.collision);
        var rig = new Rig(ctx.level(), at, emitter);
        rig.tick(100);
        var particles = alive(emitter);
        rig.remove();
        return particles;
    }


    private static void groundSnap(TestContext ctx) {
        var level = ctx.level();
        var emitter = sheet(400, 30, 10);
        emitter.config.shape.getGroundSnap().setEnable(true);
        var rig = new Rig(level, at(19, 6, 34), emitter);
        rig.tick(1);
        var particles = alive(emitter);
        int wrong = 0;
        double worst = 0;
        var heights = new TreeSet<Double>();
        for (var particle : particles) {
            var p = particle.getWorldPos(1);
            var hit = level.clip(new ClipContext(new Vec3(p.x, y(7.5), p.z), new Vec3(p.x, y(-1), p.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            double error = hit.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY : Math.abs(p.y - hit.getLocation().y);
            worst = Math.max(worst, error);
            if (error > 1e-4) wrong++;
            heights.add(Math.round((p.y - y(0)) * 8) / 8.0);
        }
        ctx.log("ground snap heights above the ground block: " + heights);
        ctx.check("all 400 were placed", particles.size() == 400, 400, particles.size());
        ctx.check("each sits on the collision surface below it", wrong == 0, 0, wrong + " (worst " + worst + ")");
        ctx.check("over stairs, slabs and the wall top", heights.size() >= 3, ">= 3 heights", heights);
        rig.remove();
    }

    private static void groundSnapSideways(TestContext ctx) {
        var emitter = sheet(200, 2, 2);
        var snap = emitter.config.shape.getGroundSnap();
        snap.setEnable(true);
        snap.setDirection(GroundSnapSetting.Direction.EmitterDown);
        // the emitter's -Y now points along +X, at the wall's west face
        emitter.transform().localRotation(new Quaternionf().rotationZ((float) Math.PI / 2));
        var rig = new Rig(ctx.level(), at(20, 2.5, 49.5), emitter);
        rig.tick(1);
        var particles = alive(emitter);
        double wall = ORIGIN.get().getX() + 24, worst = 0;
        for (var particle : particles) worst = Math.max(worst, Math.abs(particle.getWorldPos(1).x - wall));
        ctx.check("all 200 sit on the wall face", particles.size() == 200 && worst < 1e-4, wall,
                particles.size() + " particles, worst " + worst);
        rig.remove();
    }

    private static void groundSnapMissing(TestContext ctx) {
        var kept = snapHigh(ctx, false);
        var dropped = snapHigh(ctx, true);
        ctx.check("by default they stay where they spawned", kept.size() == 100 && spread(kept, y(20)) < 1e-4,
                y(20), describe(kept));
        ctx.check("with discard on, none is emitted", dropped.isEmpty(), 0, dropped.size());
    }

    private static List<TileParticle> snapHigh(TestContext ctx, boolean discard) {
        var emitter = sheet(100, 2, 2);
        var snap = emitter.config.shape.getGroundSnap();
        snap.setEnable(true);
        snap.setMaxDistance(2);
        snap.setDiscardWithoutGround(discard);
        var rig = new Rig(ctx.level(), at(6, 20, 6), emitter);
        rig.tick(1);
        var particles = alive(emitter);
        rig.remove();
        return particles;
    }


    private static final Vector3f[] DIAGONALS = {new Vector3f(6, -6, 0), new Vector3f(-6, -6, 0), new Vector3f(0, -6, 6),
            new Vector3f(0, -6, -6), new Vector3f(6, -6, 6), new Vector3f(-6, -6, -6)};

    private static void beamBlocks(TestContext ctx) {
        var level = ctx.level();
        var random = RandomSource.create(7);
        int rays = 0, hits = 0, mismatches = 0;
        String first = null;
        for (var block : new ClipContext.Block[]{ClipContext.Block.COLLIDER, ClipContext.Block.OUTLINE, ClipContext.Block.VISUAL}) {
            for (var fluid : new ClipContext.Fluid[]{ClipContext.Fluid.NONE, ClipContext.Fluid.SOURCE_ONLY, ClipContext.Fluid.ANY}) {
                var beam = new BeamEmitter();
                var config = beam.getConfig();
                config.setRaycast(BeamConfig.RaycastMode.BLOCKS);
                config.setRaycastBlockMode(block);
                config.setRaycastFluidMode(fluid);
                var rig = new Rig(level, at(0, 0, 0), beam);
                var particle = new BeamParticle(beam, config);
                for (int i = 0; i < 300; i++) {
                    var from = at(4 + random.nextDouble() * 40, 4 + random.nextDouble() * 4, 28 + random.nextDouble() * 24);
                    var direction = new Vector3f((float) (random.nextDouble() * 2 - 1), (float) -(0.3 + random.nextDouble()),
                            (float) (random.nextDouble() * 2 - 1)).normalize(12);
                    config.getEnd().set(direction);
                    var end = particle.getRealEnd(from);
                    var to = new Vec3(from.x + direction.x, from.y + direction.y, from.z + direction.z);
                    var hit = level.clip(new ClipContext(new Vec3(from), to, block, fluid, CollisionContext.empty()));
                    var expected = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
                    rays++;
                    if (hit.getType() != HitResult.Type.MISS) hits++;
                    if (distance(end, expected) > tolerance(expected)) {
                        mismatches++;
                        if (first == null) {
                            first = "%s/%s from %s dir %s: got %s, vanilla %s".formatted(block, fluid, from, direction, end, expected);
                        }
                    }
                }
                // from block centres at exactly 45 degrees: through edges and corners, where a ray has to choose
                for (int x = 4; x < 44; x += 4) {
                    for (int z = 28; z < 52; z += 4) {
                        for (var direction : DIAGONALS) {
                            var from = at(x + 0.5, 4.5, z + 0.5);
                            config.getEnd().set(direction);
                            var end = particle.getRealEnd(from);
                            var to = new Vec3(from.x + direction.x, from.y + direction.y, from.z + direction.z);
                            var hit = level.clip(new ClipContext(new Vec3(from), to, block, fluid, CollisionContext.empty()));
                            var expected = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
                            rays++;
                            if (hit.getType() != HitResult.Type.MISS) hits++;
                            if (distance(end, expected) > tolerance(expected)) {
                                mismatches++;
                                if (first == null) {
                                    first = "%s/%s from %s dir %s: got %s, vanilla %s".formatted(block, fluid, from, direction, end, expected);
                                }
                            }
                        }
                    }
                }
                rig.remove();
            }
        }
        ctx.log("beam rays: %d, %d hit something".formatted(rays, hits));
        // outlines and visuals are not in the collision snapshot, which has this section as empty
        var down = new Vector3f(0, -4, 0);
        for (var block : new ClipContext.Block[]{ClipContext.Block.OUTLINE, ClipContext.Block.VISUAL}) {
            var from = block == ClipContext.Block.OUTLINE ? at(30.5, 22, 30.5) : at(32.5, 22, 30.5);
            var beam = new BeamEmitter();
            var config = beam.getConfig();
            config.setRaycast(BeamConfig.RaycastMode.BLOCKS);
            config.setRaycastBlockMode(block);
            config.getEnd().set(down);
            var rig = new Rig(level, at(0, 0, 0), beam);
            var end = new BeamParticle(beam, config).getRealEnd(from);
            var hit = level.clip(new ClipContext(new Vec3(from), new Vec3(from).add(0, -4, 0), block, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            var expected = hit.getType() == HitResult.Type.MISS ? new Vec3(from).add(0, -4, 0) : hit.getLocation();
            ctx.check(block + " beams stop on a torch or snow in a section with no collision shapes",
                    hit.getType() != HitResult.Type.MISS && distance(end, expected) < 1e-4, expected, end);
            rig.remove();
        }
        ctx.check("every beam ends where vanilla's clip ends", mismatches == 0, 0,
                mismatches + (first == null ? "" : ", first: " + first));
        ctx.check("most of them hit something", hits > rays / 2, "> " + rays / 2, hits);
    }

    private static void beamEntities(TestContext ctx) {
        var stand = stand(ctx);
        ctx.require("the armor stand is on the client", stand != null);
        float width = 0.2f;
        // a beam is drawn its width to either side of its axis
        var skin = stand.getBoundingBox().inflate(width);
        var along = new Vector3f(18, 0, 0);
        // y 1.5 passes through the stone block at x 17 on the way to the stand; y 2.5 clears it
        var low = at(12, 1.5, 22.5);
        var high = at(12, 2.5, 22.5);
        var aside = at(12, 1.5, 25.5);
        var standLow = skin.clip(new Vec3(low), new Vec3(low).add(18, 0, 0)).orElse(null);
        var standHigh = skin.clip(new Vec3(high), new Vec3(high).add(18, 0, 0)).orElse(null);
        ctx.require("the stand is in the way of both beams", standLow != null && standHigh != null);
        var stone = new Vec3(ORIGIN.get().getX() + 17, low.y, low.z);
        beamCheck(ctx, "entities only: through the stone, onto the stand", BeamConfig.RaycastMode.ENTITIES, low, along, width, standLow);
        beamCheck(ctx, "blocks only: the stone", BeamConfig.RaycastMode.BLOCKS, low, along, width, stone);
        beamCheck(ctx, "both: the stone comes first", BeamConfig.RaycastMode.BLOCKS_AND_ENTITIES, low, along, width, stone);
        beamCheck(ctx, "both, above the stone: the stand", BeamConfig.RaycastMode.BLOCKS_AND_ENTITIES, high, along, width, standHigh);
        beamCheck(ctx, "entities only, beside the stand: full length", BeamConfig.RaycastMode.ENTITIES, aside, along, width,
                new Vec3(aside).add(18, 0, 0));
    }

    private static void beamCheck(TestContext ctx, String label, BeamConfig.RaycastMode mode, Vector3f from, Vector3f direction,
                                  float width, Vec3 expected) {
        var beam = new BeamEmitter();
        var config = beam.getConfig();
        config.setRaycast(mode);
        config.setWidth(NumberFunction.constant(width));
        config.getEnd().set(direction);
        var rig = new Rig(ctx.level(), at(0, 0, 0), beam);
        var end = new BeamParticle(beam, config).getRealEnd(from);
        ctx.check(label, distance(end, expected) < 1e-3, expected, end);
        rig.remove();
    }


    private static void araTrail(TestContext ctx) {
        var collide = trailPoints(ctx, true, 1.6, 0.05f);
        var free = trailPoints(ctx, false, 1.6, 0.05f);
        double rest = y(1) + 0.05;
        double lowest = Double.POSITIVE_INFINITY;
        int resting = 0;
        for (var point : collide) {
            lowest = Math.min(lowest, point.y);
            if (Math.abs(point.y - rest) < 0.01) resting++;
        }
        double sunk = free.stream().mapToDouble(point -> point.y).min().orElse(Double.NaN);
        ctx.log("trail: %d points, %d resting, lowest %.4f; without collision lowest %.4f".formatted(collide.size(),
                resting, lowest, sunk));
        ctx.check("with ground collision no point is below the ground", collide.size() > 20 && lowest > rest - EPS,
                "> " + (rest - EPS), lowest);
        ctx.check("and they came to lie on it", resting >= collide.size() * 0.9, ">= 90% of " + collide.size(), resting);
        ctx.check("without it they fall through", sunk < y(1) - 0.5, "< " + (y(1) - 0.5), sunk);
        // a block wide, dragged along with its lower half in the ground
        var thick = trailPoints(ctx, true, 1.3, 1);
        double thickLowest = thick.stream().mapToDouble(point -> point.y).min().orElse(Double.NaN);
        long thickResting = thick.stream().filter(point -> Math.abs(point.y - (y(1) + 1)) < 0.01).count();
        ctx.check("points thicker than the ground they start in are pushed out onto it", thick.size() > 20
                && thickLowest > y(1) + 1 - EPS && thickResting >= thick.size() * 0.9, "on " + (y(1) + 1),
                "lowest " + thickLowest + ", " + thickResting + " of " + thick.size() + " resting");
    }

    /** A trail of points {@code radius} big dragged along {@code height} above the ground, then left to settle; every point but the head. */
    private static List<Vector3f> trailPoints(TestContext ctx, boolean collide, double height, float radius) {
        var trail = new AraTrailEmitter();
        var config = trail.config;
        config.setLooping(false);
        config.setDuration(100_000);
        config.time = 10;
        var physics = config.physicsSetting;
        physics.setEnable(true);
        physics.gravity.set(0, -20, 0);
        physics.collideWithBlocks = collide;
        physics.collisionRadius = radius;
        var rig = new Rig(ctx.level(), at(4, height, 8), trail);
        for (int i = 0; i < 40; i++) {
            rig.runtime.root.updatePos(at(4 + i * 0.2, height, 8));
            rig.tick(1);
        }
        rig.tick(20);
        var points = new ArrayList<Vector3f>();
        try {
            var field = AraTrailEmitter.class.getDeclaredField("trailParticle");
            field.setAccessible(true);
            var all = ((AraTrailParticle) field.get(trail)).getPoints();
            for (int i = 0; i < all.size() - 1; i++) points.add(new Vector3f(all.get(i).position));
        } catch (ReflectiveOperationException e) {
            ctx.check("the trail's points are readable", false, "readable", e.toString());
        }
        rig.remove();
        return points;
    }

    private static void localDrag(TestContext ctx) {
        var level = ctx.level();
        var emitter = column(300, 1.5f);
        var config = emitter.config;
        config.setSimulationSpace(ParticleConfig.Space.Local);
        config.setStartSpeed(NumberFunction.constant(0));
        config.physics.setGravity(NumberFunction.constant(0));
        var rig = new Rig(level, at(14, 3, 6), emitter);
        // the emitter sinks a tenth of a block a tick, to well below the ground's top
        for (int i = 0; i <= 30; i++) {
            rig.runtime.root.updatePos(at(14, 3 - i * 0.1, 6));
            rig.tick(1);
        }
        rig.tick(10);
        var particles = alive(emitter);
        int inside = 0;
        for (var particle : particles) {
            var p = particle.getWorldPos(1);
            var box = new AABB(p.x - HALF, p.y - HALF, p.z - HALF, p.x + HALF, p.y + HALF, p.z + HALF).deflate(0.01);
            if (!level.noCollision(null, box)) inside++;
        }
        ctx.check("all 300 survive", particles.size() == 300, 300, particles.size());
        ctx.check("none was dragged into the ground", inside == 0, 0, inside);
        ctx.check("they are held on its surface", spread(particles, y(1) + HALF) < EPS, y(1) + HALF, describe(particles));
        rig.remove();
    }

    /** Particles flung at the wall's west face at head height, sideways. */
    private static List<TileParticle> flingAtWall(TestContext ctx, boolean stick) {
        var emitter = column(100, 1);
        emitter.config.setStartSpeed(NumberFunction.constant(20));
        emitter.config.physics.collision.response.setStick(stick);
        // local +Y, which the box shape fires along, now points at the wall (+X)
        emitter.transform().localRotation(new Quaternionf().rotationZ((float) -Math.PI / 2));
        var rig = new Rig(ctx.level(), at(20, 2.5, 49), emitter);
        rig.tick(60);
        var particles = alive(emitter);
        rig.remove();
        return particles;
    }

    private static void stickToWall(TestContext ctx) {
        var stuck = flingAtWall(ctx, true);
        var loose = flingAtWall(ctx, false);
        double face = ORIGIN.get().getX() + 24 - HALF;
        double worstX = 0, lowest = Double.POSITIVE_INFINITY;
        for (var particle : stuck) {
            var p = particle.getWorldPos(1);
            worstX = Math.max(worstX, Math.abs(p.x - face));
            lowest = Math.min(lowest, p.y);
        }
        ctx.check("all 100 hang on the wall face", stuck.size() == 100 && worstX < EPS, face, "worst " + worstX);
        ctx.check("where they hit it, not slid down", lowest > y(1.5), "> " + y(1.5), lowest);
        ctx.check("without sticking they end on the ground", loose.size() == 100 && spread(loose, y(1) + HALF) < EPS,
                y(1) + HALF, describe(loose));
    }

    private static void stickToCollider(TestContext ctx) {
        var emitter = column(100, 2);
        emitter.config.physics.collision.response.setStick(true);
        var plane = new ColliderObject();
        plane.config.setShape(ColliderConfig.Shape.Plane);
        plane.transform().localPosition(new Vector3f(0, -3, 0));
        var rig = new Rig(ctx.level(), at(30, 7, 12), emitter, plane);
        rig.tick(40);
        double before = alive(emitter).stream().mapToDouble(particle -> particle.getWorldPos(1).x).average().orElse(Double.NaN);
        // slides along itself: only sticking makes anything follow
        for (int i = 1; i <= 20; i++) {
            plane.transform().localPosition(new Vector3f(i * 0.1f, -3, 0));
            rig.tick(1);
        }
        var carried = alive(emitter);
        double moved = carried.stream().mapToDouble(particle -> particle.getWorldPos(1).x).average().orElse(Double.NaN) - before;
        ctx.check("carried 2 blocks along with the plane", carried.size() == 100 && Math.abs(moved - 2) < 1e-3, 2.0, moved);
        ctx.check("and still on it", spread(carried, y(4) + HALF) < EPS, y(4) + HALF, describe(carried));
        plane.remove();
        rig.tick(60);
        var dropped = alive(emitter);
        ctx.check("with the plane gone they fall to the ground", dropped.size() == 100 && spread(dropped, y(1) + HALF) < EPS,
                y(1) + HALF, describe(dropped));
        rig.remove();
    }

    private static void stickToTurningCollider(TestContext ctx) {
        var emitter = column(100, 2);
        emitter.config.physics.collision.response.setStick(true);
        var slab = new ColliderObject();
        slab.config.setShape(ColliderConfig.Shape.Box);
        slab.config.setSize(new Vector3f(4, 1, 4));
        slab.transform().localPosition(new Vector3f(0, -3, 0));
        var rig = new Rig(ctx.level(), at(30, 7, 12), emitter, slab);
        rig.tick(40);
        // a quarter turn about z over ten ticks: the top face becomes the -x one
        for (int i = 1; i <= 10; i++) {
            slab.transform().localRotation(new Quaternionf().rotationZ((float) (Math.PI / 2 * i / 10)));
            rig.tick(1);
        }
        var center = at(30, 4, 12);
        double face = center.x - 0.5 - HALF;
        var carried = alive(emitter);
        double worst = 0, low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (var particle : carried) {
            var p = particle.getWorldPos(1);
            worst = Math.max(worst, Math.abs(p.x - face));
            low = Math.min(low, p.y);
            high = Math.max(high, p.y);
        }
        ctx.check("all 100 turned with it onto its side", carried.size() == 100 && worst < EPS, face, "worst " + worst);
        ctx.check("spread up it as they were across the top", high - low > 1.5 && low > center.y - 1 - EPS
                && high < center.y + 1 + EPS, (center.y - 1) + ".." + (center.y + 1), low + ".." + high);
        rig.remove();
    }

    private static void stuckThroughBlockChanges(TestContext ctx) {
        var emitter = column(100, 1);
        var config = emitter.config;
        config.setStartSpeed(NumberFunction.constant(20));
        config.setStartLifetime(NumberFunction.constant(100));
        config.physics.collision.response.setStick(true);
        // from one to three times the size over its life
        var growth = new Curve(0, 4, 0, 4, "lifetime", "size", new ECBCurves(0, 0.25f, 0.1f, 0.3f, 0.9f, 0.7f, 1, 0.75f));
        config.sizeOverLifetime.setEnable(true);
        config.sizeOverLifetime.setSize(new NumberFunction3(growth, growth, growth));
        config.noise.setEnable(true);
        config.noise.setPosition(new NumberFunction3(0.2, 0.2, 0.2));
        emitter.transform().localRotation(new Quaternionf().rotationZ((float) -Math.PI / 2));
        var rig = new Rig(ctx.level(), at(20, 2.5, 49), emitter);
        rig.tick(40);
        // a block changes somewhere: every particle stuck to a block looks for it again
        var origin = ORIGIN.get();
        CollisionWorld.blockChanged(ctx.level(), origin.getX() + 40, origin.getY() + 30, origin.getZ() + 50);
        rig.tick(10);
        var held = alive(emitter);
        double face = origin.getX() + 24;
        double farthest = held.stream().mapToDouble(particle -> Math.abs(particle.getWorldPos(1).x - face)).max().orElse(Double.NaN);
        double lowest = held.stream().mapToDouble(particle -> particle.getWorldPos(1).y).min().orElse(Double.NaN);
        ctx.check("all 100 are still on the wall", held.size() == 100 && farthest < 0.5 && lowest > y(1.5),
                "within 0.5 of x " + face + ", above " + y(1.5), held.size() + ": " + farthest + " off, lowest " + lowest);
        rig.remove();
    }

    private static void groundSnapUnderCeiling(TestContext ctx) {
        // half a block under the floating platform, so the search from a block up starts inside it
        var emitter = sheet(100, 4, 4);
        emitter.config.shape.getGroundSnap().setEnable(true);
        var rig = new Rig(ctx.level(), at(36, 8.5, 12), emitter);
        rig.tick(1);
        var particles = alive(emitter);
        ctx.check("on the ground below, not in the platform", particles.size() == 100 && spread(particles, y(1)) < EPS,
                y(1), describe(particles));
        rig.remove();
    }

    private static void slowedSweep(TestContext ctx) {
        // particles at rest on open ground; a wall slides through them while the effect runs at half speed
        var emitter = column(100, 2);
        emitter.config.physics.collision.advanced.setDepenetrate(false);
        var wall = new ColliderObject();
        wall.config.setShape(ColliderConfig.Shape.Box);
        wall.config.setSize(new Vector3f(1, 1, 4));
        wall.transform().localPosition(new Vector3f(-4, -1.5f, 0));
        var rig = new Rig(ctx.level(), at(10, 3, 18), emitter, wall);
        rig.tick(40);
        rig.runtime.setRate(0.5f);
        for (int i = 1; i <= 30; i++) {
            wall.transform().localPosition(new Vector3f(-4 + i * 0.3f, -1.5f, 0));
            rig.tick(1);
        }
        var particles = alive(emitter);
        double front = ORIGIN.get().getX() + 10 - 4 + 30 * 0.3 + 0.5;
        double rearmost = particles.stream().mapToDouble(particle -> particle.getWorldPos(1).x).min().orElse(Double.NaN);
        ctx.check("every one was pushed ahead of it", particles.size() == 100 && rearmost > front + HALF - EPS,
                "> " + (front + HALF - EPS), rearmost);
        rig.remove();
    }

    private static void surfaceFilter(TestContext ctx) {
        var stone = event(Contact.BLOCK, Blocks.STONE.defaultBlockState());
        var dirt = event(Contact.BLOCK, Blocks.DIRT.defaultBlockState());
        var water = event(Contact.FLUID, Blocks.WATER.defaultBlockState());
        var mob = event(Contact.ENTITY, null);
        var onStone = new SubEmittersSetting.Emitter();
        onStone.setEvent(SubEmittersSetting.Event.Collision);
        onStone.setSurface(SubEmittersSetting.Surface.Blocks);
        onStone.getSurfaceBlocks().add("#minecraft:base_stone_overworld");
        ctx.check("blocks + a stone tag: stone passes, dirt and water do not",
                onStone.accepts(stone) && !onStone.accepts(dirt) && !onStone.accepts(water) && !onStone.accepts(mob),
                "true false false false", onStone.accepts(stone) + " " + onStone.accepts(dirt) + " "
                        + onStone.accepts(water) + " " + onStone.accepts(mob));
        var splash = new SubEmittersSetting.Emitter();
        splash.setEvent(SubEmittersSetting.Event.Death);
        splash.setSurface(SubEmittersSetting.Surface.Fluids);
        ctx.check("fluids on death: dying in water passes, dying of old age does not",
                splash.accepts(water) && !splash.accepts(null) && !splash.accepts(stone), "true false false",
                splash.accepts(water) + " " + splash.accepts(null) + " " + splash.accepts(stone));
        var birth = new SubEmittersSetting.Emitter();
        birth.setEvent(SubEmittersSetting.Event.Birth);
        birth.setSurface(SubEmittersSetting.Surface.Entities);
        ctx.check("birth ignores the surface", birth.accepts(null), true, birth.accepts(null));
    }

    private static CollisionEvent event(byte kind, @Nullable Object target) {
        return new CollisionEvent(CollisionEvent.Type.IMPACT, new Vector3f(), new Vector3f(0, 1, 0), kind, target, 0, 0.5f);
    }

    /** Owned by the armor stand, as an effect played on it would be. */
    private record OwnedExecutor(Level level, Entity owner) implements IEffectExecutor {
        @Override
        public Level getLevel() {
            return level;
        }

        @Override
        public RandomSource getRandomSource() {
            return RandomSource.create(42L);
        }
    }

    private static void executorOwner(TestContext ctx) {
        var stand = stand(ctx);
        ctx.require("the armor stand is on the client", stand != null);
        double top = stand.getBoundingBox().maxY + HALF;
        for (boolean ignore : new boolean[]{true, false}) {
            var emitter = column(100, 0.2f);
            var collision = emitter.config.physics.collision;
            collision.setEntities(true);
            collision.filters.setIgnoreOwner(ignore);
            collision.filters.setPickableOnly(true);
            var rig = new Rig(new OwnedExecutor(ctx.level(), stand), ctx.level(), at(20.5, 6, 22.5), emitter);
            rig.tick(80);
            var particles = alive(emitter);
            double expected = ignore ? y(1) + HALF : top;
            ctx.check(ignore ? "ignoring its owner, they fall through the stand" : "not ignoring it, they land on its head",
                    particles.size() == 100 && spread(particles, expected) < EPS, expected, describe(particles));
            rig.remove();
        }
    }

    private static void entityTypeLists(TestContext ctx) {
        var stand = stand(ctx);
        ctx.require("the armor stand is on the client", stand != null);
        double head = stand.getBoundingBox().maxY + HALF;
        var only = dropOnStand(ctx, collision -> collision.filters.getOnlyEntities().add("minecraft:armor_stand"));
        var both = dropOnStand(ctx, collision -> {
            collision.filters.getOnlyEntities().add("minecraft:armor_stand");
            collision.filters.getExceptEntities().add("minecraft:armor_stand");
        });
        var others = dropOnStand(ctx, collision -> collision.filters.getOnlyEntities().add("minecraft:pig"));
        ctx.check("only armor stands: on its head", only.size() == 100 && spread(only, head) < EPS, head, describe(only));
        ctx.check("except armor stands on top: through it", both.size() == 100 && spread(both, y(1) + HALF) < EPS,
                y(1) + HALF, describe(both));
        ctx.check("only pigs: through it", others.size() == 100 && spread(others, y(1) + HALF) < EPS,
                y(1) + HALF, describe(others));
    }

    /** A narrow column onto the armor stand's head, entity collision on. */
    private static List<TileParticle> dropOnStand(TestContext ctx, Consumer<CollisionSetting> setup) {
        return settle(ctx, at(20.5, 6, 22.5), 100, 0.2f, collision -> {
            collision.setEntities(true);
            setup.accept(collision);
        });
    }

    private static void sourceExclusion(TestContext ctx) {
        var stand = stand(ctx);
        ctx.require("the armor stand is on the client", stand != null);
        double head = stand.getBoundingBox().maxY + HALF;
        // the effect starts half a block above the stand's head
        var spared = settle(ctx, at(20.5, 3.5, 22.5), 100, 0.2f, collision -> {
            collision.setEntities(true);
            collision.filters.setSourceExclusionRadius(1);
        });
        var hit = settle(ctx, at(20.5, 3.5, 22.5), 100, 0.2f, collision -> collision.setEntities(true));
        var far = settle(ctx, at(20.5, 6, 22.5), 100, 0.2f, collision -> {
            collision.setEntities(true);
            collision.filters.setSourceExclusionRadius(1);
        });
        ctx.check("the stand was within a block of the start: they fall through it", spared.size() == 100
                && spread(spared, y(1) + HALF) < EPS, y(1) + HALF, describe(spared));
        ctx.check("without the radius they land on it", hit.size() == 100 && spread(hit, head) < EPS, head, describe(hit));
        ctx.check("started farther than the radius, it still counts", far.size() == 100 && spread(far, head) < EPS,
                head, describe(far));
    }

    /** The impacts a listener heard: the rig's tick of each, per particle. */
    private record Impacts(Map<IParticle, List<Integer>> ticks, int total) {
    }

    private static Impacts impacts(TestContext ctx, Vector3f at, int ticks, Consumer<ParticleEmitter> setup) {
        var emitter = column(100, 0.2f);
        setup.accept(emitter);
        var clock = new AtomicInteger();
        var seen = new IdentityHashMap<IParticle, List<Integer>>();
        var total = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            if (event.type() != CollisionEvent.Type.IMPACT) return;
            total.incrementAndGet();
            seen.computeIfAbsent(particle, p -> new ArrayList<>()).add(clock.get());
        });
        var rig = new Rig(ctx.level(), at, emitter);
        for (int i = 0; i < ticks; i++) {
            clock.set(i);
            rig.tick(1);
        }
        rig.remove();
        return new Impacts(seen, total.get());
    }

    private static void dedupeImpacts(TestContext ctx) {
        Consumer<ParticleEmitter> onStand = emitter -> emitter.config.physics.collision.setEntities(true);
        var every = impacts(ctx, at(20.5, 6, 22.5), 80, onStand);
        var perEmitter = impacts(ctx, at(20.5, 6, 22.5), 80, onStand.andThen(dedupe(CollisionSetting.Dedupe.PerEmitter, 0)));
        ctx.check("100 particles land on the stand: 100 impacts", every.total() == 100, 100, every.total());
        ctx.check("per emitter: one impact for the stand", perEmitter.total() == 1, 1, perEmitter.total());

        // elastic bouncers on one block: hit after hit of the same target
        Consumer<ParticleEmitter> bouncing = emitter -> emitter.config.physics.setBounceChance(NumberFunction.constant(1));
        var bounces = impacts(ctx, at(2.5, 5, 2.5), 120, bouncing);
        var once = impacts(ctx, at(2.5, 5, 2.5), 120, bouncing.andThen(dedupe(CollisionSetting.Dedupe.PerParticle, 0)));
        // they bounce about every 30 ticks
        var spaced = impacts(ctx, at(2.5, 5, 2.5), 120, bouncing.andThen(dedupe(CollisionSetting.Dedupe.PerParticle, 45)));
        var block = impacts(ctx, at(2.5, 5, 2.5), 120, bouncing.andThen(dedupe(CollisionSetting.Dedupe.PerEmitter, 0)));
        int fewest = bounces.ticks().values().stream().mapToInt(List::size).min().orElse(0);
        ctx.check("without de-duplication each bouncer reports every bounce", fewest >= 3, ">= 3 each", fewest);
        ctx.check("per particle: each reports the block once", once.total() == 100
                && once.ticks().values().stream().allMatch(list -> list.size() == 1), "100, one each", once.total());
        int closest = Integer.MAX_VALUE;
        for (var list : spaced.ticks().values()) {
            for (int i = 1; i < list.size(); i++) closest = Math.min(closest, list.get(i) - list.get(i - 1));
        }
        ctx.check("per particle with a 45 tick cooldown: reports come 45 ticks apart, and again after it",
                closest >= 45 && spaced.total() > 100 && spaced.total() < bounces.total(),
                ">= 45 apart, between 100 and " + bounces.total(), closest + " apart, " + spaced.total() + " in all");
        ctx.check("per emitter: the block reports once in all", block.total() == 1, 1, block.total());
    }

    private static Consumer<ParticleEmitter> dedupe(CollisionSetting.Dedupe mode, int cooldown) {
        return emitter -> {
            var events = emitter.config.physics.collision.events;
            events.setDedupe(mode);
            events.setCooldown(cooldown);
        };
    }

    private static void dedupeGroups(TestContext ctx) {
        int shared = groupImpacts(ctx, "hits", "hits");
        int apart = groupImpacts(ctx, "a", "b");
        ctx.check("one group: one impact between both emitters", shared == 1, 1, shared);
        ctx.check("two groups: one each", apart == 2, 2, apart);
    }

    private static void dedupeImpactsAndDeaths(TestContext ctx) {
        var emitter = column(100, 0.2f);
        emitter.config.physics.setRemovedWhenCollided(true);
        var collision = emitter.config.physics.collision;
        collision.setEntities(true);
        collision.events.setDedupe(CollisionSetting.Dedupe.PerEmitter);
        var impacts = new AtomicInteger();
        var deaths = new AtomicInteger();
        emitter.addCollisionListener((e, particle, event) -> {
            if (event.type() == CollisionEvent.Type.IMPACT) impacts.incrementAndGet();
            if (event.type() == CollisionEvent.Type.DEATH) deaths.incrementAndGet();
        });
        var rig = new Rig(ctx.level(), at(20.5, 6, 22.5), emitter);
        rig.tick(80);
        rig.remove();
        ctx.check("killed on contact, per emitter: one impact and one death for the stand", impacts.get() == 1 && deaths.get() == 1,
                "1 / 1", impacts.get() + " / " + deaths.get());
    }

    /** Two emitters of one effect drop onto the stand, each in a de-duplication group. */
    private static int groupImpacts(TestContext ctx, String first, String second) {
        var total = new AtomicInteger();
        var emitters = new ParticleEmitter[2];
        for (int i = 0; i < 2; i++) {
            var emitter = column(50, 0.2f);
            var collision = emitter.config.physics.collision;
            collision.setEntities(true);
            collision.events.setDedupe(CollisionSetting.Dedupe.PerGroup);
            collision.events.setGroup(i == 0 ? first : second);
            emitter.addCollisionListener((e, particle, event) -> {
                if (event.type() == CollisionEvent.Type.IMPACT) total.incrementAndGet();
            });
            emitters[i] = emitter;
        }
        var rig = new Rig(ctx.level(), at(20.5, 6, 22.5), emitters);
        rig.tick(80);
        rig.remove();
        return total.get();
    }

    /** Counts what sub-emitters spawn from it. */
    private static final class CountingFX extends FX {
        final AtomicInteger spawned = new AtomicInteger();

        @Override
        public FXRuntime createRuntime() {
            spawned.incrementAndGet();
            return super.createRuntime();
        }
    }

    private static final Identifier COUNTER = Identifier.fromNamespaceAndPath(Photon.MOD_ID, "uitest/collision_counter");

    @SuppressWarnings("unchecked")
    private static Map<Identifier, FX> fxCache() {
        try {
            var field = FXHelper.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<Identifier, FX>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void dedupeSubEmitters(TestContext ctx) {
        var counter = new CountingFX();
        fxCache().put(COUNTER, counter);
        try {
            Consumer<ParticleEmitter> onStand = emitter -> emitter.config.physics.collision.setEntities(true);
            Consumer<ParticleEmitter> onGround = emitter -> {
            };
            var every = spawns(ctx, counter, at(20.5, 6, 22.5), 80, true, onStand, sub -> {
            });
            var once = spawns(ctx, counter, at(20.5, 6, 22.5), 80, true, onStand.andThen(dedupe(CollisionSetting.Dedupe.PerEmitter, 0)),
                    sub -> {
                    });
            ctx.check("a Collision sub-emitter spawns once per landing, and the listener hears each one",
                    every.spawned() == 100 && every.heard() == 100, "100 / 100", every);
            ctx.check("de-duplicated per emitter: one spawn, and the listener hears that one", once.spawned() == 1 && once.heard() == 1,
                    "1 / 1", once);
            var onEntities = spawns(ctx, counter, at(20.5, 6, 22.5), 80, false, onStand,
                    sub -> sub.setSurface(SubEmittersSetting.Surface.Entities));
            var groundAsEntities = spawns(ctx, counter, at(14.5, 6, 18.5), 80, false, onGround,
                    sub -> sub.setSurface(SubEmittersSetting.Surface.Entities));
            var groundAsBlocks = spawns(ctx, counter, at(14.5, 6, 18.5), 80, false, onGround,
                    sub -> sub.setSurface(SubEmittersSetting.Surface.Blocks));
            ctx.check("surface Entities, landing on the stand: every one spawns", onEntities.spawned() == 100, 100, onEntities);
            ctx.check("surface Entities, landing on the ground: none spawns", groundAsEntities.spawned() == 0, 0, groundAsEntities);
            ctx.check("surface Blocks, landing on the ground: every one spawns", groundAsBlocks.spawned() == 100, 100, groundAsBlocks);

            // bouncers hit the same block again and again, and each spawn is a coin flip
            Consumer<ParticleEmitter> bouncing = emitter -> emitter.config.physics.setBounceChance(NumberFunction.constant(1));
            Consumer<SubEmittersSetting.Emitter> coin = sub -> sub.setEmitProbability(NumberFunction.constant(0.5f));
            var alone = spawns(ctx, counter, at(2.5, 5, 2.5), 120, false, bouncing.andThen(dedupe(CollisionSetting.Dedupe.PerParticle, 0)), coin);
            var heard = spawns(ctx, counter, at(2.5, 5, 2.5), 120, true, bouncing.andThen(dedupe(CollisionSetting.Dedupe.PerParticle, 0)), coin);
            ctx.check("per particle with a coin flip: only the first impact flips, whether or not a listener is there",
                    alone.spawned() == heard.spawned() && alone.spawned() > 0 && alone.spawned() < 100 && heard.heard() == 100,
                    "the same, between 0 and 100; 100 heard", alone.spawned() + " alone, " + heard);
        } finally {
            fxCache().remove(COUNTER, counter);
        }
    }

    private record Spawns(int spawned, int heard) {
        @Override
        public String toString() {
            return spawned + " spawned / " + heard + " heard";
        }
    }

    /**
     * What a Collision sub-emitter spawns while 100 particles drop at {@code at}, and, when {@code listen}, how
     * many of its impacts a listener hears.
     */
    private static Spawns spawns(TestContext ctx, CountingFX counter, Vector3f at, int ticks, boolean listen,
                                 Consumer<ParticleEmitter> setup, Consumer<SubEmittersSetting.Emitter> subSetup) {
        var emitter = column(100, 0.2f);
        setup.accept(emitter);
        var sub = new SubEmittersSetting.Emitter();
        sub.setFxLocation(COUNTER);
        sub.setEvent(SubEmittersSetting.Event.Collision);
        sub.setEmitProbability(NumberFunction.constant(1));
        sub.setSpawnAtContact(true);
        subSetup.accept(sub);
        emitter.config.subEmitters.getEmitters().add(sub);
        emitter.config.subEmitters.setEnable(true);
        var heard = new AtomicInteger();
        if (listen) {
            emitter.addCollisionListener((e, particle, event) -> {
                if (event.type() == CollisionEvent.Type.IMPACT && sub.accepts(event)) heard.incrementAndGet();
            });
        }
        counter.spawned.set(0);
        var rig = new Rig(ctx.level(), at, emitter);
        rig.tick(ticks);
        rig.remove();
        return new Spawns(counter.spawned.get(), heard.get());
    }

    private record Run(int[] state, List<String> events, boolean parallel) {
    }

    private static void determinism(TestContext ctx) {
        var c = new CollisionBenchScenario.Case("determinism", CollisionBenchScenario.Kind.MIXED, 5_000, true);
        var serial = run(ctx, c, false);
        var parallel = run(ctx, c, true);
        ctx.log("determinism: %d particles, %d events".formatted(serial.state().length / 6, serial.events().size()));
        ctx.check("there is something to compare", serial.state().length > 0 && !serial.events().isEmpty(),
                "particles and events", serial.state().length / 6 + " / " + serial.events().size());
        ctx.check("the serial run stayed serial", !serial.parallel(), false, serial.parallel());
        ctx.check("the parallel run went parallel", parallel.parallel(), true, parallel.parallel());
        ctx.check("the same particles survive", serial.state().length == parallel.state().length,
                serial.state().length / 6, parallel.state().length / 6);
        ctx.check("bit-identical positions and velocities", Arrays.equals(serial.state(), parallel.state()), "identical",
                "differ at " + Arrays.mismatch(serial.state(), parallel.state()));
        ctx.check("the same collision events in the same order", serial.events().equals(parallel.events()),
                serial.events().size(), parallel.events().size() + " events, first difference at "
                        + firstDifference(serial.events(), parallel.events()));
    }

    private static Run run(TestContext ctx, CollisionBenchScenario.Case c, boolean parallel) {
        var emitter = CollisionBenchScenario.createEmitter(c, parallel);
        var events = new ArrayList<String>();
        emitter.addCollisionListener((e, particle, event) -> {
            var p = event.position();
            var n = event.normal();
            events.add(event.type() + " " + p.x + " " + p.y + " " + p.z + " " + n.x + " " + n.y + " " + n.z + " " + event.speed());
        });
        var rig = new Rig(ctx.level(), CollisionBenchScenario.center(c, ORIGIN.get()), emitter);
        rig.tick(120);
        boolean ranParallel;
        try {
            var field = ParticleEmitter.class.getDeclaredField("collisionParallel");
            field.setAccessible(true);
            ranParallel = field.getBoolean(emitter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        var particles = alive(emitter);
        var state = new int[particles.size() * 6];
        for (int i = 0; i < particles.size(); i++) {
            var p = particles.get(i).getWorldPos(1);
            var v = particles.get(i).getInternalVelocity();
            state[i * 6] = Float.floatToIntBits(p.x);
            state[i * 6 + 1] = Float.floatToIntBits(p.y);
            state[i * 6 + 2] = Float.floatToIntBits(p.z);
            state[i * 6 + 3] = Float.floatToIntBits(v.x);
            state[i * 6 + 4] = Float.floatToIntBits(v.y);
            state[i * 6 + 5] = Float.floatToIntBits(v.z);
        }
        rig.remove();
        return new Run(state, events, ranParallel);
    }

    private static void persistence(TestContext ctx) {
        var collider = new ColliderObject();
        collider.config.setShape(ColliderConfig.Shape.Sphere);
        collider.config.setRadius(2.5f);
        collider.config.setTrigger(true);
        var emitter = new ParticleEmitter();
        // a disabled module saves nothing but its switch
        emitter.config.physics.setEnable(true);
        var collision = emitter.config.physics.collision;
        collision.setFluids(true);
        collision.setEntities(true);
        collision.setShape(CollisionSetting.Shape.Point);
        collision.setRadiusScale(1.5f);
        collision.setKillOnTriggerEnter(true);
        collision.response.setDampen(NumberFunction.constant(0.25f));
        collision.response.setMinKillSpeed(0.2f);
        collision.response.setStick(true);
        collision.response.setMaxBounces(3);
        collision.response.setKillAfterImpacts(5);
        collision.filters.getOnlyBlocks().addAll(List.of("#minecraft:leaves", "minecraft:stone"));
        collision.filters.getExceptBlocks().add("minecraft:oak_leaves");
        collision.filters.getOnlyEntities().add("#minecraft:skeletons");
        collision.filters.getExceptEntities().add("minecraft:pig");
        collision.filters.setEntityResponse(CollisionSetting.EntityResponse.Trigger);
        collision.filters.setPickableOnly(true);
        collision.filters.setSourceExclusionRadius(2.5f);
        collision.events.setMinImpactSpeed(0.05f);
        collision.events.setDedupe(CollisionSetting.Dedupe.PerGroup);
        collision.events.setCooldown(30);
        collision.events.setGroup("sparks");
        emitter.config.subEmitters.setEnable(true);
        var sub = new SubEmittersSetting.Emitter();
        sub.setFxLocation(COUNTER);
        sub.setEvent(SubEmittersSetting.Event.Death);
        sub.setSurface(SubEmittersSetting.Surface.Fluids);
        sub.getSurfaceBlocks().add("minecraft:water");
        sub.setSpawnAtContact(true);
        emitter.config.subEmitters.getEmitters().add(sub);
        collision.filters.setColliderFilter(CollisionSetting.ColliderFilter.List);
        collision.filters.getColliderList().add(new TransformRef(collider.transform()));
        collision.advanced.setMaxContacts(7);
        collision.advanced.setDepenetrate(false);
        var snap = emitter.config.shape.getGroundSnap();
        snap.setEnable(true);
        snap.setDirection(GroundSnapSetting.Direction.EmitterDown);
        snap.setMaxDistance(5);
        snap.setDiscardWithoutGround(true);
        var fx = new FX();
        fx.getFxData().objects().addAll(List.of(emitter, collider));
        var provider = Platform.getFrozenRegistry();
        var loaded = new FX();
        loaded.deserializeNBT(provider, fx.serializeNBT(provider));
        var objects = loaded.getFxData().objects();
        var e = (ParticleEmitter) objects.stream().filter(o -> o instanceof ParticleEmitter).findFirst().orElseThrow();
        var c = (ColliderObject) objects.stream().filter(o -> o instanceof ColliderObject).findFirst().orElseThrow();
        var lc = e.config.physics.collision;
        var ls = e.config.shape.getGroundSnap();
        ctx.check("collider shape, radius, trigger", c.config.getShape() == ColliderConfig.Shape.Sphere
                && c.config.getRadius() == 2.5f && c.config.isTrigger(), "Sphere 2.5 trigger",
                c.config.getShape() + " " + c.config.getRadius() + " " + c.config.isTrigger());
        ctx.check("collision toggles and shape", lc.isFluids() && lc.isEntities() && lc.getShape() == CollisionSetting.Shape.Point
                && lc.getRadiusScale() == 1.5f && lc.isKillOnTriggerEnter(), "true true Point 1.5 true",
                lc.isFluids() + " " + lc.isEntities() + " " + lc.getShape() + " " + lc.getRadiusScale() + " " + lc.isKillOnTriggerEnter());
        var r = lc.response;
        ctx.check("response", r.getDampen().get(0, () -> 0f).floatValue() == 0.25f && r.getMinKillSpeed() == 0.2f
                && r.isStick() && r.getMaxBounces() == 3 && r.getKillAfterImpacts() == 5, "0.25 0.2 stick 3 5",
                r.getDampen().get(0, () -> 0f) + " " + r.getMinKillSpeed() + " " + r.isStick() + " " + r.getMaxBounces() + " "
                        + r.getKillAfterImpacts());
        var f = lc.filters;
        ctx.check("block lists", f.getOnlyBlocks().equals(List.of("#minecraft:leaves", "minecraft:stone"))
                && f.getExceptBlocks().equals(List.of("minecraft:oak_leaves")), "[#minecraft:leaves, minecraft:stone] - [minecraft:oak_leaves]",
                f.getOnlyBlocks() + " - " + f.getExceptBlocks());
        ctx.check("entity lists and filters", f.getOnlyEntities().equals(List.of("#minecraft:skeletons"))
                && f.getExceptEntities().equals(List.of("minecraft:pig")) && f.getEntityResponse() == CollisionSetting.EntityResponse.Trigger
                && f.isPickableOnly() && f.getSourceExclusionRadius() == 2.5f, "[#minecraft:skeletons] - [minecraft:pig] Trigger pickable 2.5",
                f.getOnlyEntities() + " - " + f.getExceptEntities() + " " + f.isPickableOnly() + " " + f.getSourceExclusionRadius());
        var ev = lc.events;
        ctx.check("events", ev.getMinImpactSpeed() == 0.05f && ev.getDedupe() == CollisionSetting.Dedupe.PerGroup
                && ev.getCooldown() == 30 && ev.getGroup().equals("sparks"), "0.05 PerGroup 30 sparks",
                ev.getMinImpactSpeed() + " " + ev.getDedupe() + " " + ev.getCooldown() + " " + ev.getGroup());
        var subs = e.config.subEmitters.getEmitters();
        var ls2 = subs.isEmpty() ? null : subs.getFirst();
        ctx.check("sub-emitter surface", ls2 != null && ls2.getSurface() == SubEmittersSetting.Surface.Fluids
                && ls2.getSurfaceBlocks().equals(List.of("minecraft:water")) && ls2.isSpawnAtContact()
                && COUNTER.equals(ls2.getFxLocation()), "Fluids [minecraft:water] at contact",
                ls2 == null ? "none" : ls2.getSurface() + " " + ls2.getSurfaceBlocks() + " " + ls2.isSpawnAtContact());
        ctx.check("collider list still points at the collider", lc.filters.getColliderFilter() == CollisionSetting.ColliderFilter.List
                && lc.filters.getColliderList().size() == 1 && lc.filters.accepts(c), "the loaded collider",
                lc.filters.getColliderList().stream().map(TransformRef::getTransformId).toList());
        ctx.check("advanced", lc.advanced.getMaxContacts() == 7 && !lc.advanced.isDepenetrate(), "7, off",
                lc.advanced.getMaxContacts() + ", " + lc.advanced.isDepenetrate());
        ctx.check("ground snap", ls.isEnable() && ls.getDirection() == GroundSnapSetting.Direction.EmitterDown
                && ls.getMaxDistance() == 5 && ls.isDiscardWithoutGround(), "EmitterDown 5 discard",
                ls.isEnable() + " " + ls.getDirection() + " " + ls.getMaxDistance());
    }

    private static int firstDifference(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).equals(b.get(i))) return i;
        }
        return a.size() == b.size() ? -1 : Math.min(a.size(), b.size());
    }


    /** One FX scene built in code and ticked by hand, dropping what the particle engine would drop. */
    private static final class Rig {
        final FXRuntime runtime;
        final List<FXObject> objects = new ArrayList<>();
        /** As in the engine: an object not alive after its tick leaves it (a collider does, after its first). */
        final List<FXObject> ticking = new ArrayList<>();

        Rig(Level level, Vector3f at, IFXObject... objects) {
            this(new CollisionBenchScenario.BenchExecutor(level, RandomSource.create(42L)), level, at, objects);
        }

        Rig(IEffectExecutor executor, Level level, Vector3f at, IFXObject... objects) {
            var fx = new FX();
            fx.getFxData().objects().addAll(List.of(objects));
            runtime = fx.createInternalRuntime();
            for (var object : runtime.objects.values()) {
                object.setEffect(executor);
                object.reset();
                object.setLevel(level);
                if (object instanceof FXObject fxObject) this.objects.add(fxObject);
            }
            ticking.addAll(this.objects);
            runtime.root.updatePos(at);
        }

        void tick(int ticks) {
            for (int i = 0; i < ticks; i++) {
                for (var iterator = ticking.iterator(); iterator.hasNext(); ) {
                    var object = iterator.next();
                    object.tick();
                    if (!object.isAlive()) iterator.remove();
                }
            }
        }

        void remove() {
            for (var object : objects) object.remove(true);
        }
    }

    /** {@code count} particles of {@link #SIZE} dropped from a flat {@code width}-wide square, no bouncing. */
    private static ParticleEmitter column(int count, float width) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(false);
        config.setDuration(100_000);
        config.setStartLifetime(NumberFunction.constant(100_000));
        config.setStartSize(new NumberFunction3(SIZE, SIZE, SIZE));
        config.setStartSpeed(NumberFunction.constant(-4));
        config.setMaxParticles(count);
        config.setSimulationSpace(ParticleConfig.Space.World);
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.cycles = 1;
        burst.setCount(NumberFunction.constant(count));
        config.emission.getBursts().add(burst);
        config.shape.setShape(new Box());
        config.shape.setScale(new NumberFunction3(width, 0, width));
        var physics = config.physics;
        physics.setEnable(true);
        physics.setGravity(NumberFunction.constant(1));
        physics.setBounceChance(NumberFunction.constant(0));
        return emitter;
    }

    /** {@code count} still particles on a flat {@code x} by {@code z} sheet, physics off. */
    private static ParticleEmitter sheet(int count, float x, float z) {
        var emitter = column(count, 1);
        emitter.config.shape.setScale(new NumberFunction3(x, 0, z));
        emitter.config.setStartSpeed(NumberFunction.constant(0));
        emitter.config.physics.setEnable(false);
        return emitter;
    }

    private static List<TileParticle> alive(ParticleEmitter emitter) {
        var list = new ArrayList<TileParticle>();
        for (var queue : emitter.getParticles().values()) {
            for (var particle : queue) {
                if (particle instanceof TileParticle tile && tile.isAlive()) list.add(tile);
            }
        }
        return list;
    }

    /** How far the furthest particle is from height {@code y}. */
    private static double spread(List<TileParticle> particles, double y) {
        double worst = 0;
        for (var particle : particles) worst = Math.max(worst, Math.abs(particle.getWorldPos(1).y - y));
        return worst;
    }

    private static String describe(List<TileParticle> particles) {
        if (particles.isEmpty()) return "no particles";
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (var particle : particles) {
            double y = particle.getWorldPos(1).y;
            low = Math.min(low, y);
            high = Math.max(high, y);
        }
        return "%d particles, y %.5f..%.5f".formatted(particles.size(), low, high);
    }

    @Nullable
    private static ArmorStand stand(TestContext ctx) {
        var origin = ORIGIN.get();
        var level = ctx.level();
        if (origin == null || level == null) return null;
        var found = level.getEntitiesOfClass(ArmorStand.class, new AABB(origin.offset(20, 1, 22)).inflate(1.5));
        return found.isEmpty() ? null : found.getFirst();
    }

    private static Vector3f at(double x, double y, double z) {
        var origin = ORIGIN.get();
        return new Vector3f((float) (origin.getX() + x), (float) (origin.getY() + y), (float) (origin.getZ() + z));
    }

    private static double y(double y) {
        return ORIGIN.get().getY() + y;
    }

    private static Camera camera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }

    private static double distance(Vector3f a, Vec3 b) {
        return Math.max(Math.abs(a.x - b.x), Math.max(Math.abs(a.y - b.y), Math.abs(a.z - b.z)));
    }

    /** Float rounding of a beam end at {@code at}'s coordinates, plus a little. */
    private static double tolerance(Vec3 at) {
        float largest = (float) Math.max(Math.abs(at.x), Math.max(Math.abs(at.y), Math.abs(at.z)));
        return 1e-5 + 2 * Math.ulp(largest);
    }
}
