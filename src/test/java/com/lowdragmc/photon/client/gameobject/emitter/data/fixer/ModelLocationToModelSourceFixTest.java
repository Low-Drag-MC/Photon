package com.lowdragmc.photon.client.gameobject.emitter.data.fixer;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V4 → V5 structure transform of {@link ModelLocationToModelSourceFix}, exercised through a
 * standalone fixer (mirroring PhotonFXProjectDataFixer's schema chain without loading FXProject,
 * which drags in client-only classes).
 */
class ModelLocationToModelSourceFixTest {
    private static final BiFunction<Integer, Schema, Schema> SAME = Schema::new;

    private static DataFixer fixer() {
        var builder = new DataFixerBuilder(5);
        builder.addSchema(1, PhotonSchemas.V1::new);
        builder.addSchema(2, SAME);
        builder.addSchema(3, SAME);
        builder.addSchema(4, SAME);
        var schema5 = builder.addSchema(5, SAME);
        builder.addFixer(new ModelLocationToModelSourceFix(schema5));
        return builder.build().fixer();
    }

    private static CompoundTag apply(CompoundTag data) {
        var fixed = fixer().update(PhotonReferences.FX_PROJECT, new Dynamic<>(NbtOps.INSTANCE, data), 4, 5);
        return (CompoundTag) fixed.getValue();
    }

    /** {@code data.fx.fxData.fxObjects[0].data.config = config} */
    private static CompoundTag project(CompoundTag config) {
        var objData = new CompoundTag();
        objData.put("config", config);
        var fxObject = new CompoundTag();
        fxObject.put("data", objData);
        var fxObjects = new ListTag();
        fxObjects.add(fxObject);
        var fxData = new CompoundTag();
        fxData.put("fxObjects", fxObjects);
        var fx = new CompoundTag();
        fx.put("fxData", fxData);
        var data = new CompoundTag();
        data.put("fx", fx);
        return data;
    }

    private static CompoundTag configOf(CompoundTag fixedProject) {
        return fixedProject.getCompoundOrEmpty("fx").getCompoundOrEmpty("fxData")
                .getListOrEmpty("fxObjects").getCompoundOrEmpty(0)
                .getCompoundOrEmpty("data").getCompoundOrEmpty("config");
    }

    private static CompoundTag meshShapeConfig(String location) {
        var meshData = new CompoundTag();
        meshData.putString("modelLocation", location);
        var shapeData = new CompoundTag();
        shapeData.putString("type", "Triangle");
        shapeData.put("meshData", meshData);
        var shapeWrapper = new CompoundTag();
        shapeWrapper.putString("type", "mesh");
        shapeWrapper.put("data", shapeData);
        var shape = new CompoundTag();
        shape.put("shape", shapeWrapper);
        var config = new CompoundTag();
        config.put("shape", shape);
        return config;
    }

    private static CompoundTag modelRendererConfig(String location) {
        var model = new CompoundTag();
        model.putString("modelLocation", location);
        var renderer = new CompoundTag();
        renderer.putString("renderMode", "Model");
        renderer.put("model", model);
        var config = new CompoundTag();
        config.put("renderer", renderer);
        return config;
    }

    private static void assertJsonSourceWrapper(CompoundTag wrapper, String expectedLocation) {
        assertEquals("json_model", wrapper.getStringOr("type", ""));
        assertEquals(expectedLocation, wrapper.getCompoundOrEmpty("data").getStringOr("modelLocation", ""));
    }

    @Test
    void wrapsMeshShapeModelLocation() {
        var fixed = apply(project(meshShapeConfig("minecraft:block/stone")));
        var meshData = configOf(fixed).getCompoundOrEmpty("shape").getCompoundOrEmpty("shape")
                .getCompoundOrEmpty("data").getCompoundOrEmpty("meshData");
        assertFalse(meshData.contains("modelLocation"), "legacy key must be removed");
        assertJsonSourceWrapper(meshData.getCompoundOrEmpty("source"), "minecraft:block/stone");
        // sibling fields of the shape data survive
        assertEquals("Triangle", configOf(fixed).getCompoundOrEmpty("shape").getCompoundOrEmpty("shape")
                .getCompoundOrEmpty("data").getStringOr("type", ""));
    }

    @Test
    void wrapsRendererModelIntoMeshData() {
        var fixed = apply(project(modelRendererConfig("photon:block/character")));
        var renderer = configOf(fixed).getCompoundOrEmpty("renderer");
        var model = renderer.getCompoundOrEmpty("model");
        assertFalse(model.contains("modelLocation"), "legacy key must be replaced");
        assertJsonSourceWrapper(model.getCompoundOrEmpty("source"), "photon:block/character");
        assertEquals("Model", renderer.getStringOr("renderMode", ""));
    }

    @Test
    void recursesIntoTrailsConfig() {
        var config = new CompoundTag();
        var trails = new CompoundTag();
        trails.put("config", modelRendererConfig("photon:block/character"));
        config.put("trails", trails);
        var fixed = apply(project(config));
        var trailRenderer = configOf(fixed).getCompoundOrEmpty("trails").getCompoundOrEmpty("config").getCompoundOrEmpty("renderer");
        assertJsonSourceWrapper(trailRenderer.getCompoundOrEmpty("model").getCompoundOrEmpty("source"), "photon:block/character");
    }

    @Test
    void leavesConfigsWithoutModelsUntouched(){
        var config = new CompoundTag();
        var renderer = new CompoundTag();
        renderer.putString("renderMode", "Billboard");
        config.put("renderer", renderer);
        var before = project(config).copy();
        assertEquals(before, apply(project(config)));
    }
}
