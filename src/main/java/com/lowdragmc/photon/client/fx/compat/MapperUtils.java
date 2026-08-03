package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;

public class MapperUtils {

    public static ListTag mapCoords(Tag coordTag){
        CompoundTag compoundTag = (CompoundTag)coordTag;
        ListTag newCoordTag = new ListTag();
        newCoordTag.add(FloatTag.valueOf(compoundTag.getFloatOr("x", 0.0F)));
        newCoordTag.add(FloatTag.valueOf(compoundTag.getFloatOr("y", 0.0F)));
        newCoordTag.add(FloatTag.valueOf(compoundTag.getFloatOr("z", 0.0F)));
        if (compoundTag.contains("w")) {
            newCoordTag.add(FloatTag.valueOf(compoundTag.getFloatOr("w", 0.0F)));

        }
        return newCoordTag;

    }
    public static ListTag mapCoordsDouble(Tag coordTag){
        CompoundTag compoundTag = (CompoundTag)coordTag;
        ListTag newCoordTag = new ListTag();
        newCoordTag.add(DoubleTag.valueOf(compoundTag.getFloatOr("x", 0.0F)));
        newCoordTag.add(DoubleTag.valueOf(compoundTag.getFloatOr("y", 0.0F)));
        newCoordTag.add(DoubleTag.valueOf(compoundTag.getFloatOr("z", 0.0F)));
        if (compoundTag.contains("w")) {
            newCoordTag.add(DoubleTag.valueOf(compoundTag.getFloatOr("w", 0.0F)));

        }
        return newCoordTag;

    }

    /**
     * This is for tags that contain 3 typed values
     * @param typed3VecTag the tag with the values, values from key will be used as keys inside of the tag, or (x,y,z) if null
     * @param key keys inside of the tag
     * @param key that the list tag lives under.
     * @return
     */
    public static ListTag mapTyped3Vec(CompoundTag typed3VecTag, String[] key, boolean floatToDouble){
        if(key == null){
            key = new String[]{"x", "y", "z"};
        }
        ListTag listTag = new ListTag();
        for(int i = 0; i < key.length; i++){
            listTag.add(mapTypedValue(typed3VecTag.getCompoundOrEmpty(key[i]), floatToDouble));
        }
        return listTag;
    }



    public static CompoundTag mapTypedValue(CompoundTag typedValueTag,  boolean floatToDouble){
        CompoundTag newTypedValueTag = new CompoundTag();
        newTypedValueTag.putString("type", typedValueTag.getStringOr("_type", "").toLowerCase());
        CompoundTag dataTag = new CompoundTag();
        switch(typedValueTag.getStringOr("_type", "")){
            case "Curve":
               dataTag = typedValueTag.copy();
               dataTag.remove("_type");
               dataTag.remove("defaultValue");
               dataTag.putByte("lockControlPoint", (byte)1);
               break;
            case "Constant", "Color":
                dataTag = typedValueTag.copy();
                dataTag.remove("_type");
                if(floatToDouble && dataTag.getFloat("number").isPresent()){
                    //convert to double
                    dataTag.putDouble("number", dataTag.getFloatOr("number", 0.0F));
                }
                break;
            case "RandomConstant":
                dataTag.putFloat("a", typedValueTag.getFloatOr("a", 0.0F));
                dataTag.putFloat("b", typedValueTag.getFloatOr("b", 0.0F));
                newTypedValueTag.putString("type", "random_constant");
                break;
            case "Gradient":
                CompoundTag gradientTag = gradient(typedValueTag.getListOrEmpty("a"), typedValueTag.getListOrEmpty("r"), typedValueTag.getListOrEmpty("g"), typedValueTag.getListOrEmpty("b"));
                dataTag.put("gradientColor",  gradientTag);
                break;
            case "TextureMaterial":
                newTypedValueTag.putString("type", "texture");
                dataTag = buildBlankTexture();
                dataTag.putString("texture",  typedValueTag.getStringOr("texture", ""));
                dataTag.putFloat("discardThreshold", typedValueTag.getFloatOr("discardThreshold", 0.0F));
                break;
            case "CustomShaderMaterial":
                newTypedValueTag.putString("type", "ui_resource_material");
                dataTag.putString("resourcePath",  "built-in(built-in:circle)");
                break;

            case "RandomColor":
                newTypedValueTag.putString("type", "random_color");
                if(typedValueTag.getInt("a").isPresent()){
                    dataTag.putInt("a", typedValueTag.getIntOr("a", 0));
                }
                else{
                    dataTag.putFloat("a", typedValueTag.getFloatOr("a", 0.0F));
                }
                if(typedValueTag.getInt("b").isPresent()){
                    dataTag.putInt("b", typedValueTag.getIntOr("b", 0));
                }
                else{
                    dataTag.putFloat("b", typedValueTag.getFloatOr("b", 0.0F));
                }
                break;


        }
        newTypedValueTag.put("data", dataTag);
        return newTypedValueTag;
    }

    public static CompoundTag buildBlankTexture(){
        CompoundTag dataTag = new CompoundTag();
        dataTag.putString("texture",  "");
        dataTag.putFloat("discardThreshold", .01f);
        dataTag.putString("hdrMode", "ADDITIVE");
        ListTag hdr = new ListTag();
        hdr.add(FloatTag.ZERO);
        hdr.add(FloatTag.ZERO);
        hdr.add(FloatTag.ZERO);
        hdr.add(FloatTag.valueOf(1));
        dataTag.put("hdr", hdr);
        dataTag.put("pixelArt", new CompoundTag());
        dataTag.getCompoundOrEmpty("pixelArt").putByte("_enable", (byte)0);
        return dataTag;
    }

    public static CompoundTag gradient(ListTag oldA, ListTag oldR, ListTag oldG, ListTag oldB) {
        // Load old data from tags
        List<Vec2> rP = new ArrayList<>();
        List<Vec2> gP = new ArrayList<>();
        List<Vec2> bP = new ArrayList<>();

        for (int i = 0; i < oldR.size(); i += 2) {
            rP.add(new Vec2(oldR.getFloatOr(i, 0.0F), oldR.getFloatOr(i + 1, 0.0F)));
        }
        for (int i = 0; i < oldG.size(); i += 2) {
            gP.add(new Vec2(oldG.getFloatOr(i, 0.0F), oldG.getFloatOr(i + 1, 0.0F)));
        }
        for (int i = 0; i < oldB.size(); i += 2) {
            bP.add(new Vec2(oldB.getFloatOr(i, 0.0F), oldB.getFloatOr(i + 1, 0.0F)));
        }

        // Helper method to interpolate value at t
        BiFunction<List<Vec2>, Float, Float> get = (data, t) -> {
            if (data.isEmpty()) return 1f;
            var value = data.get(0).y;
            var found = t < data.get(0).x;
            if (!found) {
                for (int i = 0; i < data.size() - 1; i++) {
                    var s = data.get(i);
                    var e = data.get(i + 1);
                    if (t >= s.x && t <= e.x) {
                        value = s.y * (e.x - t) / (e.x - s.x) + e.y * (t - s.x) / (e.x - s.x);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                value = data.get(data.size() - 1).y;
            }
            return value;
        };


        // Create new RGB ListTag (merge r, g, b at each unique t position)
        ListTag newRGB = new ListTag();

        // Collect all unique t values from r, g, b channels
        var tValues = new TreeSet<Float>();
        for (var point : rP) tValues.add(point.x);
        for (var point : gP) tValues.add(point.x);
        for (var point : bP) tValues.add(point.x);

        // For each unique t value, sample r, g, b and create a combined point
        for (float t : tValues) {
            float r = get.apply(rP, t);
            float g = get.apply(gP, t);
            float b = get.apply(bP, t);

            newRGB.add(FloatTag.valueOf(t));
            newRGB.add(FloatTag.valueOf(r));
            newRGB.add(FloatTag.valueOf(g));
            newRGB.add(FloatTag.valueOf(b));
        }

        // Create and return new CompoundTag
        var tag = new CompoundTag();
        tag.put("a", oldA);
        tag.put("rgb", newRGB);
        return tag;
    }


    public static CompoundTag mapShape(CompoundTag shapeTag){
        CompoundTag newShapeTag = new CompoundTag();
        newShapeTag.putString("type", shapeTag.getStringOr("_type", ""));
        CompoundTag dataTag = shapeTag.copy();
        dataTag.remove("_type");
        newShapeTag.put("data", dataTag);

        return newShapeTag;
    }

    public static CompoundTag blankColorTag(){
        CompoundTag colorTag = new CompoundTag();
        colorTag.putString("type", "color");
        CompoundTag dataTag = new CompoundTag();
        dataTag.putInt("number", -1);
        colorTag.put("data", dataTag);

        return colorTag;
    }

    public static CompoundTag blankTypedTag(){
        CompoundTag typedTag = new CompoundTag();
        typedTag.putString("type", "constant");
        CompoundTag dataTag = new CompoundTag();
        dataTag.putInt("number", 1);
        typedTag.put("data", dataTag);

        return typedTag;

    }

    public static CompoundTag blankRenderer(){
        var tag = new CompoundTag();

        // cull section
        var cull = new CompoundTag();
        cull.putByte("_enable", (byte) 0);
        tag.put("cull", cull);

        tag.putInt("orderInLayer", 0);
        tag.putString("vertexSortingMode", "NONE");

        // materials section
        var materials = new CompoundTag();

        // payload list
        var payload = new ListTag();

        // First element (0) in payload
        var payloadElement0 = new CompoundTag();
        payloadElement0.putInt("cull", 1);

        // blendMode section
        var blendMode = new CompoundTag();
        blendMode.putString("srcColorFactor", "SRC_ALPHA");
        blendMode.putString("blendFunc", "ADD");
        blendMode.putInt("enableBlend", 1);
        blendMode.putString("srcAlphaFactor", "ONE");
        blendMode.putString("dstColorFactor", "ONE_MINUS_SRC_ALPHA");
        blendMode.putString("dstAlphaFactor", "ZERO");
        payloadElement0.put("blendMode", blendMode);

        // material section
        var material = new CompoundTag();
        material.putString("type", "texture");

        // data section (expanded)
        var data = new CompoundTag();
        data.putString("texture", "photon:textures/particle/circle.png");
        ListTag hdr =  new ListTag();
        hdr.add(FloatTag.ZERO);
        hdr.add(FloatTag.ZERO);
        hdr.add(FloatTag.ZERO);
        hdr.add(FloatTag.valueOf(1));
        data.put("hdr", hdr);
        data.putString("hdrMode", "ADDITIVE");
        data.put("pixelArt", new CompoundTag());
        data.putFloat("discardThreshold", 0.1f);
        material.put("data", data);

        material.putInt("depthMask", 0);
        material.putInt("depthTest", 1);
        payloadElement0.put("material", material);

        payload.add(payloadElement0);
        materials.put("payload", payload);
        materials.putInt("uid", 1);

        tag.put("materials", materials);


        return tag;


    }

    public static CompoundTag mapMaterial(CompoundTag materialTag){
        CompoundTag newMaterialTag = new CompoundTag();
        newMaterialTag.putByte("cull", materialTag.getByteOr("cull", (byte) 0));
        newMaterialTag.putByte("depthMask", materialTag.getByteOr("depthMask", (byte) 0));
        newMaterialTag.putByte("depthTest", materialTag.getByteOr("depthTest", (byte) 0));
        newMaterialTag.put("blendMode", materialTag.getCompoundOrEmpty("blendMode"));
        newMaterialTag.put("material",  mapTypedValue(materialTag.getCompoundOrEmpty("material"), false));

        return newMaterialTag;
    }


    public static CompoundTag mapLightTag(CompoundTag lightTag){
        CompoundTag newLightTag = new CompoundTag();
        newLightTag.putByte("_enable", lightTag.getByteOr("enable", (byte) 0));
        if(newLightTag.getByteOr("_enable", (byte) 0) == 1){
            newLightTag.put("blockLight", MapperUtils.mapTypedValue(lightTag.getCompoundOrEmpty("blockLight"), false));
            newLightTag.put("skyLight", MapperUtils.mapTypedValue(lightTag.getCompoundOrEmpty("skyLight"), false));
        }
        return newLightTag;
    }


    public static CompoundTag mapRendererTag(CompoundTag renderTag, CompoundTag configTag){
        CompoundTag newRendererTag = new CompoundTag();
        newRendererTag.putString("renderMode", renderTag.getStringOr("renderMode", ""));
        newRendererTag.putByte("useBlockUV",  renderTag.getByteOr("useBlockUV", (byte) 0));
        newRendererTag.putByte("shade",  renderTag.getByteOr("shade", (byte) 0));
        newRendererTag.put("cull", mapCullTag(renderTag.getCompoundOrEmpty("cull")));

        CompoundTag materialsTag = new CompoundTag();

        materialsTag.putInt("uid", 1);//Photon 1 doesn't support multiple materials on a single renderer!

        ListTag payloadTag = new ListTag();

        payloadTag.add(MapperUtils.mapMaterial(configTag.getCompoundOrEmpty("material")));

        materialsTag.put("payload", payloadTag);

        newRendererTag.put("materials",  materialsTag);

        newRendererTag.putInt("orderInLayer", 0);
        newRendererTag.putByte("useGPUInstance", (byte)0);
        ListTag pivot = new ListTag();
        pivot.add(FloatTag.ZERO);
        pivot.add(FloatTag.ZERO);
        pivot.add(FloatTag.ZERO);
        newRendererTag.put("modelPivot", pivot);
        newRendererTag.putString("vertexSortingMode", "NONE");

        return newRendererTag;
    }

    public static CompoundTag mapCullTag(CompoundTag cullTag){
        CompoundTag newCullTag = new CompoundTag();
        newCullTag.putByte("_enable", cullTag.getByteOr("enable", (byte) 0));
        if(newCullTag.getByteOr("_enable", (byte) 0) == 1){
            newCullTag.put("min", MapperUtils.mapCoordsDouble(cullTag.get("from")));
            newCullTag.put("max", MapperUtils.mapCoordsDouble(cullTag.get("to")));
        }
        return newCullTag;
    }

    public static CompoundTag mapUVTag(CompoundTag uvTag){
        CompoundTag newUVTag = new CompoundTag();
        newUVTag.putByte("_enable", uvTag.getByteOr("enable", (byte) 0));
        if(newUVTag.getByteOr("_enable", (byte) 0) == 1){
            newUVTag.putIntArray("tiles", new int[]{uvTag.getCompoundOrEmpty("tiles").getIntOr("a", 0), uvTag.getCompoundOrEmpty("tiles").getIntOr("b", 0)});
            newUVTag.put("startFrame", MapperUtils.mapTypedValue(uvTag.getCompoundOrEmpty("startFrame"), false));
            newUVTag.putFloat("cycle",  uvTag.getFloatOr("cycle", 0.0F));
            newUVTag.putString("animation",  uvTag.getStringOr("animation", ""));
            newUVTag.put("frameOverTime", MapperUtils.mapTypedValue(uvTag.getCompoundOrEmpty("frameOverTime"), false));
        }

        return newUVTag;
    }

    public static CompoundTag mapTransformTag(CompoundTag transformTag){
        CompoundTag newTransformTag = new CompoundTag();
        newTransformTag.put("_childrenId", new ListTag());
        newTransformTag.putString("_parentId", UUIDUtil.CODEC.parse(NbtOps.INSTANCE, transformTag.get("_parentId")).result().map(UUID::toString).orElse(""));
        newTransformTag.putString("id", UUIDUtil.CODEC.parse(NbtOps.INSTANCE, transformTag.get("id")).result().map(UUID::toString).orElse(""));
        newTransformTag.put("localRotation", MapperUtils.mapCoords(transformTag.get("localRotation")));
        newTransformTag.put("localScale", MapperUtils.mapCoords(transformTag.get("localScale")));
        newTransformTag.put("localPosition",  MapperUtils.mapCoords(transformTag.get("localPosition")));

        return newTransformTag;
    }
}
