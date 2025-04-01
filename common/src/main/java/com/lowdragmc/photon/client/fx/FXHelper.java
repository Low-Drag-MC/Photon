package com.lowdragmc.photon.client.fx;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
@Environment(EnvType.CLIENT)
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
        return CACHE.computeIfAbsent(fxLocation, location -> {
            ResourceLocation resourceLocation = new ResourceLocation(fxLocation.getNamespace(), FX_PATH + fxLocation.getPath() + ".fx");
            try (var inputStream = Minecraft.getInstance().getResourceManager().open(resourceLocation);) {
                var tag = NbtIo.readCompressed(inputStream);
                var version = tag.contains("_version") ? tag.getInt("_version") : 0;
                var fx = new FX();
                fx.setFxLocation(fxLocation);
                fx.deserializeNBT(tag.getCompound("fx"));
                if (version < 1) {
                    var emitters = new CompoundTag();
                    emitters.put("fxObjects", tag.getList("emitters", Tag.TAG_COMPOUND));
                    fx.getMainFX().deserializeNBT(emitters);
                }
                return fx;
            } catch (Exception ignored) {
                return null;
            }
        });
    }

}
