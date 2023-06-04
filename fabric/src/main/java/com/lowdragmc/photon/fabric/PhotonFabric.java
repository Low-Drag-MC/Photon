package com.lowdragmc.photon.fabric;

import com.lowdragmc.photon.Photon;
import net.fabricmc.api.ModInitializer;

public class PhotonFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Photon.init();
    }

}
