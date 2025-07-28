package com.lowdragmc.photon.client.gameobject.emitter.data.material;

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
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CurveTexture implements AutoCloseable, IConfigurable, INBTSerializable<ListTag> {
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
        if (!isDirty) return;
        RenderSystem.assertOnRenderThread();
        if (curveTexture == null || curveTexture.getPixels() == null)  {
            this.curveTexture = new DynamicTexture(width, height, false);
        }
        var pixels = curveTexture.getPixels();
        assert pixels != null;
        for (int h = 0; h < height; h++) {
            if (h >= curves.size()) {
//                for (int w = 0; w < width; w++) {
//                    pixels.setPixelRGBA(w, h, 0);
//                }
                continue;
            }
            var curve = curves.get(h);
            for (int w = 0; w < width; w++) {
                var y = curve.getCurves().getCurveY(w / (width - 1f));
                var r = Mth.clamp((int)(y * 255),0, 255);
                pixels.setPixelRGBA(w, h, FastColor.ABGR32.color(255, 0, 0, r));
            }
        }
        this.curveTexture.upload();
        isDirty = false;
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

    @Override
    public ListTag serializeNBT(@Nonnull HolderLookup.Provider provider) {
        var listTag = new ListTag();
        for (var curve : curves) {
            listTag.add(curve.serializeNBT(provider));
        }
        return listTag;
    }

    @Override
    public void deserializeNBT(@Nonnull HolderLookup.Provider provider, @Nonnull ListTag listTag) {
        curves.clear();
        for (Tag tag : listTag) {
            var curve = new Curve();
            curve.deserializeNBT(provider, (CompoundTag) tag);
            curves.add(curve);
        }
        markAsDirty();
    }
}
