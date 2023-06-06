package com.lowdragmc.photon.forge;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.forge.ClientProxyImpl;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraftforge.common.ForgeConfig;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(Photon.MOD_ID)
public class PhotonImpl {
    public PhotonImpl() {
        Photon.init();
        DistExecutor.unsafeRunForDist(() -> ClientProxyImpl::new, () -> CommonProxyImpl::new);
    }

    public static boolean isStencilEnabled(RenderTarget target) {
        return target.isStencilEnabled();
    }

    public static boolean useCombinedDepthStencilAttachment() {
        return ForgeConfig.CLIENT.useCombinedDepthStencilAttachment.get();
    }

}
