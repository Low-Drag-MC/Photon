package com.lowdragmc.photon.client.fx.compat;


import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.Photon;
import net.minecraft.nbt.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A compatibility layer for porting Photon 1 FX to Photon 2
 * @author Cdogsnappy
 */
public class FXCompat {

    private static final Path FX_CVT_PATH = Path.of(LDLib2.getAssetsDir() + "/photon/fx_old");

    public static int convertFX() {
        if (!Files.exists(FX_CVT_PATH)) return 0;
        AtomicInteger count = new AtomicInteger();
        try {
            Files.createDirectories(Path.of(LDLib2.getAssetsDir() + "/photon/fx"));
        } catch (IOException e) {
            Photon.LOGGER.error(e.getMessage());
        }
        try {
            Files.list(FX_CVT_PATH).forEach(path -> {
                try {
                    CompoundTag photon1FX = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                    CompoundTag photon2FX = mapEffect(photon1FX);
                    NbtIo.writeCompressed(photon2FX, Path.of(LDLib2.getAssetsDir() + "/photon/fx/" + path.getFileName()));
                    count.getAndIncrement();
                }
                catch(IOException e){
                    Photon.LOGGER.error("Failed to read FX tag at {}", path.getFileName());
                }
            });
        } catch (IOException e) {
            Photon.LOGGER.error("Failed to read fx_old directory");
        }
        return count.get();
    }

    public static CompoundTag mapEffect(CompoundTag fx) {
        //top level mapping
        CompoundTag fxData = new CompoundTag();
        CompoundTag new_fx = new CompoundTag();
        ListTag fxObj = new ListTag(); // new fx objects
        //drill down to fxData
        ListTag fxObjects = fx.getCompound("fx").getCompound("mainFX").getList("fxObjects", 10);
        for (Tag object : fxObjects) {
            CompoundTag fxObject = (CompoundTag) object;
            switch (fxObject.getString("_type")) {
                case "beam":
                    fxObj.add(BeamEmitterMapper.mapBeamEmitter(fxObject));
                    break;
                case "trail":
                    fxObj.add(TrailEmitterMapper.mapTrailEmitter(fxObject));
                    break;
                case "particle":
                    fxObj.add(ParticleEmitterMapper.mapParticleEmitter(fxObject));
                    break;
                case "empty":
                    fxObj.add(EmptyMapper.mapEmpty(fxObject));
                    break;
                default:
                    Photon.LOGGER.warn("Detected unknown type {}", fxObject.getString("_type"));

            }
        }
        fxData.put("fxObjects", fxObj);
        new_fx.put("fxData", fxData);
        return new_fx;
    }

}
