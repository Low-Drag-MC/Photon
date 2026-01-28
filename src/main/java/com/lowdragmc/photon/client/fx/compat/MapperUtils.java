package com.lowdragmc.photon.client.fx.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public class MapperUtils {

    public static ListTag mapCoords(Tag coordTag){
        CompoundTag compoundTag = (CompoundTag)coordTag;
        ListTag newCoordTag = new ListTag();
        newCoordTag.add(FloatTag.valueOf(compoundTag.getFloat("x")));
        newCoordTag.add(FloatTag.valueOf(compoundTag.getFloat("y")));
        newCoordTag.add(FloatTag.valueOf(compoundTag.getFloat("z")));
        if (compoundTag.contains("w")) {
            newCoordTag.add(FloatTag.valueOf(compoundTag.getFloat("w")));

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
    public static ListTag mapTyped3Vec(CompoundTag typed3VecTag, String[] key){
        if(key == null){
            key = new String[]{"x", "y", "z"};
        }
        ListTag listTag = new ListTag();
        for(int i = 0; i < key.length; i++){
            listTag.add(mapTypedValue(typed3VecTag.getCompound(key[i])));
        }
        return listTag;
    }

    public static CompoundTag mapTypedValue(CompoundTag typedValueTag){
        CompoundTag newTypedValueTag = new CompoundTag();
        newTypedValueTag.putString("type", typedValueTag.getString("_type"));
        CompoundTag dataTag = new CompoundTag();
        switch(typedValueTag.getString("_type")){
            case "constant":
                dataTag.putInt("number", typedValueTag.getInt("number"));
                break;
            case "Curve":
               dataTag = typedValueTag.copy();
               dataTag.remove("type");
               break;

        }
        newTypedValueTag.put("data", dataTag);
        return newTypedValueTag;
    }


    public static CompoundTag mapShape(CompoundTag shapeTag){
        CompoundTag newShapeTag = new CompoundTag();
        return newShapeTag;
    }
}
