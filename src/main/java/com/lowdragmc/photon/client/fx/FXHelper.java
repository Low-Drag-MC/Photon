package com.lowdragmc.photon.client.fx;

import com.lowdragmc.lowdraglib2.Platform;
import net.minecraft.nbt.NbtAccounter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashMap;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote FXHelper
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class FXHelper {
    private final static Map<ResourceLocation, FX> CACHE = new HashMap<>();
    public static final String FX_PATH = "fx/";

    public static int clearCache() {
        var count = CACHE.size();
        CACHE.clear();
        return count;
    }

    @Nullable
    public static FX getFX(ResourceLocation fxLocation) {
        return getFX(fxLocation, true);
    }


    @Nullable
    public static FX getFX(ResourceLocation fxLocation, boolean useCache) {
        return useCache ? CACHE.computeIfAbsent(fxLocation, location -> loadFX(fxLocation)) : loadFX(fxLocation);
    }

    @Nullable
    private static FX loadFX(ResourceLocation fxLocation) {
        ResourceLocation resourceLocation = ResourceLocation.fromNamespaceAndPath(fxLocation.getNamespace(), FX_PATH + fxLocation.getPath() + FX.SUFFIX);
        try (var inputStream = Minecraft.getInstance().getResourceManager().open(resourceLocation);) {
            var tag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
            var fx = new FX();
            fx.setFxLocation(fxLocation);
            fx.deserializeNBT(Platform.getFrozenRegistry(), tag);
            return fx;
        } catch (Exception ignored) {
            return null;
        }
    }

}
