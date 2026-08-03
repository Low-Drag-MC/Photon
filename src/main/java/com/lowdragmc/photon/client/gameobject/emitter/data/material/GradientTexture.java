package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.GradientColorConfigurator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
        if (registeredId != null) {
            // release() drops the registration AND closes the texture
            Minecraft.getInstance().getTextureManager().release(registeredId);
            registeredId = null;
            gradientTexture = null;
            return;
        }
        if (gradientTexture != null) {
            gradientTexture.close();
            gradientTexture = null;
        }
    }

    /** Registration id, so the drain (which resolves samplers by {@link net.minecraft.resources.Identifier} before opening its
     *  pass) can bind this live texture. Allocated on first use; released with the texture. */
    @Nullable
    private Identifier registeredId;
    private static final AtomicInteger ID_SEQ =
            new AtomicInteger();

    /**
     * Upload if dirty, then hand back the {@link net.minecraft.resources.Identifier} this sampler is registered under. 1.21 bound
     * the {@code DynamicTexture} straight to the shader ({@code shaderHolder.addDynamicSampler}); 26.1's
     * draw path takes texture IDENTIFIERS and resolves them through the {@code TextureManager} before the
     * pass opens, so the texture has to live in the registry. Render thread only.
     */
    @Nullable
    public Identifier textureId() {
        var texture = getGradientTexture();
        if (texture == null) {
            return null;
        }
        if (registeredId == null) {
            registeredId = Photon.id("dynamic/gradient/" + ID_SEQ.getAndIncrement());
            Minecraft.getInstance().getTextureManager().register(registeredId, texture);
        }
        return registeredId;
    }


    /**
     * One row per gradient, {@code width} samples across — the 1.21 layout. {@code GradientColor.getColor}
     * returns ARGB, which is exactly what 26.1's {@code NativeImage.setPixel} takes (1.21 had to convert to
     * ABGR first). Rows past the gradient list are left untouched, as in 1.21. Render thread only.
     */
    public void uploadTexture() {
        if (!isDirty) return;
        RenderSystem.assertOnRenderThread();
        if (gradientTexture == null || gradientTexture.getPixels() == null) {
            this.gradientTexture = new DynamicTexture(() -> "Photon gradient sampler", width, height, false);
        }
        var pixels = gradientTexture.getPixels();
        if (pixels == null) return;
        for (int h = 0; h < height && h < gradients.size(); h++) {
            var gradient = gradients.get(h);
            for (int w = 0; w < width; w++) {
                pixels.setPixel(w, h, gradient.getColor(w / (width - 1f)));
            }
        }
        this.gradientTexture.upload();
        isDirty = false;
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
