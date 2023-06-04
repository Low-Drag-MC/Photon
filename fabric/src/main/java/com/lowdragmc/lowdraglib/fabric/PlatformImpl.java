package com.lowdragmc.lowdraglib.fabric;

import net.fabricmc.loader.api.FabricLoader;

public class PlatformImpl {
	public static String platformName() {
		return FabricLoader.getInstance().isModLoaded("quilt_loader") ? "Quilt" : "Fabric";
	}
}
