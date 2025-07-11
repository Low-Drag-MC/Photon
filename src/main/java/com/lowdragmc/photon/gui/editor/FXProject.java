package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.resource.ColorsResource;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.lowdraglib2.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.editor.ui.menu.FileMenu;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Dialog;
import com.lowdragmc.lowdraglib2.syncdata.ISubscription;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import lombok.Getter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

public class FXProject implements IProject {
    public static final FileMenu.ProjectProvider PROVIDER = FileMenu.ProjectProvider.of(IGuiTexture.EMPTY, "fx_project", ".fxproj", FXProject::new);

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
                GradientResource.INSTANCE
        );
    }

    @Override
    public String getSuffix() {
        return PROVIDER.suffix;
    }

    @Override
    public String getName() {
        return PROVIDER.name;
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
