package com.lowdragmc.photon.gui.editor.accessor;

import com.lowdragmc.lowdraglib.syncdata.AccessorOp;
import com.lowdragmc.lowdraglib.syncdata.accessor.CustomObjectAccessor;
import com.lowdragmc.lowdraglib.syncdata.payload.ITypedPayload;
import com.lowdragmc.lowdraglib.syncdata.payload.NbtTagPayload;
import com.lowdragmc.photon.client.emitter.data.shape.IShape;
import net.minecraft.nbt.CompoundTag;

/**
 * @author KilaBash
 * @date 2023/6/3
 * @implNote IShapeAccessor
 */
public class IShapeAccessor extends CustomObjectAccessor<IShape> {

    public IShapeAccessor() {
        super(IShape.class, true);
    }

    @Override
    public ITypedPayload<?> serialize(AccessorOp op, IShape value) {
        return NbtTagPayload.of(IShape.serializeWrapper(value));
    }

    @Override
    public IShape deserialize(AccessorOp op, ITypedPayload<?> payload) {
        if (payload instanceof NbtTagPayload nbtTagPayload && nbtTagPayload.getPayload() instanceof CompoundTag tag) {
            return IShape.deserializeWrapper(tag);
        }
        return null;
    }

}
