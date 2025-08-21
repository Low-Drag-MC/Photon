package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.project.ProjectType;
import com.lowdragmc.lowdraglib2.editor.resource.ColorsResource;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.syncdata.ISubscription;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.gameobject.emitter.data.fixer.PhotonFXProjectDataFixer;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import lombok.Getter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

public class FXProject implements IProject {
    public static int VERSION = 3;
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

    @Override
    public CompoundTag serializeProject(@NotNull HolderLookup.Provider provider) {
        var data = new CompoundTag();
        data.put("fx", fx.serializeNBT(provider));
        return data;
    }

    @Override
    public void deserializeProject(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag nbt) {
        fx.deserializeNBT(provider, nbt.getCompound("fx"));
    }

    @Override
    public CompoundTag getMetadata() {
        var meta = IProject.super.getMetadata();
        meta.putInt("version_num", VERSION);
        return meta;
    }

    @Override
    public void deserializeNBT(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag nbt) {
        // apply data fix for cross-version
        var version = Math.max(1, nbt.getCompound("meta").getInt("version_num"));
        var fixedData = PhotonFXProjectDataFixer.INSTANCE.applyFixes(version, VERSION, nbt.getCompound("data"));
        deserializeProject(provider, fixedData);
    }

    @Override
    public void onLoad(Editor editor) {
        IProject.super.onLoad(editor);
        if (exportMenuSubscription != null) {
            exportMenuSubscription.unsubscribe();
        }
        exportMenuSubscription = editor.fileMenu.registerMenuCreator((tab, menu) ->
                menu.branch("ldlib.gui.editor.menu.export", m ->
                        m.leaf("photon.export_fx", () -> {
                            Dialog.showFileDialog("ldlib.gui.editor.tips.save_as", new File(LDLib2.getAssetsDir(), "%s/fx/".formatted(Photon.MOD_ID)), false,
                                    Dialog.suffixFilter(FX.SUFFIX), file -> {
                                        if (file != null && !file.isDirectory()) {
                                            if (!file.getName().endsWith(FX.SUFFIX)) {
                                                file = new File(file.getParentFile(), file.getName() + FX.SUFFIX);
                                            }
                                            try {
                                                var fileData = fx.serializeNBT(Platform.getFrozenRegistry());
                                                NbtIo.writeCompressed(fileData, file.toPath());
                                                FXHelper.clearCache();
                                            } catch (Exception ignored) {}
                                        }
                                    }).show(editor);
                        })
                ));
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
