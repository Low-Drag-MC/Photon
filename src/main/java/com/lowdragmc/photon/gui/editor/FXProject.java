package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.resource.ColorsResource;
import com.lowdragmc.lowdraglib2.editor.resource.IRendererResource;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.lowdraglib2.editor.ui.menu.FileMenu;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import lombok.Getter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

public class FXProject implements IProject {
    public static final FileMenu.ProjectProvider PROVIDER = FileMenu.ProjectProvider.of(IGuiTexture.EMPTY, "fx_project", ".fxproj", FXProject::new);
    @Getter
    private final Resources resources;

    public FXProject() {
        this.resources = Resources.of(
                new MaterialResource(),
                new ColorsResource(),
                new IRendererResource()
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
        return new CompoundTag();
    }

    @Override
    public void deserializeProject(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag nbt) {

    }
}
