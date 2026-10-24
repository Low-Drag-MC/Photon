package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.project.ProjectType;
import com.lowdragmc.lowdraglib2.editor.resource.ColorsResource;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
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
import lombok.Setter;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

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
    /** The fx ids this project was converted from or exported to: an FX instance leading back to one is a cycle. */
    @Getter
    private final Set<ResourceLocation> knownAs = new LinkedHashSet<>();
    /** The editor showing this project, so an undo back to the moment it opened shows the objects it restores. */
    @Setter
    @Nullable
    private FXEditor editor;

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
        if (!knownAs.isEmpty()) {
            var ids = new ListTag();
            knownAs.forEach(id -> ids.add(StringTag.valueOf(id.toString())));
            data.put("knownAs", ids);
        }
        return data;
    }

    @Override
    public void deserializeProject(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag nbt) {
        knownAs.clear();
        var ids = nbt.getList("knownAs", Tag.TAG_STRING);
        for (int i = 0; i < ids.size(); i++) {
            var id = ResourceLocation.tryParse(ids.getString(i));
            if (id != null) knownAs.add(id);
        }
        fx.deserializeNBT(provider, nbt.getCompound("fx"), knownAs);
    }

    public void addKnownAs(ResourceLocation id) {
        knownAs.add(id);
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
        if (editor != null) {
            // an undo back to the moment the project opened restores the whole of it: when that is what it
            // already is, the objects the editor shows (and the history refers to) must stay the same ones.
            // What it was exported as since is no edit, so it is neither compared nor forgotten.
            var current = serializeProject(provider);
            if (withRootParents(fixedData).getCompound("fx").equals(withRootParents(current).getCompound("fx"))) return;
            var exportedAs = new LinkedHashSet<>(knownAs);
            deserializeProject(provider, fixedData);
            knownAs.addAll(exportedAs);
            editor.showReloadedProject();
            return;
        }
        deserializeProject(provider, fixedData);
    }

    /**
     * {@code data} with every object whose parent it does not have hanging off the root, which is what opening it
     * does: a project made in code saves no parent for its top-level objects (the codec's "_NULL_"), and an object
     * anchored in an FX instance whose source is missing saves a parent nothing places.
     */
    private static CompoundTag withRootParents(CompoundTag data) {
        var copy = data.copy();
        var objects = copy.getCompound("fx").getCompound("fxData").getList("fxObjects", Tag.TAG_COMPOUND);
        var ids = new HashSet<UUID>();
        for (int i = 0; i < objects.size(); i++) {
            var id = InstanceIds.parse(objects.getCompound(i).getCompound("data").getCompound("transform").getString("id"));
            if (id != null) ids.add(id);
        }
        for (int i = 0; i < objects.size(); i++) {
            var transform = objects.getCompound(i).getCompound("data").getCompound("transform");
            var parent = InstanceIds.parse(transform.getString("_parentId"));
            if (parent == null || !ids.contains(parent)) {
                transform.putString("_parentId", FXRuntime.ROOT_UUID.toString());
            }
        }
        return copy;
    }
}
