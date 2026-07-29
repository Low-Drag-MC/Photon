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
        if (registeredId != null) {
            // release() drops the registration AND closes the texture
            net.minecraft.client.Minecraft.getInstance().getTextureManager().release(registeredId);
            registeredId = null;
            curveTexture = null;
            return;
        }
        if (curveTexture != null) {
            curveTexture.close();
            curveTexture = null;
        }
    }

    /** Registration id, so the drain (which resolves samplers by {@link net.minecraft.resources.Identifier} before opening its
     *  pass) can bind this live texture. Allocated on first use; released with the texture. */
    @Nullable
    private net.minecraft.resources.Identifier registeredId;
    private static final java.util.concurrent.atomic.AtomicInteger ID_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Upload if dirty, then hand back the {@link net.minecraft.resources.Identifier} this sampler is registered under. 1.21 bound
     * the {@code DynamicTexture} straight to the shader ({@code shaderHolder.addDynamicSampler}); 26.1's
     * draw path takes texture IDENTIFIERS and resolves them through the {@code TextureManager} before the
     * pass opens, so the texture has to live in the registry. Render thread only.
     */
    @Nullable
    public net.minecraft.resources.Identifier textureId() {
        var texture = getCurveTexture();
        if (texture == null) {
            return null;
        }
        if (registeredId == null) {
            registeredId = com.lowdragmc.photon.Photon.id("dynamic/curve/" + ID_SEQ.getAndIncrement());
            net.minecraft.client.Minecraft.getInstance().getTextureManager().register(registeredId, texture);
        }
        return registeredId;
    }


    /**
     * One row per curve, {@code width} samples across, value in the RED channel — the 1.21 layout
     * ({@code FastColor.ABGR32.color(255, 0, 0, r)}), expressed through 26.1's ARGB helper. Rows past the
     * curve list are left untouched, also as in 1.21. Render thread only.
     */
    public void uploadTexture() {
        if (!isDirty) return;
        RenderSystem.assertOnRenderThread();
        if (curveTexture == null || curveTexture.getPixels() == null) {
            this.curveTexture = new DynamicTexture(() -> "Photon curve sampler", width, height, false);
        }
        var pixels = curveTexture.getPixels();
        if (pixels == null) return;
        for (int h = 0; h < height && h < curves.size(); h++) {
            var curve = curves.get(h);
            for (int w = 0; w < width; w++) {
                var y = curve.getCurves().getCurveY(w / (width - 1f));
                var r = Mth.clamp((int) (y * 255), 0, 255);
                pixels.setPixel(w, h, net.minecraft.util.ARGB.color(255, r, 0, 0));
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
