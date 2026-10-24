package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaPresets;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.light.FogVolumeConfig;
import com.lowdragmc.photon.client.gameobject.light.FogVolumeObject;
import com.lowdragmc.photon.client.gameobject.light.LightConfig;
import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.client.light.ShadowMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The in-world pictures of the Photon wiki, named after the files they become under {@code docs/assets/photon2/}:
 * a night plaza lit only by Photon (lights, shadows, haze, fog, a campfire whose embers light the ground and
 * its smoke), a wall of KilaMaterial presets, sparks on stairs and slabs, and a swarm of animated glTF foxes.
 * Run with {@code -PldTestWindow=2560x1362}; it builds its own sites in a throwaway test world.
 */
@LDLRegisterClient(name = "wiki_photon_world", group = "photon_wiki", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WikiWorldScenario implements UIScenario {
    private static final AtomicReference<BlockPos> PLAZA = new AtomicReference<>();
    private static final AtomicReference<BlockPos> GALLERY = new AtomicReference<>();
    private static final AtomicReference<BlockPos> STAIRS = new AtomicReference<>();
    private static final ResourceLocation FOX = Photon.id("models/uitest_wiki_fox.glb");
    private static final List<BlockEffectExecutor> RUNNING = new ArrayList<>();
    private static final AtomicReference<GameType> GAME_MODE = new AtomicReference<>();
    private static final List<String> CAMPFIRE = List.of("fire light", "embers", "flames", "smoke", "circle light", "shield light");

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("wiki", "photon").scenarioTimeoutMs(900_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui and light the scene with Photon only", ctx -> {
                    ctx.mc().options.hideGui = true;
                    var config = PhotonConfig.INSTANCE;
                    LightTestConfig.set(config.dynamicLights, true);
                    LightTestConfig.set(config.lightShadowMode, ShadowMode.VOXEL);
                    LightTestConfig.set(config.lightSoftShadows, true);
                    LightTestConfig.set(config.lightShadowedLights, 12);
                    LightTestConfig.set(config.lightResolution, 1.0);
                    LightTestConfig.volumetricDefaults();
                    LightTestConfig.set(config.enableBloom, true);
                    LightTestConfig.set(ctx.mc().options.fov(), 70);
                })
                .server("build the sites", sc -> {
                    var origin = LightSite.claim(sc.player());
                    PLAZA.set(origin.offset(0, 0, -64));
                    GALLERY.set(origin.offset(64, 0, -64));
                    STAIRS.set(origin.offset(-64, 0, -96));
                    var level = sc.level();
                    buildPlaza(level, PLAZA.get());
                    buildGallery(level, GALLERY.get());
                    CollisionBenchScenario.buildSite(level, STAIRS.get());
                    level.setDayTime(18000);
                    level.setWeatherParameters(12000, 0, false, false);
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
                    // a spectator floats where it is put, so every view is exactly where it is aimed
                    GAME_MODE.compareAndSet(null, sc.player().gameMode.getGameModeForPlayer());
                    sc.player().setGameMode(GameType.SPECTATOR);
                });

        view(s, "the plaza", PLAZA, 0.5, 7, -17.5, 0, 24);
        s.step("light the plaza", ctx -> startPlaza(ctx, PLAZA.get()))
                .ticks(80)
                .screenshot("lights-overview")
                .step("screen-space shadows", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.SCREEN))
                .ticks(4)
                .screenshot("lights-shadow-modes_screen")
                .step("voxel shadows again", ctx -> LightTestConfig.set(PhotonConfig.INSTANCE.lightShadowMode, ShadowMode.VOXEL))
                .ticks(4)
                .screenshot("lights-shadow-modes_voxel");
        view(s, "the campfire", PLAZA, 3.0, 2.2, -8.0, 30, 24);
        s.step("clear the mist for the close-ups", ctx -> setEnabled(ctx, "mist", false))
                .step("only the embers light the ground", ctx -> {
                    setEnabled(ctx, "fire light", false);
                    setVisible(ctx, "smoke", false);
                    emberLights(ctx, 5, 4);
                })
                .ticks(10).screenshot("lights-emission")
                .step("the fire light back", ctx -> {
                    setEnabled(ctx, "fire light", true);
                    setVisible(ctx, "smoke", true);
                    emberLights(ctx, 2.5f, 3);
                });
        view(s, "the smoke", PLAZA, 4.5, 1.8, -10.5, 30, -14);
        s.step("smoke not lit", ctx -> setSmokeLit(ctx, false))
                .ticks(3)
                .screenshot("lights-lit-smoke_off")
                .step("smoke lit", ctx -> setSmokeLit(ctx, true))
                .ticks(3)
                .screenshot("lights-lit-smoke_on");
        // last on the plaza: an emitter switched off does not start again
        view(s, "the temple door in the mist", PLAZA, 3.5, 1.4, -4.5, 16, -8);
        s.step("only the temple and the searchlight", ctx -> {
                    for (var name : CAMPFIRE) setEnabled(ctx, name, false);
                    templeLight(ctx, 45, 3f);
                })
                .ticks(10).screenshot("lights-volumetric-fog")
                .step("stop the plaza", ctx -> stopAll());

        view(s, "the preset wall", GALLERY, 0.5, 2.2, -7.5, 0, 9);
        s.step("show the presets", ctx -> startGallery(ctx, GALLERY.get()))
                .ticks(50)
                .screenshot("kila-showcase")
                .step("stop the presets", ctx -> stopAll());

        s.server("noon for the stairs", sc -> sc.level().setDayTime(6000));
        view(s, "the stairs", STAIRS, 11.5, 3.2, 24.5, -32, 16);
        s.step("pour sparks over the stairs", ctx -> startSparks(ctx, STAIRS.get()))
                .ticks(60)
                .screenshot("collision-overview")
                .step("stop the sparks", ctx -> stopAll())
                .server("night again", sc -> sc.level().setDayTime(18000));

        s.step("stage the fox", WikiWorldScenario::stageFox);
        view(s, "the foxes", PLAZA, 0.5, 1.5, -27.5, 0, 8);
        s.step("let the foxes loose", ctx -> startFoxes(ctx, PLAZA.get()))
                .ticks(30)
                .screenshot("gltf-animated-swarm")
                .step("stop the foxes", ctx -> stopAll());

        s.teardown("stop everything", ctx -> stopAll())
                .teardown("restore the client", ctx -> {
                    ctx.mc().options.hideGui = false;
                    LightTestConfig.restore();
                    var fox = new File(LDLib2.getAssetsDir(), "photon/models/uitest_wiki_fox.glb");
                    if (fox.exists() && !fox.delete()) fox.deleteOnExit();
                })
                .teardownServer("put the world back", sc -> {
                    var mode = GAME_MODE.getAndSet(null);
                    if (mode != null) sc.player().setGameMode(mode);
                    var level = sc.level();
                    if (PLAZA.get() != null) clear(level, PLAZA.get(), 20, 16);
                    if (GALLERY.get() != null) clear(level, GALLERY.get(), 20, 12);
                    if (STAIRS.get() != null) CollisionBenchScenario.clearSite(level, STAIRS.get());
                    LightSite.release(sc.player());
                });
    }

    /** Flies the player to a spot relative to {@code site} and waits until the world there is drawn. */
    private static void view(ScenarioBuilder s, String name, AtomicReference<BlockPos> site, double x, double y, double z,
                             float yaw, float pitch) {
        s.server("go to " + name, sc -> {
                    var o = site.get();
                    sc.player().teleportTo(sc.level(), o.getX() + x, o.getY() + 1 + y, o.getZ() + z, yaw, pitch);
                })
                .ticks(10)
                .waitUntil(name + " is drawn", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                .frames(10);
    }

    private static void clear(ServerLevel level, BlockPos o, int radius, int height) {
        var air = Blocks.AIR.defaultBlockState();
        var grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                level.setBlock(o.offset(x, 0, z), grass, 2);
                for (int y = 1; y <= height; y++) level.setBlock(o.offset(x, y, z), air, 2);
            }
        }
    }

    private static void fill(ServerLevel level, BlockPos o, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                    level.setBlock(o.offset(x, y, z), state, 2);
    }

    /**
     * A paved square in front of a small temple: a doorway and two open windows the light inside shines out of,
     * quartz pillars, a ring of low walls, and a spent campfire in the middle for the FX to burn on.
     */
    private static void buildPlaza(ServerLevel level, BlockPos o) {
        clear(level, o, 20, 16);
        fill(level, o, -13, 0, -13, 13, 0, 13, Blocks.POLISHED_ANDESITE.defaultBlockState());
        fill(level, o, -4, 0, -7, 4, 0, 1, Blocks.SMOOTH_STONE.defaultBlockState());
        fill(level, o, -13, 0, -13, 13, 0, -13, Blocks.STONE_BRICKS.defaultBlockState());
        fill(level, o, -13, 0, -13, -13, 0, 13, Blocks.STONE_BRICKS.defaultBlockState());
        fill(level, o, 13, 0, -13, 13, 0, 13, Blocks.STONE_BRICKS.defaultBlockState());
        fill(level, o, -12, 1, -12, -12, 1, 4, Blocks.STONE_BRICK_WALL.defaultBlockState());
        fill(level, o, 12, 1, -12, 12, 1, 4, Blocks.STONE_BRICK_WALL.defaultBlockState());
        fill(level, o, -8, 1, 6, 8, 8, 13, Blocks.STONE_BRICKS.defaultBlockState());
        fill(level, o, -7, 1, 7, 7, 7, 12, Blocks.AIR.defaultBlockState());
        fill(level, o, -7, 0, 7, 7, 0, 12, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        fill(level, o, -9, 9, 5, 9, 9, 14, Blocks.DARK_OAK_SLAB.defaultBlockState());
        fill(level, o, -1, 1, 6, 1, 4, 6, Blocks.AIR.defaultBlockState());
        fill(level, o, -6, 4, 6, -4, 6, 6, Blocks.AIR.defaultBlockState());
        fill(level, o, 4, 4, 6, 6, 6, 6, Blocks.AIR.defaultBlockState());
        fill(level, o, -8, 1, 6, -8, 8, 6, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        fill(level, o, 8, 1, 6, 8, 8, 6, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        fill(level, o, -6, 1, 11, 6, 1, 11, Blocks.BOOKSHELF.defaultBlockState());
        fill(level, o, -2, 1, 5, 2, 1, 5, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        for (int x : new int[]{-10, 10}) {
            for (int z : new int[]{-8, 2}) {
                fill(level, o, x, 1, z, x, 5, z, Blocks.QUARTZ_PILLAR.defaultBlockState());
                level.setBlock(o.offset(x, 6, z), Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState(), 2);
            }
        }
        fill(level, o, -6, 1, -9, -3, 1, -9, Blocks.SPRUCE_SLAB.defaultBlockState());
        fill(level, o, 3, 1, -9, 6, 1, -9, Blocks.SPRUCE_SLAB.defaultBlockState());
        level.setBlock(o.offset(-9, 1, 4), Blocks.BARREL.defaultBlockState(), 2);
        level.setBlock(o.offset(9, 1, 4), Blocks.BARREL.defaultBlockState(), 2);
        level.setBlock(o.offset(0, 1, -3), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), 2);
    }

    /** A dark backdrop for the presets, with a floor they can sit on. */
    private static void buildGallery(ServerLevel level, BlockPos o) {
        clear(level, o, 20, 12);
        fill(level, o, -18, 0, -12, 18, 0, 4, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        fill(level, o, -18, 1, 4, 18, 9, 4, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        fill(level, o, -18, 1, 3, 18, 1, 3, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
    }

    private static BlockEffectExecutor play(TestContext ctx, BlockPos at, double dx, double dy, double dz, IFXObject... objects) {
        var fx = new FX();
        for (var object : objects) fx.getFxData().objects().add(object);
        var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), at);
        executor.setOffset(dx, dy, dz);
        executor.setAllowMulti(true);
        executor.start();
        RUNNING.add(executor);
        return executor;
    }

    private static void stopAll() {
        for (var executor : RUNNING) {
            if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
        }
        RUNNING.clear();
    }

    private static LightObject light(LightConfig.Type type, int rgb, float intensity, float range, float x, float y, float z) {
        var light = new LightObject();
        var config = light.config;
        config.setType(type);
        config.setLooping(true);
        config.setLifetime(200);
        config.setColor(new HDRConstantColor(new HDRColor(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f,
                (rgb & 255) / 255f, 1f, 1f)));
        config.setIntensity(NumberFunction.constant(intensity));
        config.setRange(NumberFunction.constant(range));
        config.setSourceRadius(0.3f);
        light.transform().localPosition(new Vector3f(x, y, z));
        return light;
    }

    private static void startPlaza(TestContext ctx, BlockPos o) {
        stopAll();
        // the campfire: a flickering fire light, embers that light the ground, flames and smoke the lights reach
        var fire = light(LightConfig.Type.Point, 0xFF8A3D, 5, 9, 0, 1.2f, 0);
        fire.setName("fire light");
        fire.config.setFlicker(0.3f);
        var embers = new ParticleEmitter();
        embers.setName("embers");
        var ec = embers.config;
        ec.setLooping(true);
        ec.setDuration(100);
        ec.setStartLifetime(new RandomConstant(30, 50));
        ec.setStartSpeed(new RandomConstant(0.8, 1.8));
        ec.setStartSize(new NumberFunction3(0.08, 0.08, 0.08));
        ec.setStartColor(NumberFunction.color(0xFFFF9A3C));
        ec.setMaxParticles(300);
        var cone = new Cone();
        cone.setAngle(18);
        ec.shape.setShape(cone);
        ec.shape.setScale(new NumberFunction3(0.5, 0.5, 0.5));
        ec.emission.setEmissionRate(NumberFunction.constant(2.5f));
        ec.lightEmission.setEnable(true);
        ec.lightEmission.setMaxLights(24);
        ec.lightEmission.setRatio(0.35f);
        ec.lightEmission.setIntensity(NumberFunction.constant(2.5f));
        ec.lightEmission.setRange(NumberFunction.constant(3));
        material(ec, KilaPresets.SPARKS.create());
        var flames = KilaGalleryScenario.showcase(KilaPresets.FIRE);
        flames.setName("flames");
        flames.config.setStartSize(new NumberFunction3(0.9, 0.9, 0.9));
        var smoke = new ParticleEmitter();
        smoke.setName("smoke");
        var sc = smoke.config;
        sc.setLooping(true);
        sc.setDuration(100);
        sc.setStartLifetime(NumberFunction.constant(70));
        sc.setStartSpeed(NumberFunction.constant(0.6f));
        sc.setStartSize(new NumberFunction3(1.8, 1.8, 1.8));
        sc.setStartColor(NumberFunction.color(0x90585858));
        sc.setMaxParticles(120);
        sc.shape.setScale(new NumberFunction3(0.4, 0.1, 0.4));
        sc.emission.setEmissionRate(NumberFunction.constant(0.6f));
        var smokeMaterial = new TextureMaterial();
        smokeMaterial.getLitParticles().setEnable(true);
        smokeMaterial.getLitParticles().intensity = 3;
        material(sc, smokeMaterial);
        smoke.transform().localPosition(new Vector3f(0, 1.6f, 0));
        play(ctx, o.offset(0, 1, -3), 0.5, 0.1, 0.5, fire, embers, flames, smoke);

        var circle = KilaGalleryScenario.showcase(KilaPresets.MAGIC_CIRCLE);
        circle.config.setStartSize(new NumberFunction3(3.2, 3.2, 3.2));
        circle.config.renderer.setRenderMode(ParticleRendererSetting.Mode.Horizontal);
        var circleLight = light(LightConfig.Type.Point, 0xB45CFF, 12, 8, 0, 1.2f, 0);
        circleLight.setName("circle light");
        play(ctx, o.offset(-6, 1, -2), 0.5, 0.05, 0.5, circle, circleLight);
        var shield = KilaGalleryScenario.showcase(KilaPresets.ENERGY_SHIELD);
        shield.config.setStartSize(new NumberFunction3(2.4, 2.4, 2.4));
        var shieldLight = light(LightConfig.Type.Point, 0x45D7FF, 10, 8, 0, 0.6f, 0);
        shieldLight.setName("shield light");
        play(ctx, o.offset(6, 1, -2), 0.5, 0.9, 0.5, shield, shieldLight);

        // warm light inside the temple, shining out of the door and the windows into the mist
        var inside = light(LightConfig.Type.Point, 0xFFC27A, 30, 14, 0, 0, 0);
        inside.setName("temple light");
        inside.config.getVolumetric().setEnable(true);
        inside.config.getVolumetric().setStrength(NumberFunction.constant(1.6f));
        play(ctx, o.offset(0, 4, 9), 0.5, 0.5, 0.5, inside);

        var spot = light(LightConfig.Type.Spot, 0xA9C8FF, 80, 34, 0, 0, 0);
        spot.config.setInnerAngle(8);
        spot.config.setOuterAngle(14);
        spot.config.getVolumetric().setEnable(true);
        spot.config.getVolumetric().setStrength(NumberFunction.constant(1.2f));
        var from = new Vector3f(-14, 15, -12);
        var target = new Vector3f(9, 0, -4);
        // a spot shines along its local -Y
        spot.transform().localRotation(new Quaternionf().rotationTo(new Vector3f(0, -1, 0),
                target.sub(from, new Vector3f()).normalize()));
        play(ctx, o, from.x + 0.5, from.y, from.z + 0.5, spot);

        // a faint moonlight over everything, so the stone reads where no colored light reaches
        var moon = light(LightConfig.Type.Point, 0x6F86C8, 3, 46, 0, 0, 0);
        moon.config.setCastShadows(false);
        play(ctx, o, 0.5, 26, -4.5, moon);

        var mist = new FogVolumeObject();
        mist.setName("mist");
        var mc = mist.config;
        mc.setShape(FogVolumeConfig.Shape.Box);
        mc.setLooping(true);
        mc.setDensity(NumberFunction.constant(1.0f));
        mc.setAbsorption(0.2f);
        mc.setEdgeFalloff(0.4f);
        mc.getNoise().setEnable(true);
        mc.getNoise().setScale(3f);
        mist.transform().localScale(new Vector3f(26, 7f, 22));
        play(ctx, o, 0.5, 3.2, -1.5, mist);
    }

    private static void material(ParticleConfig config, com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial material) {
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(material));
    }

    private static void setSmokeLit(TestContext ctx, boolean lit) {
        for (var executor : RUNNING) {
            var runtime = executor.getRuntime();
            if (runtime == null) continue;
            for (var object : runtime.objects.values()) {
                if (object instanceof ParticleEmitter emitter && "smoke".equals(emitter.getName())
                        && emitter.config.renderer.getMaterials().getFirst().getMaterial() instanceof TextureMaterial texture) {
                    texture.getLitParticles().setEnable(lit);
                }
            }
        }
    }

    private static void setEnabled(TestContext ctx, String name, boolean on) {
        for (var executor : RUNNING) {
            var runtime = executor.getRuntime();
            if (runtime == null) continue;
            for (var object : runtime.objects.values()) {
                if (object instanceof FXObject fx && name.equals(fx.getName())) fx.setEnabled(on);
            }
        }
    }

    private static void setVisible(TestContext ctx, String name, boolean visible) {
        for (var executor : RUNNING) {
            var runtime = executor.getRuntime();
            if (runtime == null) continue;
            for (var object : runtime.objects.values()) {
                if (name.equals(object.getName())) object.setSelfVisible(visible);
            }
        }
    }

    private static void emberLights(TestContext ctx, float intensity, float range) {
        for (var executor : RUNNING) {
            var runtime = executor.getRuntime();
            if (runtime == null) continue;
            for (var object : runtime.objects.values()) {
                if (object instanceof ParticleEmitter emitter && "embers".equals(emitter.getName())) {
                    emitter.config.lightEmission.setIntensity(NumberFunction.constant(intensity));
                    emitter.config.lightEmission.setRange(NumberFunction.constant(range));
                }
            }
        }
    }

    private static void templeLight(TestContext ctx, float intensity, float volumetric) {
        for (var executor : RUNNING) {
            var runtime = executor.getRuntime();
            if (runtime == null) continue;
            for (var object : runtime.objects.values()) {
                if (object instanceof LightObject light && "temple light".equals(light.getName())) {
                    light.runtime().intensity.set(NumberFunction.constant(intensity));
                    light.runtime().volumetricStrength.set(NumberFunction.constant(volumetric));
                }
            }
        }
    }

    private static void startGallery(TestContext ctx, BlockPos o) {
        stopAll();
        var presets = List.of(KilaPresets.FIRE, KilaPresets.CHARGE_ORB, KilaPresets.PORTAL, KilaPresets.ENERGY_SHIELD,
                KilaPresets.LIGHTNING, KilaPresets.SHOCKWAVE, KilaPresets.TOON_FIRE);
        for (int i = 0; i < presets.size(); i++) {
            var preset = presets.get(i);
            double x = (i - (presets.size() - 1) / 2.0) * 2.6;
            play(ctx, o, x + 0.5, preset.target() == KilaPresets.Target.MESH ? 2.2 : 2.4, -1.5, KilaGalleryScenario.showcase(preset));
        }
        var circle = KilaGalleryScenario.showcase(KilaPresets.MAGIC_CIRCLE);
        circle.config.setStartSize(new NumberFunction3(3.2, 3.2, 3.2));
        circle.config.renderer.setRenderMode(ParticleRendererSetting.Mode.Horizontal);
        play(ctx, o, 0.5, 1.05, -2.5, circle);
        var ground = KilaGalleryScenario.showcase(KilaPresets.GROUND_CIRCLE);
        play(ctx, o, -8.5, 1.05, -5.5, ground);
        var scan = KilaGalleryScenario.showcase(KilaPresets.CAUSTICS);
        play(ctx, o, 8.5, 1.05, -5.5, scan);
    }

    private static void startSparks(TestContext ctx, BlockPos o) {
        stopAll();
        var sparks = new ParticleEmitter();
        var config = sparks.config;
        config.setLooping(true);
        config.setDuration(100);
        config.setStartLifetime(new RandomConstant(50, 80));
        config.setStartSpeed(new RandomConstant(3, 7));
        config.setStartSize(new NumberFunction3(0.2, 0.2, 0.2));
        config.setStartColor(NumberFunction.color(0xFFFFB050));
        config.setMaxParticles(2000);
        config.setSimulationSpace(ParticleConfig.Space.World);
        var cone = new Cone();
        cone.setAngle(30);
        config.shape.setShape(cone);
        config.emission.setEmissionRate(NumberFunction.constant(20));
        var physics = config.physics;
        physics.setEnable(true);
        physics.setHasCollision(true);
        physics.setGravity(NumberFunction.constant(1.2f));
        physics.setBounceChance(NumberFunction.constant(1));
        physics.setBounceRate(NumberFunction.constant(0.45f));
        physics.setCollidedFriction(NumberFunction.constant(0.8f));
        config.lightEmission.setEnable(true);
        config.lightEmission.setMaxLights(64);
        config.lightEmission.setRatio(0.08f);
        config.lightEmission.setIntensity(NumberFunction.constant(3));
        config.lightEmission.setRange(NumberFunction.constant(2.5f));
        material(config, KilaPresets.SPARKS.create());
        var glow = light(LightConfig.Type.Point, 0xFF9A3C, 18, 10, 0, 0.5f, 0);
        play(ctx, o, 16.5, 6, 31.5, sparks, glow);
    }

    private static void stageFox(TestContext ctx) {
        var bytes = AnimatedGltfRenderScenario.readFixture();
        ctx.require("fox.glb was found", bytes != null);
        var target = new File(LDLib2.getAssetsDir(), "photon/models/uitest_wiki_fox.glb");
        try {
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("could not stage the fox", e);
        }
    }

    private static void startFoxes(TestContext ctx, BlockPos o) {
        stopAll();
        var foxes = new ParticleEmitter();
        var config = foxes.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        config.setStartSize(new NumberFunction3(0.02, 0.02, 0.02));
        config.setMaxParticles(9);
        config.shape.setShape(new Box());
        config.shape.setScale(new NumberFunction3(10, 0, 2));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(9));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        var source = new AnimatedGltfModelSource(FOX);
        source.setPerParticlePhase(true);
        source.setFrames(32);
        var renderer = config.renderer;
        renderer.setRenderMode(ParticleRendererSetting.Mode.Model);
        renderer.setModel(new MeshData(source));
        renderer.setUseGPUInstance(true);
        material(config, KilaPresets.HOLOGRAM.create());
        play(ctx, o, 0.5, 0.05, -21.5, foxes, light(LightConfig.Type.Point, 0x45D7FF, 8, 10, 0, 1.5f, 0));
    }

}
