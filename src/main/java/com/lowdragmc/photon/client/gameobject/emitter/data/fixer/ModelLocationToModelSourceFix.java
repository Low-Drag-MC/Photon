package com.lowdragmc.photon.client.gameobject.emitter.data.fixer;

import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;

import static com.mojang.datafixers.DSL.remainderFinder;

/**
 * V4 → V5: model references become {@code MeshData} compounds holding a polymorphic
 * {@code IModelSource} wrapper: {@code {source: {type: "json_model", data: {modelLocation: ...}}}}.
 * <ul>
 *   <li>mesh shape: {@code shape.shape.data.meshData.modelLocation} → {@code meshData.source}
 *       (keying on the field's presence is enough — only mesh shapes serialize {@code meshData})</li>
 *   <li>model render mode: {@code renderer.model = {modelLocation}} → a MeshData compound (the
 *       compound is only ever written when {@code renderMode == Model}, so no mode check needed)</li>
 * </ul>
 */
public final class ModelLocationToModelSourceFix extends DataFix {
    public ModelLocationToModelSourceFix(Schema outputSchema) {
        super(outputSchema, true);
    }

    @Override
    protected TypeRewriteRule makeRule() {
        return fixTypeEverywhereTyped("model_location_to_model_source_fix",
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
        return fxObject.update("data", data -> data.update("config", this::fixConfig));
    }

    private Dynamic<?> fixConfig(Dynamic<?> config) {
        var result = config;

        // mesh shape site
        var meshLocationOpt = result.get("shape").get("shape").get("data").get("meshData").get("modelLocation").asString().result();
        if (meshLocationOpt.isPresent()) {
            result = result.update("shape", shape -> shape.update("shape", inner -> inner.update("data",
                    shapeData -> shapeData.update("meshData", meshData -> meshData
                            .remove("modelLocation")
                            .set("source", jsonModelSource(meshData, meshLocationOpt.get()))))));
        }

        // model render mode site: the new payload is a MeshData compound {source: <wrapper>}
        var rendererLocationOpt = result.get("renderer").get("model").get("modelLocation").asString().result();
        if (rendererLocationOpt.isPresent()) {
            result = result.update("renderer", renderer ->
                    renderer.set("model", renderer.emptyMap()
                            .set("source", jsonModelSource(renderer, rendererLocationOpt.get()))));
        }

        // trails nest another config (defensive recursion, matching the existing fixers)
        if (result.get("trails").get("config").result().isPresent()) {
            result = result.update("trails", trails -> trails.update("config", this::fixConfig));
        }
        return result;
    }

    /** {@code {type: "json_model", data: {modelLocation: <loc>}}} — the IModelSource dispatch wrapper. */
    private static Dynamic<?> jsonModelSource(Dynamic<?> ops, String modelLocation) {
        return ops.emptyMap()
                .set("type", ops.createString("json_model"))
                .set("data", ops.emptyMap().set("modelLocation", ops.createString(modelLocation)));
    }
}
