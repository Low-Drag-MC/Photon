package com.lowdragmc.photon.client.fx;


import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Map;

/**
 * A compatibility layer for porting Photon 1 FX to Photon 2
 * @author Cdogsnappy
 */
public class FXCompat {

    private static String FX_CVT_PATH = "fx_old/";

    public static void convertFX(String namespace){

        Map<ResourceLocation, Resource> fx = Minecraft.getInstance()
                .getResourceManager().listResources("data/" + namespace + "/" + FX_CVT_PATH,
                        location -> location.getPath().endsWith(".fx"));

        fx.forEach((key, value) -> {
            try {
                CompoundTag photon1FX = NbtIo.readCompressed(value.open(), NbtAccounter.unlimitedHeap());
                mapEffect(photon1FX);
            } catch (IOException e) {
                Photon.LOGGER.error("Failed to read FX tag at {}", key.toDebugFileName());
            }
        });

    }

    public static void mapEffect(CompoundTag fx) {
        //top level mapping
        CompoundTag new_fx = new CompoundTag();
        //drill down to fxData
        ListTag fxObjects = fx.getCompound("fx").getCompound("mainFX").getList("fxObjects", 10);
        for(int i = 0; i < fxObjects.size(); i++) {
            CompoundTag fxObject = (CompoundTag) fxObjects.get(i);
            fxObject.
        }
    }



}
