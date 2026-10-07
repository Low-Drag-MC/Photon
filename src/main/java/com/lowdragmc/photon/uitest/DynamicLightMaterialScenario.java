package com.lowdragmc.photon.uitest;

import com.lowdragmc.kilagraph.rendertype.format.KGVertexFormat;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentAlphaBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentBaseColorBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.input.basic.Vec3Node;
import com.lowdragmc.kilagraph.rendertype.runtime.KGShaderResourceProvider;
import com.lowdragmc.kilagraph.test.gametest.KGGameTestHelpers;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
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
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.LitParticles;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.client.shadergraph.nodes.DynamicLightNode;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL20;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic lights on the materials users write themselves: a ShaderGraph lit through the Dynamic Light node,
 * with and without a normal, and a custom shader that includes {@code photon:lit_particle.glsl}. Three opaque
 * quads in a row (no normal / a normal facing the camera / the custom shader), shot unlit, with a red light
 * between them and the camera, and with it behind them — on the CPU path and instanced.
 */
@LDLRegisterClient(name = "dynamic_light_materials", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class DynamicLightMaterialScenario implements UIScenario {
    private static final double DISTANCE = 4;
    private static final double SPACING = 1.4;
    private static final double LIGHT_OFFSET = 1.5;
    private static final float INTENSITY = 6;
    private static final float RANGE = 8;
    private static final String[] QUADS = {"omni", "facing", "custom"};
    private static final AtomicReference<Long> DAY_TIME = new AtomicReference<>();
    private static final AtomicReference<Boolean> DAYLIGHT = new AtomicReference<>(true);
    /** LitParticleParams.x as the custom shader's last scene draw left it, and the defines it drew with */
    private static final AtomicReference<Float> CUSTOM_LIT = new AtomicReference<>();
    private static final AtomicReference<Set<String>> CUSTOM_DEFINES = new AtomicReference<>();
    private static final List<File> FILES = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render", "light").requiresWorld(true);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("the lit graph links on every path", DynamicLightMaterialScenario::compileMatrix)
                .step("a lit custom shader hides and skips the light uniforms", DynamicLightMaterialScenario::customShaderRows)
                .step("settings", ctx -> {
                    ctx.mc().options.hideGui = true;
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.volumetricLights, false);
                    LightDemoScene.stopLights();
                })
                .server("pin the sun at noon", sc -> {
                    var level = sc.level();
                    DAY_TIME.set(level.getDayTime());
                    DAYLIGHT.set(level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
                    level.setDayTime(6000);
                })
                .ticks(2)
                .waitUntil("noon reached the client", ctx -> ctx.mc().level != null
                        && Math.abs(ctx.mc().level.getDayTime() % 24000 - 6000) < 40)
                .ticks(10)
                .step("look straight along a block axis", ctx -> {
                    var player = ctx.requirePlayer();
                    float yaw = player.getDirection().toYRot();
                    player.setYRot(yaw);
                    player.yRotO = yaw;
                    player.setXRot(0);
                    player.xRotO = 0;
                })
                .ticks(10)
                .step("anchor the row in front of the camera", ctx -> {
                    var player = ctx.requirePlayer();
                    var direction = player.getDirection();
                    var forward = new Vec3(direction.getStepX(), 0, direction.getStepZ());
                    var right = new Vec3(direction.getClockWise().getStepX(), 0, direction.getClockWise().getStepZ());
                    ctx.put("forward", forward);
                    ctx.put("right", right);
                    ctx.put("centre", player.getEyePosition().add(forward.scale(DISTANCE)));
                })
                .frames(5)
                .screenshot("bare")
                .step("write the graphs", DynamicLightMaterialScenario::writeGraphs);
        rounds(s, "cpu", false);
        rounds(s, "gpu", true);
        s.step("the pictures", DynamicLightMaterialScenario::comparePictures)
                .teardown("stop the effect and the lights", ctx -> {
                    stop(ctx);
                    removeLight(ctx);
                    LightTestConfig.restore();
                    ctx.mc().options.hideGui = false;
                })
                .teardown("delete the graphs", ctx -> {
                    for (var file : FILES) {
                        ShaderGraphRuntime.invalidate(new FilePath(file));
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                    FILES.clear();
                })
                .teardownServer("put the clock back", sc -> {
                    if (DAY_TIME.get() == null) return;
                    sc.level().setDayTime(DAY_TIME.get());
                    sc.level().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(DAYLIGHT.get(), sc.level().getServer());
                    DAY_TIME.set(null);
                });
    }

    private static void rounds(ScenarioBuilder s, String path, boolean instanced) {
        s.step("start the quads (" + path + ")", ctx -> start(ctx, instanced))
                .frames(20)
                .screenshot(path + "_dark")
                .step("the custom shader drew unlit (" + path + ")", ctx -> customLit(ctx, path + " dark", instanced, 0f))
                .step("a light in front (" + path + ")", ctx -> addLight(ctx, -LIGHT_OFFSET))
                .frames(10)
                .screenshot(path + "_front")
                .step("the custom shader drew lit (" + path + ")", ctx -> customLit(ctx, path + " front", instanced, 1f))
                .step("the light behind (" + path + ")", ctx -> addLight(ctx, LIGHT_OFFSET))
                .frames(10)
                .screenshot(path + "_back")
                .step("lights out (" + path + ")", ctx -> {
                    removeLight(ctx);
                    stop(ctx);
                })
                .frames(2);
    }

    // ---- graphs ------------------------------------------------------------------------------------

    /** Base colour = the light, alpha 1; {@code normal} null leaves the node's normal unconnected. */
    private static ShaderGraph litGraph(@Nullable Vec3 normal) {
        var graph = new ShaderGraph() {
            @Override
            protected void initializeDefaultEntityShader() {
            }
        };
        var light = KGGameTestHelpers.addRegisteredNode(graph, DynamicLightNode.class);
        if (normal != null) {
            var constant = KGGameTestHelpers.addRegisteredNode(graph, Vec3Node.class);
            KGGameTestHelpers.setInputConstant(constant, "x", (float) normal.x);
            KGGameTestHelpers.setInputConstant(constant, "y", (float) normal.y);
            KGGameTestHelpers.setInputConstant(constant, "z", (float) normal.z);
            KGGameTestHelpers.wire(graph, light.getInputsById().get("normal"), constant.getOutputsById().get("out"));
        }
        var stage = graph.getFragmentStageModel();
        var color = KGGameTestHelpers.addBlock(graph, stage, FragmentBaseColorBlock.class);
        KGGameTestHelpers.wire(graph, color.getInputsById().get("color"), light.getOutputsById().get("light"));
        KGGameTestHelpers.setInputConstant(KGGameTestHelpers.addBlock(graph, stage, FragmentAlphaBlock.class), "alpha", 1f);
        return graph;
    }

    private static void compileMatrix(TestContext ctx) {
        for (var normal : new Vec3[]{null, new Vec3(0, 0, 1)}) {
            var name = normal == null ? "without a normal" : "with a normal";
            var graph = litGraph(normal);
            var compiled = new PhotonShaderCompiler(graph).compile();
            ctx.require("the graph " + name + " compiles", !compiled.hasStageErrors());
            ctx.check("it includes the lights (" + name + ")", LitParticles.includedBy(compiled.fragmentSource()),
                    "included", "missing");
            var format = KGVertexFormat.of(compiled.settings().vertexFormatElements());
            for (var context : List.of(MaterialContext.NORMAL, MaterialContext.PARTICLE_CPU_DATA,
                    MaterialContext.PARTICLE_INSTANCE, MaterialContext.PARTICLE_MODEL_INSTANCE,
                    MaterialContext.PARTICLE_MODEL_INSTANCE_TANGENT, MaterialContext.PARTICLE_MODEL_INSTANCE_VAT,
                    MaterialContext.PARTICLE_MODEL_INSTANCE_VAT_TANGENT, MaterialContext.TRAIL_INSTANCE,
                    MaterialContext.ARA_TRAIL_INSTANCE, MaterialContext.ARA_TRAIL_TUBE_INSTANCE,
                    MaterialContext.BEAM_INSTANCE)) {
                var path = context.getVariantKey().isEmpty() ? "cpu" : context.getVariantKey();
                var shader = ShaderGraphRuntime.createShaderInstance(compiled, format, context.getShaderDefines());
                ctx.check("the " + path + " variant links (" + name + ")", shader != null, "linked", "null");
                if (shader == null) continue;
                var inactive = new ArrayList<String>();
                for (var uniform : LitParticles.UNIFORMS) {
                    if (shader.getUniform(uniform) == null || GL20.glGetUniformLocation(shader.getId(), uniform) < 0) {
                        inactive.add(uniform);
                    }
                }
                for (var sampler : LitParticles.SAMPLERS) {
                    if (GL20.glGetUniformLocation(shader.getId(), sampler) < 0) inactive.add(sampler);
                }
                ctx.check(path + " declares and reads every light uniform (" + name + ")", inactive.isEmpty(),
                        "none missing", inactive);
                shader.close();
            }
            var editor = new PhotonShaderCompiler(graph).editorPreview().compile();
            ctx.check("the editor preview compiles (" + name + ")", !editor.hasStageErrors(), "no errors",
                    editor.stageErrors());
            var preview = KGShaderResourceProvider.createShaderInstance(editor,
                    KGVertexFormat.of(editor.settings().vertexFormatElements()), Set.of("UITEST_EDITOR_PREVIEW"));
            ctx.check("and links (" + name + ")", preview != null, "linked", "null");
            if (preview != null) preview.close();
            var lightNode = graph.graphModel.getNodeModels().stream()
                    .filter(node -> node instanceof ICustomNodeModel custom && custom.getNode() instanceof DynamicLightNode)
                    .map(NodeModel.class::cast)
                    .findFirst().orElseThrow();
            var thumbnail = new PhotonShaderCompiler(graph).compilePreview(lightNode.getOutputsById().get("light"));
            ctx.check("the node preview compiles without the lights (" + name + ")", !thumbnail.hasStageErrors()
                            && !LitParticles.includedBy(thumbnail.fragmentSource()), "no errors, no include",
                    thumbnail.stageErrors());
        }
    }

    private static void writeGraphs(TestContext ctx) {
        var dir = new File(LDLib2.getAssetsDir(), "ldlib2/resources/global");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        var forward = ctx.<Vec3>get("forward");
        // facing the camera, so a light behind the quads falls on its back
        var graphs = new Object[][]{{"omni", litGraph(null)}, {"facing", litGraph(forward.scale(-1))}};
        for (var entry : graphs) {
            var file = new File(dir, "uitest_dynamic_light_" + entry[0] + ShaderGraphResource.INSTANCE.getFileExtension());
            // what FileResourceProvider reads: {data, type}, uncompressed
            var tag = new CompoundTag();
            tag.put("data", ShaderGraphResource.INSTANCE.serializeGraph((ShaderGraph) entry[1]));
            tag.putString("type", ShaderGraphResource.INSTANCE.getName());
            boolean written;
            try {
                NbtIo.write(tag, file.toPath());
                written = file.isFile();
            } catch (Exception e) {
                written = false;
            }
            ctx.check("wrote " + file.getName(), written, "written", "failed");
            FILES.add(file);
            ctx.put("graph_" + entry[0], file);
        }
    }

    // ---- the custom shader -------------------------------------------------------------------------

    /**
     * {@code hdr_particle} as a custom shader: it includes the lights. Its {@code Sampler0} is whatever the last
     * draw left, so it is pinned to white here, and what the lights' binding set is kept for the checks.
     */
    private static final class WhiteCustomShader extends CustomShaderMaterial {
        WhiteCustomShader() {
            super(Photon.id("hdr_particle"));
        }

        @Override
        public ShaderInstance getShader(MaterialContext context) {
            int white = Minecraft.getInstance().getTextureManager().getTexture(KilaTextures.WHITE).getId();
            RenderSystem.setShaderTexture(0, white);
            var shader = super.getShader(context);
            shader.setSampler("Sampler0", white);
            var lit = shader.getUniform("LitParticleParams");
            if (!context.isRenderingPreview() && lit != null) {
                CUSTOM_LIT.set(lit.getFloatBuffer().get(0));
                CUSTOM_DEFINES.set(context.getShaderDefines());
            }
            return shader;
        }
    }

    private static void customShaderRows(TestContext ctx) {
        var lit = new CustomShaderMaterial(Photon.id("hdr_particle"));
        lit.getShader(MaterialContext.PREVIEW);
        ctx.require("hdr_particle compiles as a custom shader", !lit.isCompiledError());
        ctx.check("hdr_particle is lit by dynamic lights", lit.isLitByDynamicLights(), true, false);
        var circle = new CustomShaderMaterial(Photon.id("circle"));
        circle.getShader(MaterialContext.PREVIEW);
        ctx.check("the plain circle shader is not", !circle.isCompiledError() && !circle.isLitByDynamicLights(),
                "compiled, unlit", "error=" + circle.isCompiledError() + " lit=" + circle.isLitByDynamicLights());
        var group = new ConfiguratorGroup();
        lit.buildConfigurator(group);
        var labels = new ArrayList<String>();
        collectLabels(group, labels);
        var leaked = labels.stream().filter(LitParticles::declares).toList();
        ctx.check("the inspector shows the shader's own uniforms", labels.contains("DiscardThreshold"),
                "DiscardThreshold among the rows", labels);
        ctx.check("but none of the lights'", leaked.isEmpty(), "none", leaked);
        var saved = lit.serializeAdditionalNBT(Platform.getFrozenRegistry());
        var uniforms = ((CompoundTag) saved).getCompound("shaderData").getCompound("uniforms");
        var stored = LitParticles.UNIFORMS.stream().filter(uniforms::contains).toList();
        ctx.check("and saving keeps its own uniforms", uniforms.contains("DiscardThreshold"), "DiscardThreshold saved",
                uniforms.getAllKeys());
        ctx.check("without the lights'", stored.isEmpty(), "none", stored);
    }

    private static void collectLabels(Configurator configurator, List<String> labels) {
        labels.add(configurator.label.getText().getString());
        if (configurator instanceof ConfiguratorGroup group) {
            for (var child : group.getConfigurators()) collectLabels(child, labels);
        }
    }

    private static void customLit(TestContext ctx, String round, boolean instanced, float expected) {
        var value = CUSTOM_LIT.get();
        ctx.check("the custom shader's lights were switched for its draw (" + round + ")", value != null && value == expected,
                expected, value);
        var defines = CUSTOM_DEFINES.get();
        ctx.check("it drew " + (instanced ? "instanced" : "on the CPU path") + " (" + round + ")", defines != null
                && defines.contains("PARTICLE_INSTANCE") == instanced, instanced ? "PARTICLE_INSTANCE" : "no instancing", defines);
    }

    // ---- the scene ---------------------------------------------------------------------------------

    private static ParticleEmitter quad(IMaterial material, Vec3 offset, boolean instanced) {
        var emitter = new ParticleEmitter();
        var config = emitter.config;
        config.setLooping(true);
        config.setDuration(400);
        config.setStartLifetime(NumberFunction.constant(400));
        config.setStartSpeed(NumberFunction.constant(0));
        // half the side: one block across, with a gap to the next
        config.setStartSize(new NumberFunction3(0.5, 0.5, 0.5));
        config.setStartColor(NumberFunction.color(0xFF404040));
        config.setMaxParticles(1);
        config.shape.setScale(new NumberFunction3(0, 0, 0));
        config.emission.setEmissionRate(NumberFunction.constant(0));
        var burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(1));
        burst.cycles = 1;
        config.emission.getBursts().add(burst);
        config.renderer.getMaterials().clear();
        config.renderer.getMaterials().add(new MaterialSetting(material));
        config.renderer.setUseGPUInstance(instanced);
        emitter.transform().localPosition(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
        return emitter;
    }

    private static void start(TestContext ctx, boolean instanced) {
        stop(ctx);
        CUSTOM_LIT.set(null);
        CUSTOM_DEFINES.set(null);
        var right = ctx.<Vec3>get("right");
        var fx = new FX();
        fx.getFxData().objects().add(quad(new ShaderGraphMaterial(new FilePath(ctx.<File>get("graph_omni"))),
                right.scale(-SPACING), instanced));
        fx.getFxData().objects().add(quad(new ShaderGraphMaterial(new FilePath(ctx.<File>get("graph_facing"))),
                Vec3.ZERO, instanced));
        fx.getFxData().objects().add(quad(new WhiteCustomShader(), right.scale(SPACING), instanced));
        var centre = ctx.<Vec3>get("centre");
        var block = BlockPos.containing(centre);
        var executor = new BlockEffectExecutor(fx, ctx.requirePlayer().level(), block);
        // the executor centres the effect on the block
        executor.setOffset(centre.x - block.getX() - 0.5, centre.y - block.getY() - 0.5, centre.z - block.getZ() - 0.5);
        executor.setAllowMulti(false);
        executor.start();
        ctx.put("executor", executor);
        ctx.check("the effect started", executor.getRuntime() != null, "a runtime", "null");
    }

    private static void stop(TestContext ctx) {
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) executor.getRuntime().destroy(true);
        ctx.state().remove("executor");
    }

    /** A red light on the middle quad's axis, {@code along} blocks behind it (negative: between it and the camera). */
    private static void addLight(TestContext ctx, double along) {
        removeLight(ctx);
        var at = ctx.<Vec3>get("centre").add(ctx.<Vec3>get("forward").scale(along));
        ctx.put("light", PhotonLights.add(new DynamicLight().at(at.x, at.y, at.z).color(1f, 0.3f, 0.1f)
                .intensity(INTENSITY).range(RANGE).shadows(false)));
    }

    private static void removeLight(TestContext ctx) {
        var light = ctx.<DynamicLight>get("light");
        if (light != null) PhotonLights.remove(light);
        ctx.state().remove("light");
    }

    // ---- checks ------------------------------------------------------------------------------------

    private static ScreenshotCompare capture(TestContext ctx, String tag) {
        var capture = ScreenshotCompare.load(ctx, tag);
        ctx.require("the " + tag + " capture exists", capture != null);
        return capture;
    }

    /** A small box in the middle of each quad, left to right: where the unlit shot differs from the bare one. */
    private static List<ScreenshotCompare.Region> boxes(TestContext ctx, ScreenshotCompare bare, ScreenshotCompare dark) {
        var window = ctx.mc().getWindow();
        var all = dark.diff(bare, 40, new ScreenshotCompare.Region(0, 0, window.getWidth(), window.getHeight())).changed();
        ctx.require("the quads are on screen", all != null);
        int y = (all.top() + all.bottom()) / 2;
        var runs = new ArrayList<int[]>();
        int start = -1;
        for (int x = all.left(); x <= all.right(); x++) {
            boolean changed = x < all.right() && differs(dark.rgb(x, y), bare.rgb(x, y));
            if (changed && start < 0) start = x;
            if (!changed && start >= 0) {
                if (x - start > 20) runs.add(new int[]{start, x});
                start = -1;
            }
        }
        ctx.require("three quads side by side, found " + runs.size(), runs.size() == 3);
        var boxes = new ArrayList<ScreenshotCompare.Region>();
        for (var run : runs) {
            int x = (run[0] + run[1]) / 2;
            int r = Math.max(3, (run[1] - run[0]) / 5);
            boxes.add(new ScreenshotCompare.Region(x - r, y - r, x + r, y + r));
        }
        return boxes;
    }

    private static boolean differs(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)) > 40) return true;
        }
        return false;
    }

    /** The red photon_surface_light gives a square-on quad {@code distance} from the light, 0..255. */
    private static double expectedRed(double distance) {
        double d2 = distance * distance;
        double r2 = RANGE * RANGE;
        double window = Math.clamp(1 - d2 * d2 / (r2 * r2), 0, 1);
        double attenuation = window * window / (d2 + 1);
        return 255 * (1 - Math.exp(-INTENSITY * attenuation));
    }

    private static String rgb(double[] c) {
        return "rgb(%.0f, %.0f, %.0f)".formatted(c[0], c[1], c[2]);
    }

    private static void comparePictures(TestContext ctx) {
        for (var path : List.of("cpu", "gpu")) {
            var dark = capture(ctx, path + "_dark");
            var front = capture(ctx, path + "_front");
            var back = capture(ctx, path + "_back");
            var boxes = boxes(ctx, capture(ctx, "bare"), dark);
            var colours = new double[3][3][];
            for (int quad = 0; quad < 3; quad++) {
                var box = boxes.get(quad);
                colours[quad] = new double[][]{dark.mean(box), front.mean(box), back.mean(box)};
                ctx.log("%s %s: dark %s, front %s, back %s".formatted(path, QUADS[quad], rgb(colours[quad][0]),
                        rgb(colours[quad][1]), rgb(colours[quad][2])));
            }
            for (int quad = 0; quad < 2; quad++) {
                var c = colours[quad];
                var name = path + " " + QUADS[quad];
                ctx.check("unlit, the " + name + " graph is black", Math.max(c[0][0], Math.max(c[0][1], c[0][2])) < 25,
                        "< 25", rgb(c[0]));
                ctx.check("a light in front turns the " + name + " graph red", c[1][0] > 80 && c[1][0] > 3 * c[1][2],
                        "red > 80, > 3x blue", rgb(c[1]));
            }
            ctx.check(path + ": the light behind still reaches the graph without a normal",
                    colours[0][2][0] > 80, "red > 80", rgb(colours[0][2]));
            double side = expectedRed(Math.hypot(SPACING, LIGHT_OFFSET));
            double ahead = expectedRed(LIGHT_OFFSET);
            ctx.check(path + ": the graph without a normal gets the falloff the shader promises, from either side",
                    Math.abs(colours[0][1][0] - side) < 5 && Math.abs(colours[0][2][0] - side) < 5,
                    "red %.0f".formatted(side), rgb(colours[0][1]) + " / " + rgb(colours[0][2]));
            ctx.check(path + ": and the one facing the light square on, the nearer light's",
                    Math.abs(colours[1][1][0] - ahead) < 5, "red %.0f".formatted(ahead), rgb(colours[1][1]));
            ctx.check(path + ": but not the one whose normal faces away from it", colours[1][2][0] < 25,
                    "red < 25", rgb(colours[1][2]));
            var custom = colours[2];
            ctx.check(path + ": unlit, the custom shader is its own grey", Math.abs(custom[0][0] - custom[0][2]) < 8
                    && custom[0][0] > 30 && custom[0][0] < 110, "grey 30 .. 110", rgb(custom[0]));
            ctx.check(path + ": a light in front reddens the custom shader", custom[1][0] - custom[0][0] > 25
                    && Math.abs(custom[1][2] - custom[0][2]) < 12, "red +25, blue about the same",
                    rgb(custom[0]) + " -> " + rgb(custom[1]));
            ctx.check(path + ": so does the light behind", custom[2][0] - custom[0][0] > 25, "red +25",
                    rgb(custom[0]) + " -> " + rgb(custom[2]));
        }
    }
}
