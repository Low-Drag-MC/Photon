package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.project.IProject;
import com.lowdragmc.lowdraglib2.editor.project.ProjectType;
import com.lowdragmc.lowdraglib2.editor.resource.ColorsResource;
import com.lowdragmc.lowdraglib2.editor.resource.Resources;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.gameobject.emitter.data.fixer.PhotonFXProjectDataFixer;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import com.lowdragmc.photon.gui.editor.resource.FullscreenShaderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.GradientResource;
import com.lowdragmc.photon.gui.editor.resource.MaterialResource;
import com.lowdragmc.photon.gui.editor.resource.MeshResource;
import com.lowdragmc.photon.gui.editor.resource.PhotonShaderFunctionGraphResource;
import com.lowdragmc.photon.gui.editor.resource.RenderGraphResource;
import com.lowdragmc.photon.gui.editor.resource.ShaderGraphResource;
import com.mojang.serialization.Codec;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final Set<Identifier> knownAs = new LinkedHashSet<>();
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

    // 26.1: IProject moved to the ValueIO seam ({meta, data} shape unchanged); the .fx payload stays
    // the same "fx" compound produced by FX's Tag methods.
    @Override
    public void serializeProject(@NotNull ValueOutput output) {
        output.store("fx", ExtraCodecs.NBT, fx.serializeNBT(Platform.getFrozenRegistry()));
        if (!knownAs.isEmpty()) {
            output.store("knownAs", Codec.STRING.listOf(), knownAs.stream().map(Identifier::toString).toList());
        }
    }

    @Override
    public void deserializeProject(@NotNull ValueInput input) {
        knownAs.clear();
        // one by one: an id that no longer parses is dropped, not the whole list
        for (var value : input.read("knownAs", Codec.STRING.listOf()).orElse(List.of())) {
            var id = Identifier.tryParse(value);
            if (id != null) knownAs.add(id);
        }
        var tag = input.read("fx", ExtraCodecs.NBT).orElse(null) instanceof CompoundTag compound
                ? compound : new CompoundTag();
        fx.deserializeNBT(Platform.getFrozenRegistry(), tag, knownAs);
    }

    public void addKnownAs(Identifier id) {
        knownAs.add(id);
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
        if (editor != null) {
            // an undo back to the moment the project opened restores the whole of it: when that is what it
            // already is, the objects the editor shows (and the history refers to) must stay the same ones.
            // What it was exported as since is no edit, so it is neither compared nor forgotten.
            var current = fx.serializeNBT(Platform.getFrozenRegistry());
            if (withRootParents(fixedData.getCompoundOrEmpty("fx")).equals(withRootParents(current))) return;
            var exportedAs = new LinkedHashSet<>(knownAs);
            readProject(fixedData);
            knownAs.addAll(exportedAs);
            editor.showReloadedProject();
            return;
        }
        readProject(fixedData);
    }

    private void readProject(CompoundTag data) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            deserializeProject(TagValueInput.create(reporter, Platform.getFrozenRegistry(), data));
        }
    }

    /**
     * {@code fx} with every object whose parent it does not have hanging off the root, which is what opening it
     * does: a project made in code saves no parent for its top-level objects (the codec's "_NULL_"), and an object
     * anchored in an FX instance whose source is missing saves a parent nothing places.
     */
    private static CompoundTag withRootParents(CompoundTag fx) {
        var copy = fx.copy();
        var objects = copy.getCompoundOrEmpty("fxData").getListOrEmpty("fxObjects");
        var ids = new HashSet<UUID>();
        for (Tag object : objects) {
            var id = InstanceIds.parse(transformOf(object).getStringOr("id", ""));
            if (id != null) ids.add(id);
        }
        for (Tag object : objects) {
            var transform = transformOf(object);
            var parent = InstanceIds.parse(transform.getStringOr("_parentId", ""));
            if (parent == null || !ids.contains(parent)) {
                transform.putString("_parentId", FXRuntime.ROOT_UUID.toString());
            }
        }
        return copy;
    }

    /** The object's live transform compound (written through), or a detached empty one. */
    private static CompoundTag transformOf(Tag object) {
        return object instanceof CompoundTag tag && tag.get("data") instanceof CompoundTag data
                && data.get("transform") instanceof CompoundTag transform ? transform : new CompoundTag();
    }
}
