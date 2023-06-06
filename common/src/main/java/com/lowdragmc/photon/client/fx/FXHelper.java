package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote FXHelper
 */
@Environment(EnvType.CLIENT)
public class FXHelper {
    private final static Map<ResourceLocation, FX> CACHE = new HashMap<>();
    public static final String FX_PATH = "fx/";

    public static void clearCache() {
        CACHE.clear();
    }

    @Nullable
    public static FX getFX(ResourceLocation fxLocation) {
        return CACHE.computeIfAbsent(fxLocation, location -> {
            ResourceLocation resourceLocation = new ResourceLocation(fxLocation.getNamespace(), FX_PATH + fxLocation.getPath() + ".fx");
            try (var inputStream = Minecraft.getInstance().getResourceManager().open(resourceLocation);) {
                var tag = NbtIo.readCompressed(inputStream);
                List<IParticleEmitter> emitters = new ArrayList<>();
                for (var nbt : tag.getList("emitters", Tag.TAG_COMPOUND)) {
                    if (nbt instanceof CompoundTag data) {
                        var emitter = IParticleEmitter.deserializeWrapper(data);
                        if (emitter != null) {
                            emitters.add(emitter);
                        }
                    }
                }
                return new FX(fxLocation, emitters);
            } catch (Exception ignored) {
                return null;
            }
        });
    }

}
