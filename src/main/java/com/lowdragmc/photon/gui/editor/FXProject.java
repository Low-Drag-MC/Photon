package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.project.ProjectType;
import com.lowdragmc.lowdraglib2.editor.resource.ColorsResource;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.syncdata.ISubscription;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.fxpack.FXPackExporter;
import com.lowdragmc.photon.client.fx.fxpack.FXPacks;
import com.lowdragmc.photon.client.gameobject.emitter.data.fixer.PhotonFXProjectDataFixer;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import com.lowdragmc.photon.gui.editor.resource.RenderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

public class FXProject implements IProject {
    public static int VERSION = 5;
    public static final ProjectType TYPE = ProjectType.of(IGuiTexture.EMPTY, "fx_project", ".fxproj", FXProject::new);

    @Getter
    private final Resources resources;
    @Getter
    private final FX fx = new FX();
    // runtime
    @Nullable
    private ISubscription exportMenuSubscription;

    public FXProject() {
        this.resources = Resources.of(
                MaterialResource.INSTANCE,
                ShaderGraphResource.INSTANCE,
                PhotonShaderFunctionGraphResource.INSTANCE,
                FullscreenShaderGraphResource.INSTANCE,
                RenderGraphResource.INSTANCE,
                ColorsResource.INSTANCE,
                CurveResource.INSTANCE,
                GradientResource.INSTANCE,
                MeshResource.INSTANCE
        );
    }

    @Override
    public String getVersion() {
        return "%d.0".formatted(VERSION) ;
    }

    @Override
    public ProjectType getProjectType() {
        return TYPE;
    }

    // 26.1: IProject moved to the ValueIO seam ({meta, data} shape unchanged); the .fx payload stays
    // the same "fx" compound produced by FX's Tag methods.
    @Override
    public void serializeProject(@NotNull ValueOutput output) {
        output.store("fx", ExtraCodecs.NBT, fx.serializeNBT(Platform.getFrozenRegistry()));
    }

    @Override
    public void deserializeProject(@NotNull ValueInput input) {
        if (input.read("fx", ExtraCodecs.NBT).orElse(null) instanceof CompoundTag tag) {
            fx.deserializeNBT(Platform.getFrozenRegistry(), tag);
        }
    }

    @Override
    public void serializeMetadata(ValueOutput output) {
        IProject.super.serializeMetadata(output);
        output.putInt("version_num", VERSION);
    }

    @Override
    public void deserialize(@NotNull ValueInput input) {
        // apply data fix for cross-version
        var version = Math.max(1, input.child("meta").map(meta -> meta.getIntOr("version_num", 0)).orElse(0));
        var data = input.read("data", ExtraCodecs.NBT).orElse(null) instanceof CompoundTag tag
                ? tag : new CompoundTag();
        var fixedData = PhotonFXProjectDataFixer.INSTANCE.applyFixes(version, VERSION, data);
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            deserializeProject(TagValueInput.create(
                    reporter, Platform.getFrozenRegistry(), fixedData));
        }
    }

    @Override
    public void onLoad(Editor editor) {
        IProject.super.onLoad(editor);
        if (exportMenuSubscription != null) {
            exportMenuSubscription.unsubscribe();
        }
        exportMenuSubscription = editor.fileMenu.registerMenuCreator((tab, menu) ->
                menu.branch("ldlib.gui.editor.menu.export", m -> {
                    // plain .fx: pure definition data (references only) — usable wherever the
                    // referenced resources already exist (this machine, a mod jar, an .fxpack)
                    m.leaf("photon.export_fx", () -> showExportFxDialog(editor));
                    // .fxpack: a resource-pack zip carrying the fx plus everything it references —
                    // the self-contained distribution format (additive: exports into the same pack
                    // accumulate, shared resources are stored once)
                    m.leaf("photon.export_fxpack", () -> showExportFxPackDialog(editor));
                    // browse/remove the effects inside an existing pack
                    m.leaf("photon.manage_fxpack", () -> showManageFxPackDialog(editor));
                }));
    }

    private void showExportFxDialog(Editor editor) {
        Dialog.showFileDialog("ldlib.gui.editor.tips.save_as", new File(LDLib2.getAssetsDir(), "%s/fx/".formatted(Photon.MOD_ID)), false,
                Dialog.suffixFilter(FX.SUFFIX), file -> {
                    if (file == null || file.isDirectory()) return;
                    if (!file.getName().endsWith(FX.SUFFIX)) {
                        file = new File(file.getParentFile(), file.getName() + FX.SUFFIX);
                    }
                    try {
                        var fileData = fx.serializeNBT(Platform.getFrozenRegistry());
                        // stamp the format version so FXHelper can run the datafixer on future loads
                        fileData.putInt("version", VERSION);
                        NbtIo.writeCompressed(fileData, file.toPath());
                        FXHelper.clearCache();
                    } catch (Exception e) {
                        Photon.LOGGER.error("Failed to export fx to {}", file, e);
                    }
                }).show(editor);
    }

    private void showExportFxPackDialog(Editor editor) {
        Dialog.showFileDialog("ldlib.gui.editor.tips.save_as", FXPacks.getFxPacksDir(), false,
                Dialog.suffixFilter(FXPacks.SUFFIX), file -> {
                    if (file == null || file.isDirectory()) return;
                    if (!file.getName().endsWith(FXPacks.SUFFIX)) {
                        file = new File(file.getParentFile(), file.getName() + FXPacks.SUFFIX);
                    }
                    showFxNameDialog(editor, file);
                }).show(editor);
    }

    /** Ask for the fx's name inside the pack (its id becomes {@code <packname>:<name>}). */
    private void showFxNameDialog(Editor editor, File fxpackFile) {
        var defaultName = editor.getCurrentProjectFile() != null
                ? editor.getCurrentProjectFile().getName().replaceAll("\\.fxproj$", "")
                : "effect";
        Dialog.stringEditorDialog("photon.export_fxpack.fx_name", defaultName, null,
                name -> exportIntoFxPack(editor, fxpackFile, name)).show(editor);
    }

    private void showManageFxPackDialog(Editor editor) {
        Dialog.showFileDialog("photon.manage_fxpack", FXPacks.getFxPacksDir(), true,
                Dialog.suffixFilter(FXPacks.SUFFIX), file -> {
                    if (file != null && file.isFile()) {
                        showFxPackContents(editor, file);
                    }
                }).show(editor);
    }

    /** List a pack's effects with a remove button each; removal garbage-collects orphaned resources. */
    private void showFxPackContents(Editor editor, File fxpackFile) {
        List<Identifier> fxIds;
        try {
            fxIds = FXPacks.listFx(fxpackFile);
        } catch (Exception e) {
            Photon.LOGGER.error("Failed to read fx pack {}", fxpackFile, e);
            return;
        }
        var dialog = new Dialog();
        dialog.setTitle(fxpackFile.getName());
        var list = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.gapAll(2);
        });
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
            row.addChildren(
                    new Label().setText(fxId.toString()).layout(layout -> layout.flex(1)),
                    new Button().setText("✕").setOnClick(e -> {
                        if (FXPacks.isFileLocked(fxpackFile)) {
                            Dialog.showNotification("photon.manage_fxpack", "photon.fxpack.locked", null).show(editor);
                            return;
                        }
                        try {
                            FXPacks.removeFx(fxpackFile, fxId);
                            FXHelper.clearCache();
                        } catch (Exception ex) {
                            Photon.LOGGER.error("Failed to remove {} from {}", fxId, fxpackFile, ex);
                        }
                        dialog.close();
                        showFxPackContents(editor, fxpackFile); // rebuild with the fresh listing
                    }));
            list.addChild(row);
        }
        dialog.addContent(list);
        dialog.addButton(new Button().setOnClick(e -> dialog.close())
                .setText("ldlib.gui.tips.confirm").addClass("__confirm-button__"));
        dialog.show(editor);
    }

    private void exportIntoFxPack(Editor editor, File fxpackFile, String fxName) {
        if (FXPacks.isFileLocked(fxpackFile)) {
            // a mounted pack that served a read holds a ZipFile handle; on Windows that blocks the
            // zipfs rename-over-original commit — tell the user how to release it instead of failing
            Dialog.showNotification("photon.export_fxpack", "photon.fxpack.locked", null).show(editor);
            return;
        }
        try {
            var namespace = fxpackFile.getName().substring(0, fxpackFile.getName().length() - FXPacks.SUFFIX.length());
            var result = FXPackExporter.exportInto(fxpackFile, namespace, fxName, fx, Platform.getFrozenRegistry());
            result.warnings().forEach(warning -> Photon.LOGGER.warn("fxpack export: {}", warning));
            Photon.LOGGER.info("Exported fx '{}' into {} ({} files)", result.fxId(), fxpackFile, result.fileCount());
            FXHelper.clearCache();
            // the pack mounts on the next resource reload — tell the author how to see it in game
            Dialog.showNotification("photon.export_fxpack",
                    "%s → %s%s".formatted(result.fxId(), fxpackFile.getName(),
                            result.warnings().isEmpty() ? "" : "  (%d warnings, see log)".formatted(result.warnings().size())),
                    null).show(editor);
        } catch (Exception e) {
            Photon.LOGGER.error("Failed to export fx into {}", fxpackFile, e);
        }
    }

    @Override
    public void onClosed(Editor editor) {
        IProject.super.onClosed(editor);
        if (exportMenuSubscription != null) {
            exportMenuSubscription.unsubscribe();
            exportMenuSubscription = null;
        }
    }
}
