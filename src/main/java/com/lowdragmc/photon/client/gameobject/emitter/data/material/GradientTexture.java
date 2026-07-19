package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.GradientColorConfigurator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GradientTexture implements AutoCloseable, IConfigurable, ValueIOSerializable {
    private final int width;
    private final int height;
    @Configurable(name = "gradients", canCollapse = false, collapse = false)
    @ConfigList(configuratorMethod = "buildGradientConfigurator", addDefaultMethod = "addDefaultGradient")
    private final List<GradientColor> gradients = new ArrayList<>();
    // runtime
    private boolean isDirty = false;
    @Nullable
    private DynamicTexture gradientTexture;

    public GradientTexture(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void markAsDirty() {
        isDirty = true;
    }

    public void addGradient(GradientColor gradient) {
        this.gradients.add(gradient);
        markAsDirty();
    }

    public void removeGradient(GradientColor gradient) {
        this.gradients.remove(gradient);
    }

    public DynamicTexture getGradientTexture() {
        uploadTexture();
        return gradientTexture;
    }

    @Override
    public void close() {
        if (gradientTexture != null) {
            gradientTexture.close();
            gradientTexture = null;
        }
    }

    public void uploadTexture() {
        // TODO(M2): rebuild the gradient sampler upload — DynamicTexture's ctor and NativeImage's pixel
        // API changed (FastColor→ARGB, label-based ctor); rebuilt with the material pipeline path.
    }

    private Configurator buildGradientConfigurator(Supplier<GradientColor> getter, Consumer<GradientColor> setter) {
        return new GradientColorConfigurator("", () -> getter.get().copy(), gradientColor -> {
            setter.accept(gradientColor);
            markAsDirty();
        }, addDefaultGradient(), true);
    }

    private GradientColor addDefaultGradient() {
        return new GradientColor();
    }

    @ConfigSetter(field = "gradients")
    private void setGradients(List<GradientColor> gradients) {
        if (gradients != this.gradients) {
            this.gradients.clear();
            this.gradients.addAll(gradients);
        }
        markAsDirty();
    }

    // 26.1 note: was INBTSerializable<ListTag> (a bare list under the parent key). ValueIO-managed
    // values must be compound-shaped, so the legacy list now lives under a "gradients" key —
    // old data needs a fixer: `field: [...]` -> `field: {gradients: [...]}`.
    @Override
    public void serialize(@Nonnull ValueOutput output) {
        var list = output.childrenList("gradients");
        for (var gradientColor : gradients) {
            gradientColor.serialize(list.addChild());
        }
    }

    @Override
    public void deserialize(@Nonnull ValueInput input) {
        gradients.clear();
        for (var child : input.childrenListOrEmpty("gradients")) {
            var gradientColor = new GradientColor();
            gradientColor.deserialize(child);
            gradients.add(gradientColor);
        }
        markAsDirty();
    }
}
