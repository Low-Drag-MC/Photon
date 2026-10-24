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
import com.lowdragmc.photon.client.fx.instance.InstanceBaker;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceRecord;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.instance.NbtPatch;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
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
import net.minecraft.util.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;

/**
 * The life of an {@code .fxpack} the editor writes: a new pack waits for a reload, edits of a mounted one are
 * served at once, a converted effect survives its removal from the pack, an effect placing others carries them,
 * and the File menu offers the pack tools with no project open.
 */
@LDLRegisterClient(name = "fxpack_lifecycle", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxPackLifecycleScenario implements UIScenario {
    private static final String PACK = "uitest_lifecycle";
    private static final UUID I = uuid("I");
    private static final UUID J = uuid("J");
    private static final UUID K = uuid("K");
    private static final UUID B_EMIT = uuid("base_emit");
    private static final UUID L_EMIT = uuid("leaf_emit");

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("photon", "fxpack").requiresWorld(true).scenarioTimeoutMs(480_000);
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

                .step("export an effect whose FX instances place others", ctx -> {
                    writeMaterial(ctx, "c", Photon.id("textures/particle/circle.png"));
                    writeMaterial(ctx, "d", Photon.id("textures/particle/ring.png"));
                    writeSources(ctx);
                    ctx.put("placing", exportPlacing(ctx));
                })
                .step("the pack carries what they place, each under its own id", ctx -> {
                    var result = ctx.<FXPackExporter.Result>get("placing");
                    ctx.check("only the source that is nowhere is warned about", result.warnings().size() == 1
                            && result.warnings().getFirst().contains(source("nowhere").toString()), source("nowhere"), result.warnings());
                    ctx.check("main, base, leaf and the materials c and d were written", result.fileCount() == 5, 5, result.fileCount());
                    var main = readEntry(id("main"));
                    ctx.require("main is in the pack", main != null);
                    ctx.check("main saves its instances, not what they place", objectIds(main).equals(List.of(I, J)),
                            List.of(I, J), objectIds(main));
                    var modified = recordOf(main, I).modified();
                    var value = modified.size() == 1 ? modified.getFirst().change().value() : null;
                    ctx.check("base_emit's other material is one override, the whole reference, pointing into the pack",
                            value instanceof CompoundTag reference && "ui_resource_material".equals(reference.getStringOr("type", ""))
                                    && isPacked(reference.getCompoundOrEmpty("data").getStringOr("resourcePath", "")),
                            "a packed ui_resource_material", modified);
                    ctx.check("the record of the instance whose source is nowhere is kept", recordOf(main, J).modified().size() == 1,
                            1, recordOf(main, J));
                    var base = readEntry(source("base"));
                    var leaf = readEntry(source("leaf"));
                    ctx.check("base is packed under its own id", base != null, FXHelper.fileLocation(source("base")), null);
                    ctx.check("and leaf, which base places", leaf != null, FXHelper.fileLocation(source("leaf")), null);
                    ctx.check("nothing is packed for the source that is nowhere", readEntry(source("nowhere")) == null, null, "an entry");
                    if (base == null || leaf == null) return;
                    ctx.check("base still places leaf by instance", InstanceBaker.directSources(base.getCompoundOrEmpty("fxData"))
                            .equals(java.util.Set.of(source("leaf"))), source("leaf"), InstanceBaker.directSources(base.getCompoundOrEmpty("fxData")));
                    for (var entry : Map.of("base", base, "leaf", leaf).entrySet()) {
                        var paths = strings(entry.getValue(), "resourcePath");
                        ctx.check(entry.getKey() + "'s material points into the pack", !paths.isEmpty()
                                && paths.stream().allMatch(FxPackLifecycleScenario::isPacked), "packed", paths);
                    }
                    ctx.check("the pack carries c and d next to a's material", packedMaterials().size() == 3, 3, packedMaterials());
                })
                .step("take the sources and the materials away, as on a machine that only has the pack", ctx -> {
                    deleteSources();
                    deleteQuietly(material("c"));
                    deleteQuietly(material("d"));
                    // the pack had no effect under base's namespace when it was mounted
                    ctx.put("remount", ctx.mc().reloadResourcePacks());
                })
                .timeoutMs(180_000)
                .waitUntil("the reload finishes", ctx -> ctx.<CompletableFuture<Void>>get("remount").isDone()
                        && ctx.mc().getOverlay() == null)
                .frames(4)
                .step("the effect places them from the pack alone", ctx -> {
                    for (var name : List.of("base", "leaf")) {
                        var resolution = FXPacks.resolve(pack(), source(name));
                        ctx.check(name + " loads from the pack", resolution.status() == FXPacks.Resolution.Status.LIVE, "LIVE", resolution);
                    }
                    var fx = FXHelper.getFX(id("main"));
                    ctx.require("main loads", fx != null);
                    var objects = new java.util.HashMap<UUID, IFXObject>();
                    fx.getFxData().objects().forEach(object -> objects.put(object.id(), object));
                    var placed = objects.get(I) instanceof FXInstanceObject instance ? instance.getPlacement() : null;
                    ctx.check("its instance of base is placed", placed != null && placed.status() == InstanceBaker.Status.PLACED,
                            InstanceBaker.Status.PLACED, placed == null ? null : placed.status());
                    checkTexture(ctx, "base_emit draws with the overriding d", objects.get(InstanceIds.xor(I, B_EMIT)),
                            Photon.id("textures/particle/ring.png"));
                    checkTexture(ctx, "leaf_emit, placed inside base, draws with c", objects.get(InstanceIds.xor(InstanceIds.xor(I, K), L_EMIT)),
                            Photon.id("textures/particle/circle.png"));
                    var missing = objects.get(J) instanceof FXInstanceObject instance ? instance.getPlacement() : null;
                    ctx.check("the instance whose source is nowhere is missing", missing != null && missing.status() == InstanceBaker.Status.MISSING,
                            InstanceBaker.Status.MISSING, missing == null ? null : missing.status());
                })
                .step("export an effect placing a cycle, a broken source, an old one and itself", ctx -> {
                    writeEdgeSources(ctx);
                    exportEdges(false);
                    ctx.put("edges", exportEdges(true));
                })
                .step("each is packed as far as it can be", ctx -> {
                    var result = ctx.<FXPackExporter.Result>get("edges");
                    ctx.check("both sides of the cycle are packed, once", readEntry(source("cyc_a")) != null && readEntry(source("cyc_b")) != null,
                            "cyc_a and cyc_b", listFx());
                    ctx.check("the broken source is warned about", result.warnings().stream().anyMatch(w -> w.contains(source("broken").toString())),
                            source("broken"), result.warnings());
                    ctx.check("and not packed", readEntry(source("broken")) == null, null, "an entry");
                    var old = readEntry(source("old"));
                    ctx.check("the old source is packed as it was, without a version", old != null && !old.contains("version"),
                            "no version", old == null ? null : old.keySet());
                    var edges = readEntry(id("edges"));
                    ctx.check("the effect is the one just exported, not an older one of itself it places",
                            edges != null && objectIds(edges).contains(uuid("E4")), uuid("E4"), edges == null ? null : objectIds(edges));
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

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(PACK, name);
    }

    private static File material(String name) {
        return new File(LDLib2.getAssetsDir(), "ldlib2/resources/global/uitest_fxpack_" + name
                + MaterialResource.INSTANCE.getFileExtension());
    }

    private static Identifier source(String name) {
        return Photon.id(PACK + "/" + name);
    }

    private static File sources() {
        return new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + PACK);
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("fxpack_lifecycle/" + name).getBytes());
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

    private static void writeMaterial(TestContext ctx, String name, Identifier texture) {
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
        var fx = new FX();
        fx.getFxData().objects().add(emitter(emitterName, material(name)));
        try {
            FXPackExporter.exportInto(packFile, PACK, name, fx, Platform.getFrozenRegistry());
        } catch (IOException e) {
            throw new IllegalStateException("exporting " + name + " failed", e);
        }
    }

    private static ParticleEmitter emitter(String name, File material) {
        var emitter = new ParticleEmitter();
        emitter.setName(name);
        // still playing when it is started again, which is when a start can be skipped as a duplicate
        emitter.config.setLooping(true);
        drawWith(emitter, material);
        return emitter;
    }

    private static void drawWith(ParticleEmitter emitter, File material) {
        var materials = emitter.config.renderer.getMaterials();
        materials.clear();
        materials.add(new MaterialSetting(new UIResourceMaterial(new FilePath(material))));
    }

    private static <T extends IFXObject> T withId(T object, UUID id) {
        object.transform()._setInternalID(id);
        return object;
    }

    /** leaf: leaf_emit drawing with c. base: base_emit drawing with c, and K placing leaf. */
    private static void writeSources(TestContext ctx) {
        try {
            writeSource("leaf", List.of(withId(emitter("leaf_emit", material("c")), L_EMIT)));
            var placingLeaf = withId(new FXInstanceObject(source("leaf")), K);
            placingLeaf.setName("K");
            writeSource("base", List.of(withId(emitter("base_emit", material("c")), B_EMIT), placingLeaf));
        } catch (IOException e) {
            ctx.require("the sources are written: " + e, false);
        }
    }

    private static void writeSource(String name, List<IFXObject> objects) throws IOException {
        var fx = new FX();
        fx.getFxData().objects().addAll(objects);
        var tag = fx.serializeNBT(Platform.getFrozenRegistry());
        tag.putInt("version", FXProject.VERSION);
        var file = new File(sources(), name + FX.SUFFIX);
        Files.createDirectories(file.getParentFile().toPath());
        NbtIo.writeCompressed(tag, file.toPath());
    }

    /** main: I placing base, with base_emit drawing with d instead; J placing a source that is nowhere. */
    private static FXPackExporter.Result exportPlacing(TestContext ctx) {
        var provider = Platform.getFrozenRegistry();
        var placing = new FX();
        var base = withId(new FXInstanceObject(source("base")), I);
        base.setName("I");
        var nowhere = withId(new FXInstanceObject(source("nowhere")), J);
        nowhere.setName("J");
        nowhere.setOverrides(new InstanceRecord(List.of(new InstanceRecord.Modification(B_EMIT,
                NbtPatch.Change.set(List.of("config", "duration"), IntTag.valueOf(5)))), List.of(), List.of()).write());
        placing.getFxData().objects().addAll(List.of(base, nowhere));
        // read back the way a project opens, so what the instances place is there to edit
        var authored = new FX();
        authored.deserializeNBT(provider, placing.serializeNBT(provider));
        var emit = authored.getFxData().objects().stream().filter(object -> object.id().equals(InstanceIds.xor(I, B_EMIT)))
                .findFirst().orElse(null);
        ctx.require("base_emit is placed", emit instanceof ParticleEmitter);
        drawWith((ParticleEmitter) emit, material("d"));
        try {
            return FXPackExporter.exportInto(pack(), PACK, "main", authored, provider);
        } catch (IOException e) {
            throw new IllegalStateException("exporting main failed", e);
        }
    }

    /** cyc_a and cyc_b place each other; broken is not an fx at all; old was saved before files had versions. */
    private static void writeEdgeSources(TestContext ctx) {
        try {
            var toB = withId(new FXInstanceObject(source("cyc_b")), uuid("toB"));
            writeSource("cyc_a", List.of(toB));
            var toA = withId(new FXInstanceObject(source("cyc_a")), uuid("toA"));
            writeSource("cyc_b", List.of(toA));
            Files.createDirectories(sources().toPath());
            Files.writeString(new File(sources(), "broken" + FX.SUFFIX).toPath(), "not an fx");
            var old = new FX();
            old.getFxData().objects().add(withId(emitter("old_emit", material("c")), uuid("old_emit")));
            NbtIo.writeCompressed(old.serializeNBT(Platform.getFrozenRegistry()), new File(sources(), "old" + FX.SUFFIX).toPath());
        } catch (IOException e) {
            ctx.require("the sources are written: " + e, false);
        }
    }

    /** edges: an emitter; then, placing too, cyc_a, broken, old and edges itself. */
    private static FXPackExporter.Result exportEdges(boolean placing) {
        var fx = new FX();
        fx.getFxData().objects().add(emitter("edges_emit", material("a")));
        if (placing) {
            fx.getFxData().objects().addAll(List.of(withId(new FXInstanceObject(source("cyc_a")), uuid("E1")),
                    withId(new FXInstanceObject(source("broken")), uuid("E2")), withId(new FXInstanceObject(source("old")), uuid("E3")),
                    withId(new FXInstanceObject(id("edges")), uuid("E4"))));
        }
        try {
            return FXPackExporter.exportInto(pack(), PACK, "edges", fx, Platform.getFrozenRegistry());
        } catch (IOException e) {
            throw new IllegalStateException("exporting edges failed", e);
        }
    }

    @Nullable
    private static CompoundTag readEntry(Identifier fx) {
        try (var zip = FileSystems.newFileSystem(pack().toPath())) {
            var path = FXPacks.entryPath(zip, FXHelper.fileLocation(fx));
            if (!Files.isRegularFile(path)) return null;
            try (var in = Files.newInputStream(path)) {
                return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<UUID> objectIds(CompoundTag fxRoot) {
        return InstanceTags.objects(fxRoot.getCompoundOrEmpty("fxData")).stream().map(InstanceTags::id).toList();
    }

    private static InstanceRecord recordOf(CompoundTag fxRoot, UUID instance) {
        for (var object : InstanceTags.objects(fxRoot.getCompoundOrEmpty("fxData"))) {
            if (instance.equals(InstanceTags.id(object))) {
                return InstanceRecord.read(InstanceTags.data(object).getCompoundOrEmpty(InstanceTags.OVERRIDES));
            }
        }
        return InstanceRecord.EMPTY;
    }

    private static boolean isPacked(String libraryPath) {
        return libraryPath.startsWith("file(assets/" + FXPackExporter.LIBRARY_NAMESPACE + "/material/");
    }

    /** Every string saved under {@code key}, at any depth. */
    private static List<String> strings(Tag tag, String key) {
        var out = new ArrayList<String>();
        if (tag instanceof CompoundTag compound) {
            for (var name : compound.keySet()) {
                if (name.equals(key) && compound.get(name) instanceof StringTag string) {
                    out.add(string.value());
                } else {
                    out.addAll(strings(compound.get(name), key));
                }
            }
        } else if (tag instanceof ListTag list) {
            list.forEach(child -> out.addAll(strings(child, key)));
        }
        return out;
    }

    private static void checkTexture(TestContext ctx, String what, @Nullable IFXObject object, Identifier texture) {
        var material = object instanceof ParticleEmitter emitter ? emitter.config.renderer.getMaterials().getFirst().getMaterial() : null;
        var reference = material instanceof UIResourceMaterial resource ? resource : null;
        var packed = reference != null && reference.getResourcePath() instanceof FilePath path && path.location != null
                && FXPackExporter.LIBRARY_NAMESPACE.equals(path.location.getNamespace());
        ctx.check(what + ", from the pack", packed && reference.getRawMaterial() instanceof TextureMaterial textured
                        && texture.equals(textured.getTexture()), texture,
                reference == null ? material : reference.getResourcePath().getPathWithType() + " -> " + reference.getRawMaterial());
    }

    private static void deleteSources() {
        var files = sources().listFiles();
        if (files != null) {
            for (var file : files) deleteQuietly(file);
        }
        deleteQuietly(sources());
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

    private static List<Identifier> listFx() {
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
                // a read on another thread can reopen it in between, as FXPacks.replace knows
                for (int attempt = 1; ; attempt++) {
                    release.invoke(null, pack());
                    try {
                        Files.delete(pack().toPath());
                        break;
                    } catch (IOException e) {
                        if (attempt == 3) throw e;
                        LockSupport.parkNanos(20_000_000L);
                    }
                }
            }
            for (var name : List.of("a", "b", "c", "d")) {
                Files.deleteIfExists(material(name).toPath());
            }
            deleteSources();
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
