package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.inventory.InventoryMenu;

import javax.annotation.Nonnull;

@OnlyIn(Dist.CLIENT)
@LDLRegisterClient(name = "block_atlas", registry = "photon:material", manual = true)
public final class BlockTextureSheetMaterial extends TextureMaterial {
    public static final BlockTextureSheetMaterial INSTANCE = new BlockTextureSheetMaterial();

    private BlockTextureSheetMaterial() {
        super(InventoryMenu.BLOCK_ATLAS);
    }

    @Override
    public void buildConfigurator(@Nonnull ConfiguratorGroup father) {
        createPreview(father);
    }
}
