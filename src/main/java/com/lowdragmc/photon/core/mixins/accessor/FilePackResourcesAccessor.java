package com.lowdragmc.photon.core.mixins.accessor;

import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FilePackResources.class)
public interface FilePackResourcesAccessor {
    @Accessor
    FilePackResources.SharedZipFileAccess getZipFileAccess();
}
