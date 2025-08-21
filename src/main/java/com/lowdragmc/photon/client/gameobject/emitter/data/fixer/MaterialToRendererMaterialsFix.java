package com.lowdragmc.photon.client.gameobject.emitter.data.fixer;

import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;

import java.util.Map;
import java.util.stream.Stream;

import static com.mojang.datafixers.DSL.remainderFinder;

public final class MaterialToRendererMaterialsFix extends DataFix {
    public MaterialToRendererMaterialsFix(Schema outputSchema) {
        super(outputSchema, true);
    }

    @Override
    protected TypeRewriteRule makeRule() {
        return fixTypeEverywhereTyped("material_to_renderer_materials_fix",
                getInputSchema().getType(PhotonReferences.FX_PROJECT),
            typed -> typed.update(remainderFinder(), this::fixStructure));
    }


    private Dynamic<?> fixStructure(Dynamic<?> dynamic) {
        return dynamic.update("fx", fx ->
                fx.update("fxData", fxData -> fxData.update("fxObjects",
                        fxObjects -> fxObjects.createList(fxObjects.asStream().map(this::fixFXObject))
                )));
    }

    private Dynamic<?> fixFXObject(Dynamic<?> fxObject) {
        return fxObject.update("data", data -> data.update("config", config -> {
            var materialOpt = config.get("material").result();
            var result = config;
            if (materialOpt.isPresent()) {
                result = config
                        .remove("material")
                        .update("renderer", renderer -> renderer.set("materials", config.createMap(Map.of(
                                config.createString("uid"), config.createInt(1),
                                config.createString("payload"), config.createList(Stream.of(materialOpt.get()))
                        ))));
            }
            var trailMaterialOpt = result.get("trails").get("config").get("material").result();
            if (trailMaterialOpt.isPresent()) {
                result = result.update("trails", trails -> trails.update("config", trailConfig -> trailConfig
                                .remove("material")
                                .update("renderer", renderer -> renderer.set("materials", config.createMap(Map.of(
                                        config.createString("uid"), config.createInt(1),
                                        config.createString("payload"), config.createList(Stream.of(trailMaterialOpt.get()))
                                )))))
                        );
            }
            return result;
        }));
    }


}
