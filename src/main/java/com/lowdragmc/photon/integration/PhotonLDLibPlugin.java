package com.lowdragmc.photon.integration;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.plugin.ILDLibPlugin;
import com.lowdragmc.lowdraglib2.plugin.LDLibPlugin;
import com.lowdragmc.lowdraglib2.syncdata.AccessorRegistries;
import com.lowdragmc.lowdraglib2.syncdata.accessor.direct.CustomDirectAccessor;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import net.minecraft.network.codec.ByteBufCodecs;


/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote LDLibPlugin
 */
@LDLibPlugin
public class PhotonLDLibPlugin implements ILDLibPlugin {

    @Override
    public void onLoad() {
        AccessorRegistries.setPriority(1000);
        if (LDLib2.isClient()) {
            AccessorRegistries.registerAccessor(CustomDirectAccessor.builder(NumberFunction.class)
                    .codec(NumberFunction.CODEC)
                    .streamCodec(ByteBufCodecs.fromCodec(NumberFunction.CODEC))
                    .codecMark()
                    .build());
            AccessorRegistries.registerAccessor(CustomDirectAccessor.builder(NumberFunction3.class)
                    .codec(NumberFunction3.CODEC)
                    .streamCodec(ByteBufCodecs.fromCodec(NumberFunction3.CODEC))
                    .codecMark()
                    .build());
            AccessorRegistries.registerAccessor(CustomDirectAccessor.builder(IShape.class)
                    .codec(IShape.CODEC)
                    .streamCodec(ByteBufCodecs.fromCodec(IShape.CODEC))
                    .codecMark()
                    .build());
            AccessorRegistries.registerAccessor(CustomDirectAccessor.builder(IMaterial.class)
                    .codec(IMaterial.CODEC)
                    .streamCodec(ByteBufCodecs.fromCodec(IMaterial.CODEC))
                    .copyMark(IMaterial::copy)
                    .build());
        }
    }
}
