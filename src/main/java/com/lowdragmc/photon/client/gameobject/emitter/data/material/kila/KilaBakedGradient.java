package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.FastColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/** A gradient baked into a 256x1 texture for the shader to look up, re-baked whenever its stops changed. */
@OnlyIn(Dist.CLIENT)
final class KilaBakedGradient {
    static final int WIDTH = 256;

    private final GradientColor gradient;
    @Nullable
    private DynamicTexture baked;
    private long bakedHash;

    KilaBakedGradient(GradientColor gradient) {
        this.gradient = gradient;
    }

    int textureId() {
        RenderSystem.assertOnRenderThread();
        long hash = hash();
        if (baked == null || baked.getPixels() == null) {
            baked = new DynamicTexture(WIDTH, 1, false);
            AutoCloseCleaner.registerRenderThread(this, baked);
            bakedHash = hash + 1;
        }
        if (hash != bakedHash) {
            var pixels = baked.getPixels();
            for (int x = 0; x < WIDTH; x++) {
                pixels.setPixelRGBA(x, 0, FastColor.ABGR32.fromArgb32(gradient.getColor(x / (WIDTH - 1f))));
            }
            baked.upload();
            bakedHash = hash;
        }
        return baked.getId();
    }

    private long hash() {
        long hash = 17;
        for (var stop : gradient.getRgbP()) {
            hash = hash * 31 + Float.floatToIntBits(stop.x);
            hash = hash * 31 + Float.floatToIntBits(stop.y);
            hash = hash * 31 + Float.floatToIntBits(stop.z);
            hash = hash * 31 + Float.floatToIntBits(stop.w);
        }
        for (var stop : gradient.getAP()) {
            hash = hash * 31 + Float.floatToIntBits(stop.x);
            hash = hash * 31 + Float.floatToIntBits(stop.y);
        }
        return hash;
    }

    /** {@code into} takes the stops of {@code from}; the field stays the same object, which the baker holds. */
    static void copy(GradientColor from, GradientColor into) {
        into.getRgbP().clear();
        from.getRgbP().forEach(stop -> into.getRgbP().add(new Vector4f(stop)));
        into.getAP().clear();
        from.getAP().forEach(stop -> into.getAP().add(new Vector2f(stop)));
    }
}
