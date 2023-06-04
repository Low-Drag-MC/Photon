package com.lowdragmc.photon.gui.editor.accessor;

import com.lowdragmc.lowdraglib.syncdata.AccessorOp;
import com.lowdragmc.lowdraglib.syncdata.accessor.CustomObjectAccessor;
import com.lowdragmc.lowdraglib.syncdata.payload.ITypedPayload;
import com.lowdragmc.lowdraglib.syncdata.payload.NbtTagPayload;
import com.lowdragmc.photon.client.data.number.NumberFunction;
import net.minecraft.nbt.CompoundTag;

/**
 * @author KilaBash
 * @date 2023/6/3
 * @implNote NumberFunctionAccessor
 */
public class NumberFunctionAccessor extends CustomObjectAccessor<NumberFunction> {

    public NumberFunctionAccessor() {
        super(NumberFunction.class, true);
    }

    @Override
    public ITypedPayload<?> serialize(AccessorOp op, NumberFunction value) {
        return NbtTagPayload.of(NumberFunction.serializeWrapper(value));
    }

    @Override
    public NumberFunction deserialize(AccessorOp op, ITypedPayload<?> payload) {
        if (payload instanceof NbtTagPayload nbtTagPayload && nbtTagPayload.getPayload() instanceof CompoundTag tag) {
            return NumberFunction.deserializeWrapper(tag);
        }
        return null;
    }

}
