package com.lowdragmc.photon;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlockTextureSheetMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Supplier;

public class PhotonRegistries {

    @OnlyIn(Dist.CLIENT)
    public static AutoRegistry.LDLibRegisterClient<IFXObject, Supplier<IFXObject>> FX_OBJECTS;

    @OnlyIn(Dist.CLIENT)
    public static AutoRegistry.LDLibRegisterClient<IMaterial, Supplier<IMaterial>> MATERIALS;

    @OnlyIn(Dist.CLIENT)
    public static AutoRegistry.LDLibRegisterClient<NumberFunction, Supplier<NumberFunction>> NUMBER_FUNCTIONS;

    @OnlyIn(Dist.CLIENT)
    public static AutoRegistry.LDLibRegisterClient<IShape, Supplier<IShape>> SHAPES;

    static {
        if (LDLib2.isClient()) {
            Client.load();
        }
    }

    public static void init() {
    }

    @OnlyIn(Dist.CLIENT)
    private static class Client {
        public static void load() {
            FX_OBJECTS = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("fx_object"), IFXObject.class, AutoRegistry::noArgsCreator);
            MATERIALS = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("material"), IMaterial.class, AutoRegistry::noArgsCreator);
            NUMBER_FUNCTIONS = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("number_function"), NumberFunction.class, AutoRegistry::noArgsCreator);
            SHAPES = AutoRegistry.LDLibRegisterClient
                    .create(Photon.id("shape"), IShape.class, AutoRegistry::noArgsCreator);
            MATERIALS.register("missing", AutoRegistry.Holder.of(
                    IMaterial.MissingMaterial.class.getAnnotation(LDLRegisterClient.class),
                    IMaterial.MissingMaterial.class,
                    () -> IMaterial.MISSING));
            MATERIALS.register("block_atlas", AutoRegistry.Holder.of(
                    BlockTextureSheetMaterial.class.getAnnotation(LDLRegisterClient.class),
                    BlockTextureSheetMaterial.class,
                    () -> BlockTextureSheetMaterial.INSTANCE));
        }
    }
}
