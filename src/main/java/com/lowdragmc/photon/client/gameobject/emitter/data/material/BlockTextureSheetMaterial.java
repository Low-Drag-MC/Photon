package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;

import javax.annotation.Nonnull;

@LDLRegisterClient(name = "block_atlas", registry = "photon:material")
public final class BlockTextureSheetMaterial extends TextureMaterial {
    public static final BlockTextureSheetMaterial INSTANCE = new BlockTextureSheetMaterial();

    private BlockTextureSheetMaterial() {
        super(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
    }

    @Override
    public void buildConfigurator(@Nonnull ConfiguratorGroup father) {
        createPreview(father);
    }
}
