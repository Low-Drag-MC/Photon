package com.lowdragmc.photon.client.gameobject.emitter.data.fixer;

import com.google.common.collect.Maps;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.templates.TypeTemplate;

import java.util.Map;
import java.util.function.Supplier;

public final class PhotonSchemas {
    public static class V1 extends Schema {
        public V1(int versionKey, Schema parent) {
            super(versionKey, parent);
        }

        @Override
        public Map<String, Supplier<TypeTemplate>> registerEntities(Schema schema) {
            return Maps.newHashMap();
        }

        @Override
        public Map<String, Supplier<TypeTemplate>> registerBlockEntities(Schema schema) {
            return Maps.newHashMap();
        }

        @Override
        public void registerTypes(Schema schema, Map<String, Supplier<TypeTemplate>> entityTypes,
                                  Map<String, Supplier<TypeTemplate>> blockEntityTypes) {
            schema.registerType(true, PhotonReferences.FX_PROJECT, DSL::remainder);
        }
    }

}
