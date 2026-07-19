package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfigurator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CurveTexture implements AutoCloseable, IConfigurable, ValueIOSerializable {
    private final int width;
    private final int height;
    @Configurable(name = "curves", canCollapse = false, collapse = false)
    @ConfigList(configuratorMethod = "buildCurveConfigurator", addDefaultMethod = "addDefaultCurve")
    private final List<Curve> curves = new ArrayList<>();
    // runtime
    private boolean isDirty = false;
    @Nullable
    private DynamicTexture curveTexture;

    public CurveTexture(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void markAsDirty() {
        isDirty = true;
    }

    public void addCurve(Curve curve) {
        this.curves.add(curve);
        markAsDirty();
    }

    public void removeCurve(Curve curve) {
        this.curves.remove(curve);
    }

    public DynamicTexture getCurveTexture() {
        uploadTexture();
        return curveTexture;
    }

    @Override
    public void close() {
        if (curveTexture != null) {
            curveTexture.close();
            curveTexture = null;
        }
    }

    public void uploadTexture() {
        // TODO(M2): rebuild the curve sampler upload — DynamicTexture's ctor and NativeImage's pixel
        // API changed (FastColor→ARGB, label-based ctor); rebuilt with the material pipeline path.
    }

    private Configurator buildCurveConfigurator(Supplier<Curve> getter, Consumer<Curve> setter) {
        return new CurveConfigurator("", getter, curve -> {
            setter.accept(curve);
            markAsDirty();
        }, addDefaultCurve(), true).disableBoundField();
    }

    private Curve addDefaultCurve() {
        return new Curve();
    }

    @ConfigSetter(field = "curves")
    private void setCurves(List<Curve> curves) {
        if (curves != this.curves) {
            this.curves.clear();
            this.curves.addAll(curves);
        }
        markAsDirty();
    }

    // 26.1 note: was INBTSerializable<ListTag> (a bare list under the parent key). ValueIO-managed
    // values must be compound-shaped, so the legacy list now lives under a "curves" key —
    // old data needs a fixer: `field: [...]` -> `field: {curves: [...]}`.
    @Override
    public void serialize(@Nonnull ValueOutput output) {
        var listTag = new ListTag();
        for (var curve : curves) {
            listTag.add(PersistedParser.serializeNBT(curve, Platform.getFrozenRegistry()));
        }
        output.store("curves", ExtraCodecs.NBT, listTag);
    }

    @Override
    public void deserialize(@Nonnull ValueInput input) {
        curves.clear();
        if (input.read("curves", ExtraCodecs.NBT).orElse(null) instanceof ListTag listTag) {
            for (Tag tag : listTag) {
                var curve = new Curve();
                PersistedParser.deserializeNBT((CompoundTag) tag, curve, Platform.getFrozenRegistry());
                curves.add(curve);
            }
        }
        markAsDirty();
    }
}
