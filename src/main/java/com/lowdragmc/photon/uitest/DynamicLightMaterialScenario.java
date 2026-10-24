package com.lowdragmc.photon.uitest;

import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentAlphaBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentBaseColorBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.input.basic.Vec3Node;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeFactory;
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
import com.lowdragmc.photon.client.fx.compat.ShaderFormatConverter;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.LitParticles;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaTextures;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.lowdragmc.photon.client.light.PhotonLights;
import com.lowdragmc.photon.client.light.dev.LightDemoScene;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.client.shadergraph.nodes.DynamicLightNode;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic lights on the materials users write themselves: a ShaderGraph lit through the Dynamic Light node,
 * with and without a normal, and a custom shader that includes {@code photon:lit_particle.glsl}. Three opaque
 * quads in a row (no normal / a normal facing the camera / the custom shader), shot unlit, with a red light
 * between them and the camera, and with it behind them — on the CPU path and instanced.
 * <p>
 * The custom shader is the one 1.21 used, 1.21's own {@code hdr_particle}, as a 1.21 user's shader: put through
 * {@code ShaderFormatConverter} and loaded from the LDLib2 assets folder. 26.1 ships an {@code hdr_particle} of its own
 * that is the texture material's stage, not a custom shader.
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
    /** Where the converted 1.21 shader is loaded from: the LDLib2 assets folder, as a user's would be. */
    private static final Identifier LEGACY = Identifier.fromNamespaceAndPath("photon_uitest", "legacy_lit");
    private static final AtomicReference<Long> DAY_TIME = new AtomicReference<>();
    private static final AtomicReference<Boolean> DAYLIGHT = new AtomicReference<>(true);
    /** The geometry each lit draw of the custom shader in the scene went down, read off its pipeline */
    private static final Set<String> CUSTOM_DRAWS = ConcurrentHashMap.newKeySet();
    /** Whether the frame had lights at the custom shader's last draw: 1.21's light switch, 1 on, 0 off. */
    private static final AtomicReference<Float> CUSTOM_LIT = new AtomicReference<>();
    private static final List<File> FILES = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "render", "light").requiresWorld(true);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("a 1.21 lit custom shader, converted as a user's would be", DynamicLightMaterialScenario::installLegacyShader)
                .waitUntil("the resources reloaded", ctx -> ctx.<CompletableFuture<?>>get("reload").isDone())
                .frames(5)
                .step("the lit graph links on every path", DynamicLightMaterialScenario::compileMatrix)
                .step("a lit custom shader hides and skips the light uniforms", DynamicLightMaterialScenario::customShaderRows)
                .step("settings", ctx -> {
                    HiddenGui.hide(ctx.mc());
                    LightTestConfig.set(PhotonConfig.INSTANCE.dynamicLights, true);
                    LightTestConfig.set(PhotonConfig.INSTANCE.volumetricLights, false);
                    LightDemoScene.stopLights();
                })
                .server("pin the sun at noon", sc -> {
                    var level = sc.level();
                    DAY_TIME.set(TestClock.time(level));
                    DAYLIGHT.set(TestClock.advancing(level));
                    TestClock.setAdvancing(level, false);
                    TestClock.set(level, 6000);
                })
                .ticks(2)
                .waitUntil("noon reached the client", ctx -> ctx.mc().level != null
                        && Math.abs(TestClock.time(ctx.mc().level) % 24000 - 6000) < 40)
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
                    HiddenGui.restore(ctx.mc());
                })
                .teardown("delete the converted shader", ctx -> {
                    for (var extension : new String[]{".json", ".fsh"}) {
                        //noinspection ResultOfMethodCallIgnored
                        legacyFile(extension).delete();
                    }
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
                    TestClock.set(sc.level(), DAY_TIME.get());
                    TestClock.setAdvancing(sc.level(), DAYLIGHT.get());
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
            // the generated GLSL reaches the device through KilaGraph's source registry, as a graph material's own does
            RenderTypeFactory.getOrBuildPipeline(compiled);
            ctx.check("it includes the lights (" + name + ")", LitParticles.includedBy(compiled.fragmentSource()),
                    "included", "missing");
            var key = PhotonPipelines.ParticlePipelineKey.DEFAULT;
            var lights = List.of(PhotonPipelines.LIGHT_DATA, PhotonPipelines.CLUSTER_GRID, PhotonPipelines.CLUSTER_INDICES,
                    PhotonPipelines.VISIBILITY_ATLAS, PhotonPipelines.LIGHTS_BLOCK);
            var paths = new LinkedHashMap<String, RenderPipeline>();
            paths.put("cpu", PhotonPipelines.graphShader(compiled, null, key, 0L, false));
            paths.put(PhotonPipelines.CPU_DATA_DEFINE, PhotonPipelines.graphShader(compiled, null, key, 0L, false, true));
            for (var variant : PhotonPipelines.InstancedVariant.values()) {
                paths.put(String.join("+", variant.defines), PhotonPipelines.graphShader(compiled, variant, key, 0L, false));
            }
            paths.forEach((path, pipeline) -> {
                var linked = RenderSystem.getDevice().precompilePipeline(pipeline);
                ctx.check("the " + path + " variant links (" + name + ")", linked.isValid(), "linked", "invalid");
                if (!(linked instanceof GlRenderPipeline gl) || !linked.isValid()) return;
                var live = gl.program().getUniforms().keySet();
                var inactive = lights.stream().filter(light -> !live.contains(light)).toList();
                ctx.check(path + " declares and reads every light binding (" + name + ")", inactive.isEmpty()
                        && PhotonPipelines.isLit(pipeline), "none missing, lit", inactive);
            });
            var editor = new PhotonShaderCompiler(graph).editorPreview().compile();
            ctx.check("the editor preview compiles (" + name + ")", !editor.hasStageErrors(), "no errors",
                    editor.stageErrors());
            ctx.check("and links (" + name + ")", !editor.hasStageErrors() && RenderSystem.getDevice()
                    .precompilePipeline(RenderTypeFactory.getOrBuildPipeline(editor)).isValid(), "linked", "invalid");
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

    private static File legacyFile(String extension) {
        return new File(LDLib2.getAssetsDir(), LEGACY.getNamespace() + "/shaders/core/" + LEGACY.getPath() + extension);
    }

    /**
     * 1.21's hdr_particle, converted, written to the LDLib2 assets folder (where a user keeps their shaders) with its
     * json pointed at it, then a resource reload.
     */
    private static void installLegacyShader(TestContext ctx) {
        var conversion = ShaderFormatConverter.convertFragment(LegacyLitShader.FRAGMENT,
                ShaderFormatConverter.parseJsonDefaults(LegacyLitShader.JSON),
                ShaderFormatConverter.parseJsonInts(LegacyLitShader.JSON));
        ctx.check("the converter takes it without a warning", conversion.warnings().isEmpty(), "none",
                conversion.warnings());
        var json = LegacyLitShader.JSON.replace("\"photon:hdr_particle\"", "\"" + LEGACY + "\"");
        boolean written;
        try {
            var fragment = legacyFile(".fsh").toPath();
            Files.createDirectories(fragment.getParent());
            Files.writeString(fragment, conversion.source());
            Files.writeString(legacyFile(".json").toPath(), json);
            written = true;
        } catch (Exception e) {
            written = false;
        }
        ctx.require("the converted shader was written", written);
        ctx.put("reload", ctx.mc().reloadResourcePacks());
    }

    /**
     * The converted hdr_particle as a custom shader. Its {@code Sampler0} is no texture of a custom shader's own (1.21
     * left whatever the last draw bound there), so it is pinned to white here.
     */
    private static final class WhiteCustomShader extends CustomShaderMaterial {
        WhiteCustomShader() {
            super(LEGACY);
        }

        @Override
        protected Identifier baseTexture() {
            return KilaTextures.WHITE;
        }
    }

    private static void customShaderRows(TestContext ctx) {
        var setting = new MaterialSetting();
        var lit = new WhiteCustomShader();
        var type = lit.getRenderType(setting, VertexFormat.Mode.QUADS);
        var info = type == null ? null : PhotonRenderTypes.drawInfo(type);
        ctx.require("1.21's hdr_particle compiles as a custom shader", !lit.isCompiledError() && info != null);
        ctx.check("1.21's hdr_particle is lit by dynamic lights", PhotonPipelines.isLit(info.programs().main()), true, false);
        var circle = new CustomShaderMaterial(Photon.id("circle"));
        var circleType = circle.getRenderType(setting, VertexFormat.Mode.QUADS);
        var circleInfo = circleType == null ? null : PhotonRenderTypes.drawInfo(circleType);
        ctx.check("the plain circle shader is not", !circle.isCompiledError() && circleInfo != null
                        && !PhotonPipelines.isLit(circleInfo.programs().main()), "compiled, unlit",
                "error=" + circle.isCompiledError() + " lit=" + (circleInfo != null && PhotonPipelines.isLit(circleInfo.programs().main())));
        var group = new ConfiguratorGroup();
        lit.buildConfigurator(group);
        var labels = new ArrayList<String>();
        collectLabels(group, labels);
        var leaked = labels.stream().filter(LitParticles::declares).toList();
        ctx.check("the inspector shows the shader's own uniforms", labels.contains("DiscardThreshold"),
                "DiscardThreshold among the rows", labels);
        ctx.check("but none of the lights'", leaked.isEmpty(), "none", leaked);
        // 26.1 saves what was set, over the json's defaults: set the shader's own value, as the inspector row would
        lit.setUniformValue("DiscardThreshold", 0.02f);
        var saved = lit.serializeAdditionalNBT(Platform.getFrozenRegistry());
        var uniforms = saved instanceof CompoundTag tag
                ? tag.getCompoundOrEmpty("shaderData").getCompoundOrEmpty("uniforms") : new CompoundTag();
        var stored = uniforms.keySet().stream().filter(LitParticles::declares).toList();
        ctx.check("and saving keeps its own uniforms", uniforms.contains("DiscardThreshold"), "DiscardThreshold saved",
                uniforms.keySet());
        ctx.check("without the lights'", stored.isEmpty(), "none", stored);
    }

    private static void collectLabels(Configurator configurator, List<String> labels) {
        labels.add(configurator.label.getText().getString());
        if (configurator instanceof ConfiguratorGroup group) {
            for (var child : group.getConfigurators()) collectLabels(child, labels);
        }
    }

    /** 1.21 read the custom shader's light switch; 26.1 binds the lights at every draw of its lit pipeline. */
    private static void customLit(TestContext ctx, String round, boolean instanced, float expected) {
        var value = CUSTOM_LIT.get();
        ctx.check("the custom shader's lights were switched for its draw (" + round + ")", value != null && value == expected,
                expected, value);
        var path = instanced ? "PARTICLE_INSTANCE" : "";
        ctx.check("it drew " + (instanced ? "instanced" : "on the CPU path") + " (" + round + ")",
                CUSTOM_DRAWS.equals(Set.of(path)), instanced ? "PARTICLE_INSTANCE" : "no instancing", CUSTOM_DRAWS);
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
        CUSTOM_DRAWS.clear();
        CUSTOM_LIT.set(null);
        PhotonWorldRenderState.drawObserver = (pipeline, records) -> {
            if (DrawnVariants.isCustomShader(pipeline) && PhotonPipelines.isLit(pipeline)) {
                CUSTOM_DRAWS.add(DrawnVariants.of(pipeline));
                CUSTOM_LIT.set(DynamicLightRenderer.hasLights() ? 1f : 0f);
            }
        };
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
        PhotonWorldRenderState.drawObserver = null;
        var executor = ctx.<BlockEffectExecutor>get("executor");
        if (executor != null && executor.getRuntime() != null) executor.getRuntime().destroy(true);
        ctx.state().remove("executor");
    }

    /** A red light on the middle quad's axis, {@code along} blocks behind it (negative: between it and the camera). */
    private static void addLight(TestContext ctx, double along) {
        removeLight(ctx);
        // a round sees its own draws only
        CUSTOM_DRAWS.clear();
        CUSTOM_LIT.set(null);
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
