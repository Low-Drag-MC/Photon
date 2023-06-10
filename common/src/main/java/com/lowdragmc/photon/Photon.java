package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.Platform;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Photon {
    public static final String MOD_ID = "photon";
    public static final String NAME = "Photon";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

    public static void init() {
        LOGGER.info("{} is initializing on platform: {}", NAME, Platform.platformName());
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    @ExpectPlatform
    public static boolean isStencilEnabled(RenderTarget target) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean useCombinedDepthStencilAttachment() {
        throw new AssertionError();
    }

    public static boolean isShaderModInstalled() {
        return LDLib.isModLoaded("iris") || LDLib.isModLoaded("oculus");
    }

    public static boolean isUsingShaderPack() {
        if (isShaderModInstalled()) {
            return IrisApi.getInstance().isShaderPackInUse();
        }
        return false;
    }
}

