package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/5
 * @implNote FXHelper
 */
@Environment(EnvType.CLIENT)
public class FXHelper {
    public static final String FX_PATH = "fx/";

    public static List<IParticleEmitter> getFX(ResourceLocation fxLocation) {
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
            return emitters;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

}
