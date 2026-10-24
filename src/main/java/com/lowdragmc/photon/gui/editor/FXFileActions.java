package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXInstances;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.fx.fxpack.FXPackExporter;
import com.lowdragmc.photon.client.fx.fxpack.FXPacks;
import com.lowdragmc.photon.gui.editor.resource.ResourceDialogs;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * The FX editor's own File-menu entries. Exporting needs an open project; converting {@code .fx} definitions
 * into projects and managing {@code .fxpack} files work without one.
 */
@OnlyIn(Dist.CLIENT)
public final class FXFileActions {
    private FXFileActions() {
    }

    static void appendTo(Editor editor, TreeBuilder.Menu menu) {
        var project = editor.getCurrentProject() instanceof FXProject fxProject ? fxProject : null;
        menu.branch("ldlib.gui.editor.menu.export", export -> {
            if (project == null) {
                export.disabledLeaf("photon.export_fx");
                export.disabledLeaf("photon.export_fxpack");
                return;
            }
            // plain .fx: pure definition data (references only) — usable wherever the
            // referenced resources already exist (this machine, a mod jar, an .fxpack)
            export.leaf("photon.export_fx", () -> showExportFxDialog(editor, project));
            // .fxpack: a resource-pack zip carrying the fx plus everything it references —
            // the self-contained distribution format (additive: exports into the same pack
            // accumulate, shared resources are stored once)
            export.leaf("photon.export_fxpack", () -> showExportFxPackDialog(editor, project));
        });
        menu.leaf(Icons.IMPORT, "photon.convert_fx", () -> showConvertFxDialog(editor));
        menu.leaf(Icons.FOLDER, "photon.manage_fxpack", () -> showManageFxPackDialog(editor));
    }

    // ---- export ------------------------------------------------------------------------------------

    private static void showExportFxDialog(Editor editor, FXProject project) {
        var fx = project.getFx();
        Dialog.showFileDialog("ldlib.gui.editor.tips.save_as", new File(LDLib2.getAssetsDir(), "%s/fx/".formatted(Photon.MOD_ID)), false,
                Dialog.suffixFilter(FX.SUFFIX), file -> {
                    if (file == null || file.isDirectory()) return;
                    var target = withSuffix(file, FX.SUFFIX);
                    var id = FXInstanceEditing.idOf(target);
                    if (id != null && refuseCycle(editor, project, id)) return;
                    try {
                        var fileData = fx.serializeNBT(Platform.getFrozenRegistry());
                        // stamp the format version so FXHelper can run the datafixer on future loads
                        fileData.putInt("version", FXProject.VERSION);
                        NbtIo.writeCompressed(fileData, target.toPath());
                        FXHelper.clearCache();
                        if (id != null) project.addKnownAs(id);
                    } catch (Exception e) {
                        Photon.LOGGER.error("Failed to export fx to {}", target, e);
                        showError(editor, "photon.export_fx", e);
                    }
                }).show(editor);
    }

    private static void showExportFxPackDialog(Editor editor, FXProject project) {
        var fx = project.getFx();
        Dialog.showFileDialog("ldlib.gui.editor.tips.save_as", FXPacks.getFxPacksDir(), false,
                Dialog.suffixFilter(FXPacks.SUFFIX), file -> {
                    if (file == null || file.isDirectory()) return;
                    var packFile = withSuffix(file, FXPacks.SUFFIX);
                    // the fx's id becomes <packname>:<name>
                    var projectFile = editor.getCurrentProjectFile();
                    var defaultName = projectFile != null ? baseName(projectFile, FXProject.TYPE.getSuffix()) : "effect";
                    Dialog.stringEditorDialog("photon.export_fxpack.fx_name", defaultName, null,
                            name -> exportIntoFxPack(editor, project, packFile, name)).show(editor);
                }).show(editor);
    }

    private static void exportIntoFxPack(Editor editor, FXProject project, File packFile, String fxName) {
        var fx = project.getFx();
        var namespace = baseName(packFile, FXPacks.SUFFIX);
        var fxId = FXPackExporter.fxId(namespace, fxName);
        if (refuseCycle(editor, project, fxId)) return;
        FXPackExporter.Result result;
        try {
            result = FXPackExporter.exportInto(packFile, namespace, fxName, fx, Platform.getFrozenRegistry());
        } catch (Exception e) {
            Photon.LOGGER.error("Failed to export fx into {}", packFile, e);
            showError(editor, "photon.export_fxpack", e);
            return;
        }
        result.warnings().forEach(warning -> Photon.LOGGER.warn("fxpack export: {}", warning));
        project.addKnownAs(result.fxId());
        Photon.LOGGER.info("Exported fx '{}' into {} ({} files)", result.fxId(), packFile, result.fileCount());
        Component message = Component.translatable("photon.fxpack.exported", result.fxId().toString(), packFile.getName());
        if (!result.warnings().isEmpty()) {
            message = join(message, Component.translatable("photon.fxpack.warnings", result.warnings().size()));
        }
        reportResolution(editor, packFile, result.fxId(), message);
    }

    /** Tells the author what playing the fx in game loads now, and offers the reload a pack new to the game needs. */
    private static void reportResolution(Editor editor, File packFile, ResourceLocation fxId, Component message) {
        if (!FXPacks.isLoadable(packFile)) {
            notify(editor, "photon.export_fxpack", join(message, Component.translatable("photon.fxpack.not_loadable")));
            return;
        }
        var resolution = FXPacks.resolve(packFile, fxId);
        switch (resolution.status()) {
            case LIVE -> notify(editor, "photon.export_fxpack", join(message, Component.translatable("photon.fxpack.live")));
            case SHADOWED -> notify(editor, "photon.export_fxpack",
                    join(message, Component.translatable("photon.fxpack.shadowed", resolution.sourcePackId())));
            case NOT_MOUNTED -> Dialog.showCheckBox("photon.export_fxpack",
                    join(message, Component.translatable("photon.fxpack.not_mounted")), reload -> {
                        if (reload) {
                            Minecraft.getInstance().reloadResourcePacks();
                        }
                    }).show(editor);
        }
    }

    // ---- conversion --------------------------------------------------------------------------------

    private static void showConvertFxDialog(Editor editor) {
        var fxDir = new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx");
        Dialog.showFileDialog("photon.convert_fx", Platform.getGamePath().toFile(), true,
                fxDir.isDirectory() ? fxDir : LDLib2.getAssetsDir(), Dialog.suffixFilter(FX.SUFFIX), file -> {
                    if (file == null || !file.isFile()) return;
                    pickProjectFile(editor, baseName(file, FX.SUFFIX), projectFile -> openConverted(editor, projectFile,
                            () -> FXProjectConverter.convertFile(file, projectFile)));
                }).show(editor);
    }

    /** Asks where a converted project goes, confirming before it replaces an existing file. */
    private static void pickProjectFile(Editor editor, String name, Consumer<File> onPicked) {
        var root = LDLib2.getAssetsDir();
        var suffix = FXProject.TYPE.getSuffix();
        var defaultFile = new File(root, name + suffix);
        for (int i = 1; defaultFile.exists(); i++) {
            defaultFile = new File(root, name + "_" + i + suffix);
        }
        Dialog.showFileDialog("photon.convert_fx.save_as", root, false, defaultFile, Dialog.suffixFilter(suffix), file -> {
            if (file == null || file.isDirectory()) return;
            var projectFile = withSuffix(file, suffix);
            if (!projectFile.exists()) {
                onPicked.accept(projectFile);
                return;
            }
            Dialog.showCheckBox("photon.convert_fx", Component.translatable("photon.convert_fx.overwrite", projectFile.getName()),
                    overwrite -> {
                        if (overwrite) {
                            onPicked.accept(projectFile);
                        }
                    }).show(editor);
        }).show(editor);
    }

    @FunctionalInterface
    private interface Conversion {
        FXProjectConverter.Result run() throws Exception;
    }

    private static void openConverted(Editor editor, File projectFile, Conversion conversion) {
        FXProjectConverter.Result result;
        try {
            result = conversion.run();
        } catch (Exception e) {
            Photon.LOGGER.error("Failed to convert fx into {}", projectFile, e);
            showError(editor, "photon.convert_fx", e);
            return;
        }
        result.warnings().forEach(warning -> Photon.LOGGER.warn("fx conversion: {}", warning));
        var current = editor.getCurrentProjectFile();
        if (current != null && current.getAbsoluteFile().toPath().normalize()
                .equals(projectFile.getAbsoluteFile().toPath().normalize())) {
            // its file was replaced on purpose: offering to save the old project over it would undo that
            editor.closeCurrentProject(false, null);
        }
        editor.loadProject(result.project(), projectFile);
        if (!result.warnings().isEmpty()) {
            notify(editor, "photon.convert_fx", Component.translatable("photon.convert_fx.missing", result.warnings().size()));
        }
    }

    // ---- pack management ---------------------------------------------------------------------------

    private static void showManageFxPackDialog(Editor editor) {
        Dialog.showFileDialog("photon.manage_fxpack", FXPacks.getFxPacksDir(), true,
                Dialog.suffixFilter(FXPacks.SUFFIX), file -> {
                    if (file != null && file.isFile()) {
                        showFxPackContents(editor, file);
                    }
                }).show(editor);
    }

    /** A pack's effects, each one convertible into a project or removable from the pack. */
    private static void showFxPackContents(Editor editor, File packFile) {
        List<ResourceLocation> fxIds;
        try {
            fxIds = FXPacks.listFx(packFile);
        } catch (Exception e) {
            Photon.LOGGER.error("Failed to read fx pack {}", packFile, e);
            showError(editor, "photon.manage_fxpack", e);
            return;
        }
        var dialog = new Dialog();
        dialog.overlay.layout(layout -> layout.width(260).maxWidthPercent(95));
        dialog.setTitle(packFile.getName());
        var list = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.gapAll(2);
        });
        if (!FXPacks.isLoadable(packFile)) {
            list.addChild(wrappedLabel(Component.translatable("photon.fxpack.not_loadable")));
        } else if (!FXPacks.isMounted(packFile)) {
            list.addChildren(wrappedLabel(Component.translatable("photon.manage_fxpack.not_mounted")),
                    new Button().setText("photon.fxpack.reload").setOnClick(e -> {
                        dialog.close();
                        Minecraft.getInstance().reloadResourcePacks();
                    }));
        }
        if (fxIds.isEmpty()) {
            list.addChild(new Label().setText("photon.manage_fxpack.empty"));
        }
        for (var fxId : fxIds) {
            var row = new UIElement().layout(layout -> {
                layout.widthPercent(100);
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.gapAll(4);
            });
            var name = fxId.getPath().substring(fxId.getPath().lastIndexOf('/') + 1);
            row.addChildren(
                    // a long id is cut at the buttons and rolls while hovered
                    new Label().setText(fxId.toString())
                            .textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL))
                            .layout(layout -> layout.flex(1)),
                    ResourceDialogs.iconButton(Icons.IMPORT, "photon.manage_fxpack.convert", e ->
                            pickProjectFile(editor, name, projectFile -> {
                                dialog.close();
                                openConverted(editor, projectFile,
                                        () -> FXProjectConverter.convertPacked(packFile, fxId, projectFile));
                            })),
                    ResourceDialogs.iconButton(Icons.DELETE, "photon.manage_fxpack.remove", e ->
                            confirmRemove(editor, dialog, packFile, fxId)));
            list.addChild(row);
        }
        dialog.addContent(list);
        dialog.addButton(new Button().setOnClick(e -> dialog.close())
                .setText("ldlib.gui.tips.confirm").addClass("__confirm-button__"));
        dialog.show(editor);
    }

    private static void confirmRemove(Editor editor, Dialog contents, File packFile, ResourceLocation fxId) {
        Dialog.showCheckBox("photon.manage_fxpack", Component.translatable("photon.manage_fxpack.remove_confirm", fxId.toString()),
                remove -> {
                    if (!remove) return;
                    try {
                        // garbage-collects the resources nothing else in the pack uses
                        FXPacks.removeFx(packFile, fxId);
                    } catch (Exception e) {
                        Photon.LOGGER.error("Failed to remove {} from {}", fxId, packFile, e);
                        showError(editor, "photon.manage_fxpack", e);
                        return;
                    }
                    contents.close();
                    showFxPackContents(editor, packFile); // rebuilt from the fresh listing
                }).show(editor);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static void showError(Editor editor, String title, Exception e) {
        notify(editor, title, e instanceof FXPacks.PackInUseException
                ? Component.translatable("photon.fxpack.in_use")
                : Component.translatable("photon.fxpack.failed", String.valueOf(e.getMessage())));
    }

    /**
     * Whether exporting {@code project} as {@code id} would make one of its FX instances place it inside
     * itself; if so the author is told through which effect.
     */
    private static boolean refuseCycle(Editor editor, FXProject project, ResourceLocation id) {
        var through = cycleThrough(project, id);
        if (through == null) return false;
        notify(editor, "photon.fx_instance", Component.translatable("photon.fx_instance.export_cycle", id.toString(), through.toString()));
        return true;
    }

    /** The effect through which {@code project} exported as {@code id} would place itself, or null. */
    @Nullable
    public static ResourceLocation cycleThrough(FXProject project, ResourceLocation id) {
        for (var object : project.getFx().getFxData().objects()) {
            if (!(object instanceof FXInstanceObject instance) || instance.getFx() == null) continue;
            if (instance.getFx().equals(id)) return id;
            for (var placed : FXInstances.templates().closure(instance.getFx())) {
                if (placed.equals(id)) return instance.getFx();
            }
        }
        return null;
    }

    /** Open the source of an FX instance: the {@code .fx} under the editor's assets folder, converted into a project. */
    public static void openSource(Editor editor, @Nullable ResourceLocation fx) {
        if (fx == null) return;
        var file = new File(LDLib2.getAssetsDir(), fx.getNamespace() + "/fx/" + fx.getPath() + FX.SUFFIX);
        if (!file.isFile()) {
            notify(editor, "photon.fx_instance", Component.translatable("photon.fx_instance.open_source.elsewhere", fx.toString()));
            return;
        }
        convertAndOpen(editor, file);
    }

    static void convertAndOpen(Editor editor, File file) {
        pickProjectFile(editor, baseName(file, FX.SUFFIX), projectFile -> openConverted(editor, projectFile,
                () -> FXProjectConverter.convertFile(file, projectFile)));
    }

    /** An {@code .fx} outside an assets folder's {@code <namespace>/fx/} has no id an effect can load it by. */
    public static void notifyOutsideAssets(Editor editor, File file) {
        notify(editor, "photon.fx_instance", Component.translatable(FXInstanceEditing.isUnderFxFolder(file)
                ? "photon.fx_instance.add_file.invalid_named" : "photon.fx_instance.add_file.outside_named", file.getName()));
    }

    static void notify(Editor editor, String title, Component message) {
        var dialog = new Dialog();
        dialog.setTitle(title);
        dialog.addContent(wrappedLabel(message));
        dialog.addButton(new Button().setOnClick(e -> dialog.close())
                .setText("ldlib.gui.tips.confirm").addClass("__confirm-button__"));
        dialog.show(editor);
    }

    /** Two sentences as one message, spaced the way the language spaces sentences. */
    private static Component join(Component first, Component second) {
        return Component.translatable("photon.fxpack.join", first, second);
    }

    private static UIElement wrappedLabel(Component text) {
        return new Label().textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true))
                .setText(text).layout(layout -> layout.widthPercent(100));
    }

    private static File withSuffix(File file, String suffix) {
        return file.getName().endsWith(suffix) ? file : new File(file.getParentFile(), file.getName() + suffix);
    }

    static String baseName(File file, String suffix) {
        var name = file.getName();
        return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
    }
}
