package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Supplier;

public class PhotonRegistries {

    @OnlyIn(Dist.CLIENT)
    public final static AutoRegistry.LDLibRegisterClient<IFXObject, Supplier<IFXObject>> FX_OBJECTS = AutoRegistry.LDLibRegisterClient
            .create(Photon.id("fx_object"), IFXObject.class, AutoRegistry::noArgsCreator);

    @OnlyIn(Dist.CLIENT)
    public final static AutoRegistry.LDLibRegisterClient<IMaterial, Supplier<IMaterial>> MATERIALS = AutoRegistry.LDLibRegisterClient
            .create(Photon.id("material"), IMaterial.class, AutoRegistry::noArgsCreator);

    public static void init() {

    }
}
