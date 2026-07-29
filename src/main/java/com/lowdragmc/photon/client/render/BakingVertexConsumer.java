package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * A recording {@link VertexConsumer}: bakes the exact call sequence into compact op/payload streams
 * during extraction, {@link #replay}s it into the real buffer during feature rendering (M1 decision
 * D2 = extract-time baking — particle state is only read in the extract phase, like vanilla's
 * QuadParticleRenderState). Reused frame-to-frame via {@link #reset()}.
 */
public final class BakingVertexConsumer implements VertexConsumer {

    private static final int OP_ADD_VERTEX = 0;   // 3 floats
    private static final int OP_COLOR4 = 1;       // 4 floats (r,g,b,a as 0..255 ints stored as floats)
    private static final int OP_COLOR_PACKED = 2; // 1 int, inline in the OP stream (argb)
    private static final int OP_UV = 3;           // 2 floats
    private static final int OP_UV1 = 4;          // 2 floats (ints stored as floats)
    private static final int OP_UV2 = 5;          // 2 floats (ints stored as floats)
    private static final int OP_NORMAL = 6;       // 3 floats
    private static final int OP_LINE_WIDTH = 7;   // 1 float

    private final IntArrayList ops = new IntArrayList();
    private final FloatArrayList data = new FloatArrayList();
    private int vertexCount;

    public void reset() {
        ops.clear();
        data.clear();
        vertexCount = 0;
    }

    public boolean isEmpty() {
        return vertexCount == 0;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public void replay(VertexConsumer buffer) {
        int cursor = 0;
        for (int i = 0; i < ops.size(); i++) {
            switch (ops.getInt(i)) {
                case OP_ADD_VERTEX -> buffer.addVertex(data.getFloat(cursor++), data.getFloat(cursor++), data.getFloat(cursor++));
                case OP_COLOR4 -> buffer.setColor((int) data.getFloat(cursor++), (int) data.getFloat(cursor++),
                        (int) data.getFloat(cursor++), (int) data.getFloat(cursor++));
                case OP_COLOR_PACKED -> buffer.setColor(ops.getInt(++i)); // argb inline in the op stream
                case OP_UV -> buffer.setUv(data.getFloat(cursor++), data.getFloat(cursor++));
                case OP_UV1 -> buffer.setUv1((int) data.getFloat(cursor++), (int) data.getFloat(cursor++));
                case OP_UV2 -> buffer.setUv2((int) data.getFloat(cursor++), (int) data.getFloat(cursor++));
                case OP_NORMAL -> buffer.setNormal(data.getFloat(cursor++), data.getFloat(cursor++), data.getFloat(cursor++));
                case OP_LINE_WIDTH -> buffer.setLineWidth(data.getFloat(cursor++));
            }
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        ops.add(OP_ADD_VERTEX);
        data.add(x);
        data.add(y);
        data.add(z);
        vertexCount++;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        ops.add(OP_COLOR4);
        data.add(r);
        data.add(g);
        data.add(b);
        data.add(a);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        // the argb int rides in the op stream: float storage is lossy for bit patterns in NaN
        // space (intBitsToFloat may quiet signaling NaNs, silently corrupting the color)
        ops.add(OP_COLOR_PACKED);
        ops.add(color);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        ops.add(OP_UV);
        data.add(u);
        data.add(v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        ops.add(OP_UV1);
        data.add(u);
        data.add(v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        ops.add(OP_UV2);
        data.add(u);
        data.add(v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        ops.add(OP_NORMAL);
        data.add(x);
        data.add(y);
        data.add(z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        ops.add(OP_LINE_WIDTH);
        data.add(width);
        return this;
    }
}
