package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.inventory.InventoryMenu;

@Environment(EnvType.CLIENT)
public class BlockTextureSheetMaterial extends TextureMaterial {

    public BlockTextureSheetMaterial() {
        super(InventoryMenu.BLOCK_ATLAS);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {

    }
}
