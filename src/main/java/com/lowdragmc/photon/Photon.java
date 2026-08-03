package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.client.PhotonClientProxy;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.test.gametest.PhotonGameTests;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

@Mod(Photon.MOD_ID)
public class Photon {
    public static final String MOD_ID = "photon";
    public static final String NAME = "Photon";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

    public Photon(IEventBus eventBus, ModContainer modContainer) {
        Photon.init();
        PhotonGameTests.init(eventBus);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, PhotonConfig.CONFIG_SPEC);
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
            new PhotonClientProxy(eventBus);
        } else {
            new PhotonCommonProxy(eventBus);
        }
    }

    public static void init() {
        LOGGER.info("{} is initializing on platform: {}", NAME, Platform.platformName());
        if (new File(LDLib2.getAssetsDir(), "photon").mkdirs()) {
            LOGGER.info("Created photon assets folder");
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** @see com.lowdragmc.photon.client.compat.iris.IrisCompat */
    public static boolean isUsingShaderPack() {
        return FMLEnvironment.getDist() == Dist.CLIENT && IrisCompat.isUsingShaderPack();
    }
}

