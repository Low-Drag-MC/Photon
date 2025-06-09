package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.inventory.InventoryMenu;

@OnlyIn(Dist.CLIENT)
public class BlockTextureSheetMaterial extends TextureMaterial {

    public BlockTextureSheetMaterial() {
        super(InventoryMenu.BLOCK_ATLAS);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {

    }
}
