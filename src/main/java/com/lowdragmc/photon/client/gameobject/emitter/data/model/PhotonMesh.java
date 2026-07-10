package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Immutable geometry shared by every {@link IModelSource}: flat quads of pos(3)+uv(2)+normal(3).
 * Positions are in <b>centered model space</b> — a JSON block model's 0..1 cube is stored as
 * -0.5..0.5, OBJ positions are the raw author space (origin = pivot). Triangles are stored as
 * degenerate quads (corner 3 == corner 2, zero-area second half) so the QUADS-mode render paths
 * and the 6-indices-per-quad EBO layout stay untouched. Consumers compare instances by identity
 * to detect cache invalidation ({@link PhotonMeshCache} hands out a new instance after reload).
 */
@OnlyIn(Dist.CLIENT)
public final class PhotonMesh {
    public static final int FLOATS_PER_VERTEX = 8; // pos3 + uv2 + normal3
    public static final PhotonMesh EMPTY = new PhotonMesh(new float[0], new float[0], new float[0]);

    /** quadCount * 4 * {@link #FLOATS_PER_VERTEX}: x,y,z,u,v,nx,ny,nz per corner. */
    private final float[] vertices;
    /** quadCount * 4: u0,v0,u1,v1 sprite bounds per quad ({@code 0,0,1,1} when UVs are already raw). */
    private final float[] spriteBounds;
    /** quadCount: per-face directional shade factor (all 1 when the source has no face directions). */
    private final float[] shadeBrightness;

    private PhotonMesh(float[] vertices, float[] spriteBounds, float[] shadeBrightness) {
        this.vertices = vertices;
        this.spriteBounds = spriteBounds;
        this.shadeBrightness = shadeBrightness;
    }

    public int quadCount() {
        return shadeBrightness.length;
    }

    public boolean isEmpty() {
        return shadeBrightness.length == 0;
    }

    public float[] vertices() {
        return vertices;
    }

    public float[] spriteBounds() {
        return spriteBounds;
    }

    public float shadeBrightness(int quad) {
        return shadeBrightness[quad];
    }

    /** Offset of {@code corner} (0..3) of {@code quad} into {@link #vertices()}. */
    public static int vertexOffset(int quad, int corner) {
        return (quad * 4 + corner) * FLOATS_PER_VERTEX;
    }

    /** True when the quad is a degenerate triangle (corner 3 repeats corner 2). */
    public boolean isTriangle(int quad) {
        int c2 = vertexOffset(quad, 2);
        int c3 = vertexOffset(quad, 3);
        return vertices[c2] == vertices[c3]
                && vertices[c2 + 1] == vertices[c3 + 1]
                && vertices[c2 + 2] == vertices[c3 + 2];
    }

    /**
     * Decode baked quads (with their per-face shade factor) into a mesh. Positions are shifted by
     * -0.5 into centered space; sprite bounds are recorded so {@code useBlockUV=false} can remap
     * atlas UVs back to 0..1 at consumption time.
     */
    public static PhotonMesh fromBakedQuads(List<Pair<BakedQuad, Float>> quads) {
        var builder = new Builder();
        var corners = new float[4][FLOATS_PER_VERTEX];
        for (var pair : quads) {
            var quad = pair.getLeft();
            int[] data = quad.getVertices();
            int points = Math.min(data.length / IQuadTransformer.STRIDE, 4);
            if (points < 3) continue;
            for (int k = 0; k < points; k++) {
                int off = k * IQuadTransformer.STRIDE;
                var corner = corners[k];
                corner[0] = Float.intBitsToFloat(data[off + IQuadTransformer.POSITION]) - 0.5f;
                corner[1] = Float.intBitsToFloat(data[off + IQuadTransformer.POSITION + 1]) - 0.5f;
                corner[2] = Float.intBitsToFloat(data[off + IQuadTransformer.POSITION + 2]) - 0.5f;
                corner[3] = Float.intBitsToFloat(data[off + IQuadTransformer.UV0]);
                corner[4] = Float.intBitsToFloat(data[off + IQuadTransformer.UV0 + 1]);
                int packedNormal = data[off + IQuadTransformer.NORMAL];
                corner[5] = ((byte) packedNormal) / 127.0f;
                corner[6] = ((byte) (packedNormal >> 8)) / 127.0f;
                corner[7] = ((byte) (packedNormal >> 16)) / 127.0f;
            }
            if (points == 3) {
                System.arraycopy(corners[2], 0, corners[3], 0, FLOATS_PER_VERTEX);
            }
            var sprite = quad.getSprite();
            builder.quad(corners[0], corners[1], corners[2], corners[3],
                    sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), pair.getRight());
        }
        return builder.build();
    }

    public static final class Builder {
        private final FloatArrayList vertices = new FloatArrayList();
        private final FloatArrayList spriteBounds = new FloatArrayList();
        private final FloatArrayList shadeBrightness = new FloatArrayList();

        /** Each corner is {@link #FLOATS_PER_VERTEX} floats: x,y,z,u,v,nx,ny,nz. */
        public Builder quad(float[] a, float[] b, float[] c, float[] d,
                            float u0, float v0, float u1, float v1, float brightness) {
            vertices.addElements(vertices.size(), a, 0, FLOATS_PER_VERTEX);
            vertices.addElements(vertices.size(), b, 0, FLOATS_PER_VERTEX);
            vertices.addElements(vertices.size(), c, 0, FLOATS_PER_VERTEX);
            vertices.addElements(vertices.size(), d, 0, FLOATS_PER_VERTEX);
            spriteBounds.add(u0);
            spriteBounds.add(v0);
            spriteBounds.add(u1);
            spriteBounds.add(v1);
            shadeBrightness.add(brightness);
            return this;
        }

        /** One triangle stored as a degenerate quad (corner 3 == corner 2), raw 0..1 UVs, no shade. */
        public Builder triangle(float[] a, float[] b, float[] c) {
            return quad(a, b, c, c, 0f, 0f, 1f, 1f, 1f);
        }

        public PhotonMesh build() {
            if (shadeBrightness.isEmpty()) {
                return EMPTY;
            }
            return new PhotonMesh(vertices.toFloatArray(), spriteBounds.toFloatArray(), shadeBrightness.toFloatArray());
        }
    }
}
