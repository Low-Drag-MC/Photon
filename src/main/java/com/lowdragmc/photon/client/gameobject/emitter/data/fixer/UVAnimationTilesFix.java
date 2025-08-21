package com.lowdragmc.photon.client.gameobject.emitter.data.fixer;

import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;

import java.util.stream.IntStream;

import static com.mojang.datafixers.DSL.remainderFinder;

public final class UVAnimationTilesFix extends DataFix {
    public UVAnimationTilesFix(Schema outputSchema) {
        super(outputSchema, true);
    }

    @Override
    protected TypeRewriteRule makeRule() {
        return fixTypeEverywhereTyped("uv_animation_tiles_fix",
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
            config = config.update("uvAnimation", uvAnimation -> {
                var tilesOpt = uvAnimation.get("tiles").result();
                if (tilesOpt.isPresent()) {
                    var tiles = tilesOpt.get();
                    var a = tiles.get("a").result().map(d -> d.asInt(1)).orElse(1);
                    var b = tiles.get("b").result().map(d -> d.asInt(1)).orElse(1);
                    uvAnimation = uvAnimation.remove("tiles")
                            .set("tiles", tiles.createIntList(IntStream.builder().add(a).add(b).build()));
                }
                return uvAnimation;
            });
            return config;
        }));
    }


}
