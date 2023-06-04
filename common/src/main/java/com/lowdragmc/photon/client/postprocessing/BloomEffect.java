package com.lowdragmc.photon.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote BloomEffect
 */
@Environment(EnvType.CLIENT)
public class BloomEffect {

    private static RenderTarget FILTER;
    private static RenderTarget SWAP2A, SWAP4A, SWAP8A, SWAP16A, SWAP2B, SWAP4B, SWAP8B, SWAP16B;

    public static void updateScreenSize(int width, int height) {
        FILTER = resize(FILTER, width, height, true);

        SWAP2A = resize(SWAP2A, width / 2, height / 2, false);
        SWAP4A = resize(SWAP4A, width / 4, height / 4, false);
        SWAP8A = resize(SWAP8A, width / 8, height / 8, false);
        SWAP16A = resize(SWAP16A, width / 16, height / 16, false);

        SWAP2B = resize(SWAP2B, width / 2, height / 2, false);
        SWAP4B = resize(SWAP4B, width / 4, height / 4, false);
        SWAP8B = resize(SWAP8B, width / 8, height / 8, false);
        SWAP16B = resize(SWAP16B, width / 16, height / 16, false);
    }

    private static RenderTarget resize(RenderTarget target, int width, int height, boolean useDepth) {
        if (target == null) {
            target = new TextureTarget(width, height, useDepth, Minecraft.ON_OSX);
            return target;
        }
        target.resize(width, height, Minecraft.ON_OSX);
        return target;
    }
}
