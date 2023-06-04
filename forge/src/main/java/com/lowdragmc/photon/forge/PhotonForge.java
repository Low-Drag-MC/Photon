package com.lowdragmc.photon.forge;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.forge.ClientProxyImpl;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(Photon.MOD_ID)
public class PhotonForge {
    public PhotonForge() {
        Photon.init();
        DistExecutor.unsafeRunForDist(() -> ClientProxyImpl::new, () -> CommonProxyImpl::new);
    }

}
