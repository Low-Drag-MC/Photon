package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.resource.FileResourceProvider;
import com.lowdragmc.lowdraglib2.editor.ui.browser.FileOps;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceContainer;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;

/**
 * Diagnostic: does sitting in the editor with many materials loaded grow memory without bound?
 *
 * <p>Reported from the wild — "if you stay in editor too long with many materials loaded your ram usage
 * starts to exponentially bloat and freeze your game", said to have regressed in 2.2. This scenario is
 * the measurement that was missing: it loads a folder of materials, holds the editor open, and samples
 * memory across a few thousand frames.
 *
 * <p>It reports rather than asserts a tight bound: what a leak looks like is a slope that does not flatten
 * after a full GC, and the numbers below are printed per window so the shape is visible in the report even
 * when the run passes. The only hard check is a ceiling loose enough that a healthy editor never trips it.
 */
@LDLRegisterClient(name = "editor_memory", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EditorMemoryScenario implements UIScenario {

    /** Enough material tiles that a per-material-per-frame leak shows up in seconds rather than minutes. */
    private static final int MATERIAL_COUNT = 120;
    private static final int WINDOWS = 6;
    private static final int FRAMES_PER_WINDOW = 200;
    /** Retained growth over the whole run that would mean something is genuinely accumulating. */
    private static final long GROWTH_LIMIT_MB = 256;

    private static File resourceDir() {
        return new File(LDLib2.getAssetsDir(), "ldlib2/resources/global");
    }

    private static File fixtureFile(int index) {
        return new File(resourceDir(), "uitest_mem_%04d%s".formatted(index, MaterialResource.INSTANCE.getFileExtension()));
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "editor", "memory").requiresWorld(true).scenarioTimeoutMs(300_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("write %d material resources".formatted(MATERIAL_COUNT), ctx -> {
            deleteFixtures();
            //noinspection ResultOfMethodCallIgnored
            resourceDir().mkdirs();
            try {
                var graphs = shaderGraphFiles();
                ctx.log("shader graphs available for ShaderGraphMaterial: " + graphs.length);
                for (int i = 0; i < MATERIAL_COUNT; i++) {
                    // Three kinds, so one run covers all three shader back-ends a user accumulates:
                    // a plain texture, a hand-written shader, and a KilaGraph shader graph. The graph
                    // one is the reason this scenario exists in its current form — it is the only
                    // material whose compile, uniforms and GL program come out of KilaGraph.
                    var material = switch (i % 3) {
                        case 0 -> new TextureMaterial(Identifier.parse("photon:textures/particle/smoke.png"));
                        case 1 -> new CustomShaderMaterial(Identifier.parse("photon:circle"));
                        default -> graphs.length == 0
                                ? new CustomShaderMaterial(Identifier.parse("photon:circle"))
                                : new ShaderGraphMaterial(new FilePath(graphs[i % graphs.length]));
                    };
                    var data = MaterialResource.INSTANCE.serializeResource(material, Platform.getFrozenRegistry());
                    var nbt = new CompoundTag();
                    nbt.put("data", data);
                    nbt.putString("type", MaterialResource.INSTANCE.getName());
                    NbtIo.write(nbt, fixtureFile(i).toPath());
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not write the material fixture", e);
            }
        })

        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())

        // A project is what registers the resource panels — without one the editor only shows the asset
        // browser, and the material tiles that this scenario exists to render are never built.
        .step("open an empty project", ctx -> {
            var editor = ctx.query().type(FXEditor.class).one().as(FXEditor.class);
            editor.loadProject(FXProject.TYPE.newEmptyProject(), null);
        })
        .waitUntil("the material panel exists", ctx -> {
            var editor = ctx.query().type(FXEditor.class).one().as(FXEditor.class);
            return editor.resourceView.getResourceInstance(MaterialResource.INSTANCE) != null;
        })
        // Selecting the tab is not enough: the panel opens on whichever provider it opened on last
        // (usually "built-in", eight tiles), and the fixtures live in the global one. Without this the
        // scenario measures an editor that is not drawing the materials it just loaded.
        .step("show the material resource panel", ctx -> {
            var editor = ctx.query().type(FXEditor.class).one().as(FXEditor.class);
            editor.resourceView.selectResourceInstance(MaterialResource.INSTANCE);
            var container = ctx.query().type(ResourceContainer.class).list().stream()
                    .map(ref -> (ResourceContainer<?>) ref.as(ResourceContainer.class))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("the material panel is not on screen"));
            selectGlobalProvider(container);
        })
        // the provider polls the folder on its own tick, so wait for the fixtures to actually be in
        .waitUntil("every material is loaded", ctx -> loadedMaterials(ctx) >= MATERIAL_COUNT)
        // Validate the fixture before trusting anything it measures: a graph material whose graph did
        // not resolve compiles nothing and owns no GL state, so a flat result would say nothing about
        // the path this scenario was extended to cover.
        .step("report what is on screen", ctx -> {
            ctx.log("materials loaded: " + loadedMaterials(ctx));
            ctx.log("UI elements: " + ctx.requireUI().ui.rootElement.selfAndAllChildren().count());
            var graphMaterials = 0;
            var compiled = 0;
            for (var entry : MaterialResource.INSTANCE.getResourceInstance().listAllResources()) {
                if (entry.getValue() instanceof ShaderGraphMaterial graphMaterial) {
                    graphMaterials++;
                    if (!graphMaterial.isCompiledError()) compiled++;
                }
            }
            ctx.log("shader-graph materials: %d, of which compiled: %d".formatted(graphMaterials, compiled));
            ctx.check("the graph materials actually compiled", compiled > 0, "> 0", compiled);
        })
        .screenshot("01_materials")

        // settle first: class loading, shader compiles and lazy caches all allocate once, and counting
        // that as growth would make any run look like a leak
        .frames(120)
        .step("baseline", ctx -> ctx.put("baseline", sample(ctx, "baseline")))

        .repeat(WINDOWS, w -> w
                .frames(FRAMES_PER_WINDOW)
                .step("sample", ctx -> {
                    var used = sample(ctx, "window");
                    var baseline = ctx.<Sample>get("baseline");
                    ctx.log("  delta vs baseline: heap %+d MB, direct %+d MB, elements %+d".formatted(
                            mb(used.heap() - baseline.heap()),
                            mb(used.direct() - baseline.direct()),
                            used.elements() - baseline.elements()));
                    ctx.put("last", used);
                }))

        .step("growth over the run", ctx -> {
            var baseline = ctx.<Sample>get("baseline");
            var last = ctx.<Sample>get("last");
            var heapGrowth = mb(last.heap() - baseline.heap());
            var directGrowth = mb(last.direct() - baseline.direct());
            var elementGrowth = last.elements() - baseline.elements();
            ctx.attach("heapGrowthMB", String.valueOf(heapGrowth));
            ctx.attach("directGrowthMB", String.valueOf(directGrowth));
            ctx.attach("elementGrowth", String.valueOf(elementGrowth));
            ctx.log("after %d frames: heap %+d MB, direct %+d MB, UI elements %+d".formatted(
                    WINDOWS * FRAMES_PER_WINDOW, heapGrowth, directGrowth, elementGrowth));
            ctx.check("retained heap growth stays under %d MB".formatted(GROWTH_LIMIT_MB),
                    heapGrowth < GROWTH_LIMIT_MB, "< " + GROWTH_LIMIT_MB + " MB", heapGrowth + " MB");
            ctx.check("the UI element count does not grow", elementGrowth <= 0, "<= 0", elementGrowth);
        })

        .teardown("close the editor", ctx -> ctx.mc().setScreen(null))
        .teardown("delete the fixtures", ctx -> deleteFixtures());
    }

    /** Point the panel at the provider the fixtures were written into, i.e. the folder-backed one. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void selectGlobalProvider(ResourceContainer<?> container) {
        var provider = MaterialResource.INSTANCE.getResourceInstance().listAllResourceEntries().stream()
                .map(entry -> entry.provider())
                .filter(FileResourceProvider.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no file-backed material provider is loaded"));
        ((ResourceContainer) container).selectProvider(provider);
    }

    private record Sample(long heap, long direct, long elements) {}

    /**
     * Retained memory, measured after a full GC so only what is still reachable counts. Direct buffers
     * are sampled too because a texture or vertex buffer that is never freed does not show up on the heap.
     */
    private static Sample sample(TestContext ctx, String label) {
        System.gc();
        System.runFinalization();
        System.gc();
        var runtime = Runtime.getRuntime();
        var heap = runtime.totalMemory() - runtime.freeMemory();
        long direct = 0;
        for (var pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            direct += pool.getMemoryUsed();
        }
        var elements = ctx.requireUI().ui.rootElement.selfAndAllChildren().count();
        ctx.log("%s: heap %d MB, direct %d MB, UI elements %d".formatted(label, mb(heap), mb(direct), elements));
        return new Sample(heap, direct, elements);
    }

    private static long loadedMaterials(TestContext ctx) {
        return MaterialResource.INSTANCE.getResourceInstance().listAllResources().size();
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    /** The shader graphs the dev environment happens to have, used to back the graph materials. */
    private static File[] shaderGraphFiles() {
        var suffix = ".shader_graph.nbt";
        var found = new java.util.ArrayList<File>();
        var roots = new File[]{ resourceDir(), new File(LDLib2.getAssetsDir(), "ldlib2/resources/shadergraph") };
        for (var root : roots) {
            var files = root.listFiles((dir, name) -> name.endsWith(suffix) && !name.startsWith("uitest_"));
            if (files != null) java.util.Collections.addAll(found, files);
        }
        return found.toArray(new File[0]);
    }

    private static void deleteFixtures() {
        var dir = resourceDir();
        var files = dir.listFiles((file, name) -> name.startsWith("uitest_mem_"));
        if (files != null) {
            for (var file : files) {
                FileOps.deleteRecursively(file);
            }
        }
    }
}
