package com.lowdragmc.photon.core.mixins.accessor;

import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;

@Mixin(FilePackResources.SharedZipFileAccess.class)
public interface SharedZipFileAccessAccessor {
    @Accessor
    File getFile();

    @Accessor
    void setFailedToLoad(boolean failedToLoad);
}
