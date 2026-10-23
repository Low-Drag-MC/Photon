package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.ui.menu.MenuTab;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.gui.util.TreeNode;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.fxpack.FXPackExporter;
import com.lowdragmc.photon.client.fx.fxpack.FXPacks;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.UIResourceMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXFileActions;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.FXProjectConverter;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The life of an {@code .fxpack} the editor writes: a new pack waits for a reload, edits of a mounted one are
 * served at once, a converted effect survives its removal from the pack, and the File menu offers the pack tools
 * with no project open.
 */
@LDLRegisterClient(name = "fxpack_lifecycle", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxPackLifecycleScenario implements UIScenario {
    private static final String PACK = "uitest_lifecycle";

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "fxpack").requiresWorld(true).scenarioTimeoutMs(300_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("clean up after an earlier run", ctx -> {
            deleteFixtures();
            // a pack mounted by an earlier run would answer for the new one, so it has to be unmounted first
            if (FXPacks.isMounted(pack())) {
                ctx.put("cleanup", ctx.mc().reloadResourcePacks());
            }
        })
                .timeoutMs(180_000)
                .waitUntil("nothing of an earlier run is mounted", ctx -> {
                    var cleanup = ctx.<CompletableFuture<Void>>get("cleanup");
                    return (cleanup == null || cleanup.isDone()) && ctx.mc().getOverlay() == null;
                })
                .step("write the library materials the effects reference", ctx -> {
                    writeMaterial(ctx, "a", Photon.id("textures/particle/smoke.png"));
                    writeMaterial(ctx, "b", Photon.id("textures/particle/circle.png"));
                })

                .step("export into a new pack", ctx -> {
                    export("a", "a_v1");
                    var resolution = FXPacks.resolve(pack(), id("a"));
                    ctx.check("a pack the game has not mounted is not loadable yet",
                            resolution.status() == FXPacks.Resolution.Status.NOT_MOUNTED, "NOT_MOUNTED", resolution);
                    ctx.check("so playing it finds nothing", FXHelper.getFX(id("a")) == null, null, FXHelper.getFX(id("a")));
                })
                .step("reload the resources", ctx -> ctx.put("reload", ctx.mc().reloadResourcePacks()))
                .timeoutMs(180_000)
                .waitUntil("the reload finishes", ctx -> ctx.<CompletableFuture<Void>>get("reload").isDone()
                        && ctx.mc().getOverlay() == null)
                .frames(4)
                .step("the reload mounted the pack", ctx -> {
                    checkLive(ctx, "a");
                    checkEmitters(ctx, "a", "a_v1");
                    if (isWindows()) {
                        ctx.check("the mounted pack is held open, from the reload that mounted it on",
                                FXPacks.isFileLocked(pack()), true, FXPacks.isFileLocked(pack()));
                    }
                })
                // what exporting into a pack used to be, kept here as the evidence for the rewrite
                .step("an in-place zip edit cannot commit into the mounted pack", ctx -> {
                    if (!isWindows()) {
                        ctx.log("not on Windows: an open file does not stop its replacement, it only goes stale");
                        return;
                    }
                    var before = leftovers();
                    IOException failure = null;
                    try (var zip = FileSystems.newFileSystem(pack().toPath())) {
                        Files.writeString(zip.getPath("probe.txt"), "probe");
                    } catch (IOException e) {
                        failure = e;
                    }
                    ctx.check("the commit fails", failure != null, "an IOException", failure);
                    var junk = new ArrayList<>(leftovers());
                    junk.removeAll(before);
                    ctx.log("the failed commit left " + junk.stream().map(File::getName).toList());
                    junk.forEach(File::delete);
                })

                .step("play it the way a command does", ctx -> ctx.put("first", play(ctx, "a")))
                .ticks(5)
                .step("export the effect again into the mounted pack", ctx -> {
                    export("a", "a_v2");
                    checkLive(ctx, "a");
                    checkEmitters(ctx, "a", "a_v2");
                    ctx.check("no copy or temp file is left in the pack folder", leftovers().isEmpty(), List.of(), leftovers());
                })
                .step("playing it again at the same block replaces the outdated one", ctx -> {
                    var first = ctx.<BlockEffectExecutor>get("first");
                    var second = play(ctx, "a");
                    ctx.put("second", second);
                    ctx.check("the outdated effect stopped", first.getRuntime() != null && !first.getRuntime().isValid(),
                            "stopped", first.getRuntime());
                    var runtime = second.getRuntime();
                    ctx.check("the current one plays", runtime != null && runtime.findObject("a_v2") != null,
                            "a_v2", runtime == null ? null : runtime.objects.values().stream().map(IFXObject::getName).toList());
                    var playing = BlockEffectExecutor.CACHE.getOrDefault(second.pos, List.of());
                    ctx.check("it is the only effect there", playing.equals(List.of(second)), 1, playing.size());
                })
                .step("add a second effect to the mounted pack", ctx -> {
                    export("b", "b_v1");
                    checkLive(ctx, "b");
                    checkEmitters(ctx, "b", "b_v1");
                    ctx.check("the listing of loadable effects has it", FXHelper.listAllFX().contains(id("b")),
                            id("b"), FXHelper.listAllFX());
                    ctx.check("the pack carries both materials", packedMaterials().size() == 2, 2, packedMaterials());
                })
                .step("a pack of the same name in a subfolder is told apart from the mounted one", ctx -> {
                    var nested = new File(FXPacks.getFxPacksDir(), "uitest_sub/" + PACK + FXPacks.SUFFIX);
                    try {
                        export(nested, "a", "a_nested");
                        ctx.check("a reload would not mount it", !FXPacks.isLoadable(nested), false, FXPacks.isLoadable(nested));
                        ctx.check("it is not mounted", !FXPacks.isMounted(nested), false, FXPacks.isMounted(nested));
                        var resolution = FXPacks.resolve(nested, id("a"));
                        ctx.check("the fx it holds is not reported as played from it",
                                resolution.status() != FXPacks.Resolution.Status.LIVE, "not LIVE", resolution);
                        checkEmitters(ctx, "a", "a_v2");
                    } finally {
                        deleteQuietly(nested);
                        deleteQuietly(nested.getParentFile());
                    }
                })

                .step("convert the second effect into a project", ctx -> {
                    FXProjectConverter.Result result;
                    try {
                        result = FXProjectConverter.convertPacked(pack(), id("b"), project());
                    } catch (Exception e) {
                        throw new IllegalStateException("the conversion failed", e);
                    }
                    ctx.check("every reference resolved", result.warnings().isEmpty(), List.of(), result.warnings());
                    ctx.check("the project was saved", project().isFile(), project(), project().exists());
                    checkLocalMaterial(ctx, result.project());
                    ctx.put("converted", result.project());
                })
                .step("remove the second effect from the pack", ctx -> {
                    try {
                        FXPacks.removeFx(pack(), id("b"));
                    } catch (IOException e) {
                        throw new IllegalStateException("removing it failed", e);
                    }
                    ctx.check("playing it finds nothing", FXHelper.getFX(id("b")) == null, null, FXHelper.getFX(id("b")));
                    ctx.check("the listing of loadable effects dropped it", !FXHelper.listAllFX().contains(id("b")),
                            "no " + id("b"), FXHelper.listAllFX());
                    ctx.check("the pack lists only the first", listFx().equals(List.of(id("a"))), List.of(id("a")), listFx());
                    ctx.check("the material only it used was swept out", packedMaterials().size() == 1, 1, packedMaterials());
                    checkEmitters(ctx, "a", "a_v2");
                    ctx.check("no copy or temp file is left in the pack folder", leftovers().isEmpty(), List.of(), leftovers());
                })
                .step("the converted project does not need the pack", ctx -> {
                    try {
                        checkLocalMaterial(ctx, (FXProject) FXProject.TYPE.loadProjectFromFile(project()));
                    } catch (Exception e) {
                        throw new IllegalStateException("the project does not load", e);
                    }
                })

                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("with no project the file menu still converts and manages packs", ctx -> {
                    var editor = editor(ctx);
                    if (editor.getCurrentProject() != null) {
                        editor.closeCurrentProject(false, null);
                    }
                    var menu = fileMenu(editor);
                    ctx.check("converting is offered", isEnabled(find(menu, "photon.convert_fx")), true, describe(menu));
                    ctx.check("managing packs is offered", isEnabled(find(menu, "photon.manage_fxpack")), true, describe(menu));
                    var export = find(menu, "ldlib.gui.editor.menu.export");
                    ctx.check("exporting shows, disabled", export != null
                            && !isEnabled(find(export, "photon.export_fx")) && find(export, "photon.export_fx") != null
                            && !isEnabled(find(export, "photon.export_fxpack")) && find(export, "photon.export_fxpack") != null,
                            "two disabled entries", describe(menu));
                })
                .step("the converted project opens with nothing to save", ctx -> {
                    var editor = editor(ctx);
                    editor.loadProject(ctx.<FXProject>get("converted"), project());
                    ctx.check("it is the open project", project().equals(editor.getCurrentProjectFile()),
                            project(), editor.getCurrentProjectFile());
                    ctx.check("it reads as unmodified", !editor.isCurrentProjectDirty(), false, editor.isCurrentProjectDirty());
                    editor.closeCurrentProject(false, null);
                })
                .step("an open project enables exporting", ctx -> {
                    var editor = editor(ctx);
                    editor.loadProject(FXProject.TYPE.newEmptyProject(), null);
                    var export = find(fileMenu(editor), "ldlib.gui.editor.menu.export");
                    ctx.check("both exports are enabled", export != null && isEnabled(find(export, "photon.export_fx"))
                            && isEnabled(find(export, "photon.export_fxpack")), "enabled", export == null ? null : describe(export));
                    editor.closeCurrentProject(false, null);
                })
                .step("open the pack manager on the pack", ctx -> showFxPackContents(editor(ctx), pack()))
                .frames(4)
                .step("it lists the effect left in the pack", ctx -> {
                    var rows = ctx.query().type(Label.class).withText(id("a").toString()).list();
                    ctx.check("the effect has a row", rows.size() == 1, 1, rows.size());
                    var notMounted = Component.translatable("photon.manage_fxpack.not_mounted").getString();
                    ctx.check("a mounted pack offers no reload", ctx.query().type(Label.class).withText(notMounted).list().isEmpty(),
                            "no reload notice", notMounted);
                    ctx.screenshot("pack_manager");
                })
                .frames(2)
                .step("close the pack manager", ctx ->
                        ctx.query().type(Dialog.class).list().forEach(dialog -> dialog.as(Dialog.class).close()))
                .step("open the file menu", ctx -> {
                    var editor = editor(ctx);
                    editor.openMenu(4, 15, fileMenuBuilder(editor));
                })
                .frames(4)
                .step("show the file menu", ctx -> ctx.screenshot("file_menu"));

        s.teardown("stop the effects and delete the fixtures", ctx -> {
            for (var key : List.of("first", "second")) {
                var executor = ctx.<BlockEffectExecutor>get(key);
                if (executor != null && executor.getRuntime() != null) {
                    executor.getRuntime().destroy(true);
                }
            }
            deleteFixtures();
        });
    }

    /** What a {@code /photon fx <id> block} command does on the client, a few blocks ahead of the player. */
    private static BlockEffectExecutor play(TestContext ctx, String name) {
        var fx = FXHelper.getFX(id(name));
        ctx.require(name + " loads", fx != null);
        var player = ctx.requirePlayer();
        var executor = new BlockEffectExecutor(fx, player.level(), player.blockPosition().relative(player.getDirection(), 5));
        executor.start();
        return executor;
    }

    private static File pack() {
        return new File(FXPacks.getFxPacksDir(), PACK + FXPacks.SUFFIX);
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(PACK, name);
    }

    private static File material(String name) {
        return new File(LDLib2.getAssetsDir(), "ldlib2/resources/global/uitest_fxpack_" + name
                + MaterialResource.INSTANCE.getFileExtension());
    }

    private static File project() {
        return new File(LDLib2.getAssetsDir(), "uitest_fxpack_b" + FXProject.TYPE.getSuffix());
    }

    private static File projectResources() {
        return new File(LDLib2.getAssetsDir(), "uitest_fxpack_b_resources");
    }

    private static boolean isWindows() {
        return Util.getPlatform() == Util.OS.WINDOWS;
    }

    private static void writeMaterial(TestContext ctx, String name, ResourceLocation texture) {
        var data = MaterialResource.INSTANCE.serializeResource(new TextureMaterial(texture), Platform.getFrozenRegistry());
        var nbt = new CompoundTag();
        nbt.put("data", data);
        nbt.putString("type", MaterialResource.INSTANCE.getName());
        try {
            Files.createDirectories(material(name).getParentFile().toPath());
            NbtIo.write(nbt, material(name).toPath());
        } catch (IOException e) {
            ctx.require("the material fixture is written: " + e, false);
        }
    }

    private static void export(String name, String emitterName) {
        export(pack(), name, emitterName);
    }

    /** One particle emitter, named so a load tells which export it came from, drawing with a library material. */
    private static void export(File packFile, String name, String emitterName) {
        var emitter = new ParticleEmitter();
        emitter.setName(emitterName);
        // still playing when it is started again, which is when a start can be skipped as a duplicate
        emitter.config.setLooping(true);
        var materials = emitter.config.renderer.getMaterials();
        materials.clear();
        materials.add(new MaterialSetting(new UIResourceMaterial(new FilePath(material(name)))));
        var fx = new FX();
        fx.getFxData().objects().add(emitter);
        try {
            FXPackExporter.exportInto(packFile, PACK, name, fx, Platform.getFrozenRegistry());
        } catch (IOException e) {
            throw new IllegalStateException("exporting " + name + " failed", e);
        }
    }

    private static void checkLive(TestContext ctx, String name) {
        var resolution = FXPacks.resolve(pack(), id(name));
        ctx.check(name + " is loaded from the pack", resolution.status() == FXPacks.Resolution.Status.LIVE,
                "LIVE", resolution);
    }

    private static void checkEmitters(TestContext ctx, String name, String expected) {
        var fx = FXHelper.getFX(id(name));
        var names = fx == null ? null : fx.getFxData().objects().stream().map(IFXObject::getName).toList();
        ctx.check("playing " + name + " loads its latest export", List.of(expected).equals(names), List.of(expected), names);
    }

    private static void checkLocalMaterial(TestContext ctx, FXProject project) {
        var objects = project.getFx().getFxData().objects();
        ctx.require("the project holds the emitter", objects.size() == 1 && objects.getFirst() instanceof ParticleEmitter);
        var setting = ((ParticleEmitter) objects.getFirst()).config.renderer.getMaterials().getFirst();
        ctx.require("its material is a library reference", setting.getMaterial() instanceof UIResourceMaterial);
        var reference = (UIResourceMaterial) setting.getMaterial();
        var file = reference.getResourcePath() instanceof FilePath path ? path.getFile() : null;
        ctx.check("the reference points at a copy next to the project", file != null && file.isFile()
                        && file.getAbsoluteFile().getParentFile().toPath().normalize()
                        .equals(projectResources().getAbsoluteFile().toPath().normalize()),
                projectResources(), reference.getResourcePath().getPathWithType());
        ctx.check("which resolves to the material", reference.getRawMaterial() instanceof TextureMaterial,
                "a TextureMaterial", reference.getRawMaterial());
    }

    private static List<ResourceLocation> listFx() {
        try {
            return FXPacks.listFx(pack());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> packedMaterials() {
        try (var zip = FileSystems.newFileSystem(pack().toPath());
             var files = Files.list(zip.getPath("assets", FXPackExporter.LIBRARY_NAMESPACE, "material"))) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Any file in the pack folder that is not a pack: a rewrite's copy, or a zip file system's temp file. */
    private static List<File> leftovers() {
        var files = FXPacks.getFxPacksDir().listFiles(file -> file.isFile() && !file.getName().endsWith(FXPacks.SUFFIX));
        return files == null ? List.of() : Arrays.stream(files).sorted(Comparator.comparing(File::getName)).toList();
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static TreeNode<Tuple<IGuiTexture, Component>, Runnable> fileMenu(FXEditor editor) {
        return fileMenuBuilder(editor).build();
    }

    /** The menu the File tab opens, built the way a click on it builds it. */
    private static TreeBuilder.Menu fileMenuBuilder(FXEditor editor) {
        try {
            var createMenu = MenuTab.class.getDeclaredMethod("createMenu");
            createMenu.setAccessible(true);
            return (TreeBuilder.Menu) createMenu.invoke(editor.fileMenu);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void showFxPackContents(FXEditor editor, File packFile) {
        try {
            var show = FXFileActions.class.getDeclaredMethod("showFxPackContents", Editor.class, File.class);
            show.setAccessible(true);
            show.invoke(null, editor, packFile);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    private static TreeNode<Tuple<IGuiTexture, Component>, Runnable> find(TreeNode<Tuple<IGuiTexture, Component>, Runnable> menu,
                                                                           String key) {
        for (var child : menu.getChildren()) {
            if (key.equals(keyOf(child))) return child;
        }
        return null;
    }

    private static boolean isEnabled(@Nullable TreeNode<Tuple<IGuiTexture, Component>, Runnable> leaf) {
        return leaf != null && leaf.isLeaf() && leaf.getContent() != null && leaf.getContent() != TreeBuilder.Menu.DISABLED;
    }

    private static String keyOf(TreeNode<Tuple<IGuiTexture, Component>, Runnable> node) {
        var name = node.getKey().getB();
        return name.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : name.getString();
    }

    private static List<String> describe(TreeNode<Tuple<IGuiTexture, Component>, Runnable> menu) {
        return menu.getChildren().stream().map(child -> keyOf(child) + (child.isLeaf()
                ? (isEnabled(child) ? "" : " (disabled)") : " " + describe(child))).toList();
    }

    private static void deleteFixtures() {
        try {
            if (pack().exists()) {
                // the mount holds the file open; the same release a rewrite does lets it go
                var release = FXPacks.class.getDeclaredMethod("release", File.class);
                release.setAccessible(true);
                release.invoke(null, pack());
                Files.delete(pack().toPath());
            }
            Files.deleteIfExists(material("a").toPath());
            Files.deleteIfExists(material("b").toPath());
            Files.deleteIfExists(project().toPath());
            var resources = projectResources().listFiles();
            if (resources != null) {
                for (var file : resources) {
                    Files.delete(file.toPath());
                }
            }
            Files.deleteIfExists(projectResources().toPath());
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("could not delete the fixtures", e);
        }
    }
}
