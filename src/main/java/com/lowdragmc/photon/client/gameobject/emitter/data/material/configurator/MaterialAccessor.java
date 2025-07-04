package com.lowdragmc.photon.client.gameobject.emitter.data.material.configurator;

import com.lowdragmc.lowdraglib2.configurator.accessors.TypesAccessor;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;

@LDLRegisterClient(name = "material", registry = "ldlib2:configurator_accessor")
public class MaterialAccessor extends TypesAccessor<IMaterial> {

    public MaterialAccessor() {
        super(IMaterial.class);
    }

    @Override
    public IMaterial defaultValue(Field field, Class<?> type) {
        return IMaterial.MISSING;
    }

    @Override
    public Configurator create(String name, Supplier<IMaterial> supplier, Consumer<IMaterial> consumer, boolean forceUpdate, Field field, Object owner) {
        return new IMaterialConfigurator(name, supplier, consumer, defaultValue(field, field.getType()), forceUpdate);
    }
}
