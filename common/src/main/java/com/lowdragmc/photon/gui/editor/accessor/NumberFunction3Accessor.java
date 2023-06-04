package com.lowdragmc.photon.gui.editor.accessor;

import com.lowdragmc.lowdraglib.syncdata.AccessorOp;
import com.lowdragmc.lowdraglib.syncdata.accessor.CustomObjectAccessor;
import com.lowdragmc.lowdraglib.syncdata.payload.ITypedPayload;
import com.lowdragmc.lowdraglib.syncdata.payload.NbtTagPayload;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import com.lowdragmc.photon.client.data.number.NumberFunction3;
import net.minecraft.nbt.CompoundTag;

/**
 * @author KilaBash
 * @date 2023/6/3
 * @implNote NumberFunction3Accessor
 */
public class NumberFunction3Accessor extends CustomObjectAccessor<NumberFunction3> {

    public NumberFunction3Accessor() {
        super(NumberFunction3.class, true);
    }

    @Override
    public ITypedPayload<?> serialize(AccessorOp op, NumberFunction3 value) {
        var tag = new CompoundTag();
        tag.put("x", NumberFunction.serializeWrapper(value.x));
        tag.put("y", NumberFunction.serializeWrapper(value.y));
        tag.put("z", NumberFunction.serializeWrapper(value.z));
        return NbtTagPayload.of(tag);
    }

    @Override
    public NumberFunction3 deserialize(AccessorOp op, ITypedPayload<?> payload) {
        if (payload instanceof NbtTagPayload nbtTagPayload && nbtTagPayload.getPayload() instanceof CompoundTag tag) {
            return new NumberFunction3(NumberFunction.deserializeWrapper(tag.getCompound("x")),
                    NumberFunction.deserializeWrapper(tag.getCompound("y")),
                    NumberFunction.deserializeWrapper(tag.getCompound("z")));
        }
        return null;
    }

}
