package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The M1 extract-time baking recorder must replay the exact call sequence, including raw packed
 *  color bits (argb values that fall into float NaN space must survive the float-array storage). */
public class BakingVertexConsumerTest {

    /** Replay target that stringifies every call for exact sequence comparison. */
    private record CallLog(List<String> calls) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            calls.add("v(%s,%s,%s)".formatted(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            calls.add("c4(%d,%d,%d,%d)".formatted(r, g, b, a));
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            calls.add("c(%08x)".formatted(color));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            calls.add("uv(%s,%s)".formatted(u, v));
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            calls.add("uv1(%d,%d)".formatted(u, v));
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            calls.add("uv2(%d,%d)".formatted(u, v));
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            calls.add("n(%s,%s,%s)".formatted(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            calls.add("w(%s)".formatted(width));
            return this;
        }
    }

    @Test
    public void replaysExactCallSequence() {
        var baked = new BakingVertexConsumer();
        baked.addVertex(1, 2, 3).setColor(255, 128, 0, 64).setUv(0.25f, 0.75f).setUv2(15, 15).setNormal(0, 1, 0);
        baked.addVertex(4, 5, 6).setColor(0xFFC08040).setUv(0f, 1f).setUv2(0, 240).setNormal(0, 0, 1);
        assertEquals(2, baked.vertexCount());

        var log = new CallLog(new ArrayList<>());
        baked.replay(log);
        assertEquals(List.of(
                "v(1.0,2.0,3.0)", "c4(255,128,0,64)", "uv(0.25,0.75)", "uv2(15,15)", "n(0.0,1.0,0.0)",
                "v(4.0,5.0,6.0)", "c(ffc08040)", "uv(0.0,1.0)", "uv2(0,240)", "n(0.0,0.0,1.0)"
        ), log.calls());
    }

    @Test
    public void packedColorSurvivesNanSpace() {
        // 0x7FC00001 is a signaling-NaN float bit pattern — the round trip must preserve raw bits
        var baked = new BakingVertexConsumer();
        baked.addVertex(0, 0, 0).setColor(0x7FC00001);

        var log = new CallLog(new ArrayList<>());
        baked.replay(log);
        assertEquals(List.of("v(0.0,0.0,0.0)", "c(7fc00001)"), log.calls());
    }

    @Test
    public void resetRecycles() {
        var baked = new BakingVertexConsumer();
        baked.addVertex(1, 1, 1).setColor(-1);
        baked.reset();
        assertTrue(baked.isEmpty());
        var log = new CallLog(new ArrayList<>());
        baked.replay(log);
        assertTrue(log.calls().isEmpty());
    }
}
