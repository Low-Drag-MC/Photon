package com.lowdragmc.photon.uitest;

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
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comparison captures, not a test: one still scene of translucent effects (an HDR orb half sunk in water, a wisp behind
 * stained glass, overlapping smoke) under classic transparency (LATE and VANILLA), improved transparency with and
 * without Photon's OIT, and a shader pack, with and without bloom.
 */
@LDLRegisterClient(name = "photon_oit_showcase", group = "photon_showcase", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class OitShowcaseScenario implements UIScenario {

    private static final String PACK = "BSL_v10.1.3.zip";
    private static final AtomicReference<BlockPos> ORIGIN = new AtomicReference<>();

    /** {@code mode} null: the default composite mode. */
    private record Shot(String tag, boolean improvedTransparency, boolean photonOit, boolean bloom,
                        @Nullable FXCompositeMode mode, boolean pack) {
    }

    private static final List<Shot> SHOTS = List.of(
            new Shot("classic_bloom", false, true, true, null, false),
            new Shot("classic_no_bloom", false, true, false, null, false),
            new Shot("classic_vanilla_mode", false, true, true, FXCompositeMode.VANILLA, false),
            new Shot("oit_bloom", true, true, true, null, false),
            new Shot("oit_no_bloom", true, true, false, null, false),
            new Shot("improved_transparency_photon_oit_off", true, false, true, null, false),
            new Shot("iris_bloom", false, true, true, null, true),
            new Shot("iris_no_bloom", false, true, false, null, true));

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("showcase").requiresWorld(true).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("hide the gui, no section fade-in", ctx -> {
                    HiddenGui.hide(ctx.mc());
                    LightTestConfig.set(ctx.mc().options.chunkSectionFadeInTime(), 0.0);
                })
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
                            Set.<Relative>of(), 0f, 14f, true);
                })
                .serverTicks(20)
                .step("the effects", OitShowcaseScenario::start)
                .frames(10);
        var improved = new boolean[]{false};
        var packLoaded = new boolean[]{false};
        for (int i = 0; i < SHOTS.size(); i++) {
            var shot = SHOTS.get(i);
            var number = i + 1;
            if (shot.pack() && !packLoaded[0]) {
                packLoaded[0] = true;
                int[] waited = {0};
                s.step("improved transparency off",
                                ctx -> LightTestConfig.set(ctx.mc().options.improvedTransparency(), false))
                        .step("load " + PACK, ctx -> {
                            if (!LightTestConfig.hasShaderPack(PACK)) {
                                LightTestConfig.missingShaderPack(ctx, PACK);
                                return;
                            }
                            LightTestConfig.selectShaderPack(PACK);
                        })
                        .step("wait for the pack", ctx -> {
                            if (LightTestConfig.hasShaderPack(PACK) && !IrisCompat.isUsingShaderPack()
                                    && ++waited[0] < 600) ctx.repeat("pack");
                        })
                        .frames(120);
            }
            if (!shot.pack() && shot.improvedTransparency() != improved[0]) {
                improved[0] = shot.improvedTransparency();
                s.step("improved transparency " + (shot.improvedTransparency() ? "on" : "off"),
                                ctx -> LightTestConfig.set(ctx.mc().options.improvedTransparency(),
                                        shot.improvedTransparency()))
                        .ticks(5)
                        .waitUntil("sections compiled", ctx -> ctx.mc().levelRenderer.hasRenderedAllSections())
                        .frames(10);
            }
            s.step("settings: " + shot.tag(), ctx -> {
                        LightTestConfig.set(PhotonConfig.INSTANCE.orderIndependentTransparency, shot.photonOit());
                        LightTestConfig.set(PhotonConfig.INSTANCE.enableBloom, shot.bloom());
                        LightTestConfig.set(PhotonConfig.INSTANCE.fxCompositeMode,
                                shot.mode() == null ? FXCompositeMode.LATE : shot.mode());
                    })
                    .frames(shot.pack() ? 40 : 15)
                    .screenshot(number + "_" + shot.tag());
        }
        s.teardown("stop the effects", OitShowcaseScenario::stop)
                .teardown("put the settings back", ctx -> LightTestConfig.restore());
        s.teardownServer("put the world back", sc -> LightSite.release(sc.player()));
    }

    /** Seen from the origin along +z: the pool on the right (-x), the glass on the left (+x), a wall behind. */
    private static void build(ServerLevel level, BlockPos origin) {
        var air = Blocks.AIR.defaultBlockState();
        var stone = Blocks.STONE.defaultBlockState();
        for (int x = -12; x <= 12; x++) {
            for (int z = -2; z <= 20; z++) {
                for (int y = 1; y <= 12; y++) {
                    level.setBlock(origin.offset(x, y, z), air, 2);
                }
                for (int y = -3; y <= 0; y++) {
                    level.setBlock(origin.offset(x, y, z), stone, 2);
                }
            }
        }
        for (int x = -12; x <= 12; x++) {
            for (int y = 1; y <= 10; y++) {
                level.setBlock(origin.offset(x, y, 16), stone, 2);
            }
        }
        for (int x = -7; x <= -1; x++) {
            for (int z = 6; z <= 12; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(origin.offset(x, -1, z), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        for (int x = 1; x <= 6; x++) {
            for (int y = 1; y <= 6; y++) {
                level.setBlock(origin.offset(x, y, 5), Blocks.STAINED_GLASS.red().defaultBlockState(), 2);
            }
        }
    }

    private record Puff(double x, double y, double z, float size, int argb, @Nullable HDRColor hdr) {
    }

    private static final List<Puff> PUFFS = List.of(
            new Puff(-3.5, 1.1, 9.0, 2.6f, 0xFFFF9020, new HDRColor(2.5f, 0.9f, 0.1f, 1f, 1f)),
            new Puff(3.2, 2.8, 9.0, 3.6f, 0xC020E0FF, null),
            new Puff(0.3, 2.4, 7.0, 2.4f, 0x90FFFFFF, null),
            new Puff(1.2, 3.3, 8.6, 2.6f, 0x90F0F0F0, null),
            new Puff(-0.6, 4.1, 10.2, 2.8f, 0x90E8E8E8, null));

    private static void start(TestContext ctx) {
        stop(ctx);
        var player = ctx.requirePlayer();
        var executors = new ArrayList<BlockEffectExecutor>();
        for (var puff : PUFFS) {
            var emitter = new ParticleEmitter();
            var config = emitter.config;
            config.setLooping(true);
            config.setDuration(4000);
            config.setStartLifetime(NumberFunction.constant(4000));
            config.setStartSpeed(NumberFunction.constant(0));
            config.setStartSize(new NumberFunction3(puff.size(), puff.size(), puff.size()));
            config.setStartColor(NumberFunction.color(puff.argb()));
            config.setMaxParticles(1);
            config.shape.setScale(new NumberFunction3(0, 0, 0));
            config.emission.setEmissionRate(NumberFunction.constant(0));
            var burst = new EmissionSetting.Burst();
            burst.time = 0;
            burst.setCount(NumberFunction.constant(1));
            burst.cycles = 1;
            config.emission.getBursts().add(burst);
            var material = new TextureMaterial(Photon.id("textures/particle/circle.png"));
            if (puff.hdr() != null) material.setHdr(puff.hdr());
            config.renderer.getMaterials().clear();
            config.renderer.getMaterials().add(new MaterialSetting(material));
            config.renderer.setLayer(RendererSetting.Layer.Translucent);
            config.renderer.setCompositeMode(FXCompositeMode.INHERIT);

            var fx = new FX();
            fx.getFxData().objects().add(emitter);
            var origin = ORIGIN.get();
            var block = origin.offset((int) Math.floor(puff.x()), (int) Math.floor(puff.y()),
                    (int) Math.floor(puff.z()));
            var executor = new BlockEffectExecutor(fx, player.level(), block);
            executor.setOffset(puff.x() - Math.floor(puff.x()), puff.y() - Math.floor(puff.y()),
                    puff.z() - Math.floor(puff.z()));
            executor.setAllowMulti(true);
            executor.start();
            executors.add(executor);
        }
        ctx.put("effects", executors);
    }

    private static void stop(TestContext ctx) {
        List<BlockEffectExecutor> executors = ctx.get("effects");
        if (executors != null) {
            for (var executor : executors) {
                if (executor.getRuntime() != null) executor.getRuntime().destroy(true);
            }
        }
        ctx.state().remove("effects");
    }
}
