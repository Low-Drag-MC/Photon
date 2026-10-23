package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.project.ProjectType;
import com.lowdragmc.lowdraglib2.editor.resource.ColorsResource;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.emitter.data.fixer.PhotonFXProjectDataFixer;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.RenderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import lombok.Getter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

/**
 * An FX editor project: the {@link FX} being authored. Exporting it and turning {@code .fx} files back into
 * projects live in {@link FXFileActions} and {@link FXProjectConverter}.
 */
public class FXProject implements IProject {
    public static int VERSION = 5;
    public static final ProjectType TYPE = ProjectType.of(PhotonIcons.PHOTON_PROJECT, "fx_project", ".fxproj", FXProject::new);

    @Getter
    private final Resources resources;
    @Getter
    private final FX fx = new FX();

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
}
