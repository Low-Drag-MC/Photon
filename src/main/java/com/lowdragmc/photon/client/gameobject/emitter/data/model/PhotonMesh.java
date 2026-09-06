package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.apache.commons.lang3.tuple.Pair;
import javax.annotation.Nullable;

import java.util.List;

/**
 * Immutable geometry shared by every {@link IModelSource}: flat quads of pos(3)+uv(2)+normal(3), plus a
 * parallel tangent(3)+handedness(1) array — the source's own when it has one (glTF's {@code TANGENT}),
 * otherwise derived from the UVs on demand (see {@link MeshTangents}).
 * Positions are in <b>centered model space</b> — a JSON block model's 0..1 cube is stored as
 * -0.5..0.5, OBJ positions are the raw author space (origin = pivot). Triangles are stored as
 * degenerate quads (corner 3 == corner 2, zero-area second half) so the QUADS-mode render paths
 * and the 6-indices-per-quad EBO layout stay untouched. Consumers compare instances by identity
 * to detect cache invalidation ({@link PhotonMeshCache} hands out a new instance after reload).
 */
public final class PhotonMesh {
    public static final int FLOATS_PER_VERTEX = 8; // pos3 + uv2 + normal3
    /** Floats per corner in {@link #tangents()}: tangent xyz + handedness. */
    public static final int FLOATS_PER_TANGENT = MeshTangents.FLOATS_PER_TANGENT;
    public static final PhotonMesh EMPTY = new PhotonMesh(new float[0], new float[0], new float[0], new float[0]);

    /** quadCount * 4 * {@link #FLOATS_PER_VERTEX}: x,y,z,u,v,nx,ny,nz per corner. */
    private final float[] vertices;
    /** quadCount * 4: u0,v0,u1,v1 sprite bounds per quad ({@code 0,0,1,1} when UVs are already raw). */
    private final float[] spriteBounds;
    /** quadCount: per-face directional shade factor (all 1 when the source has no face directions). */
    private final float[] shadeBrightness;
    /**
     * quadCount * 4 * {@link #FLOATS_PER_TANGENT}: tx,ty,tz,w per corner, derived from positions + UVs
     * ({@link MeshTangents}). Kept in a parallel array rather than widening {@link #vertices} so the
     * {@code vertexOffset + component} indexing every existing consumer uses stays put. Built on first
     * {@link #tangents()} unless the source supplied its own — only the model render path asks, and only
     * when the emitter's Tangent setting is on, so a mesh used purely for emission shapes or by a
     * tangent-free emitter never pays for it.
     */
    @Nullable
    private volatile float[] tangents;

    private PhotonMesh(float[] vertices, float[] spriteBounds, float[] shadeBrightness,
                       @Nullable float[] suppliedTangents) {
        this.vertices = vertices;
        this.spriteBounds = spriteBounds;
        this.shadeBrightness = shadeBrightness;
        // A source that carries real tangents (glTF's TANGENT attribute) seeds the cache, so tangents()
        // hands those back and never generates. Null = nothing supplied them; generate on demand.
        this.tangents = suppliedTangents;
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

    /**
     * Per-corner {@code tx,ty,tz,w}; the shader rebuilds the bitangent as {@code cross(N, T) * w}.
     * The source's own tangents when it supplied them (glTF), otherwise generated on first call and
     * memoized. The generation is pure and depends only on final fields, so two threads racing to fill
     * the cache produce identical arrays — a benign race, no lock needed, and the instance stays
     * observably immutable.
     */
    public float[] tangents() {
        var cached = tangents;
        if (cached == null) {
            cached = MeshTangents.generate(vertices, spriteBounds, shadeBrightness.length);
            tangents = cached;
        }
        return cached;
    }

    public float shadeBrightness(int quad) {
        return shadeBrightness[quad];
    }

    /** Offset of {@code corner} (0..3) of {@code quad} into {@link #vertices()}. */
    public static int vertexOffset(int quad, int corner) {
        return (quad * 4 + corner) * FLOATS_PER_VERTEX;
    }

    /** Offset of {@code corner} (0..3) of {@code quad} into {@link #tangents()}. */
    public static int tangentOffset(int quad, int corner) {
        return (quad * 4 + corner) * FLOATS_PER_TANGENT;
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
            // 26.1: BakedQuad is a record with typed accessors (no more int[] + IQuadTransformer strides)
            var quad = pair.getLeft();
            var faceNormal = quad.direction().getUnitVec3f();
            for (int k = 0; k < BakedQuad.VERTEX_COUNT; k++) {
                var corner = corners[k];
                var position = quad.position(k);
                corner[0] = position.x() - 0.5f;
                corner[1] = position.y() - 0.5f;
                corner[2] = position.z() - 0.5f;
                long packedUv = quad.packedUV(k);
                corner[3] = UVPair.unpackU(packedUv);
                corner[4] = UVPair.unpackV(packedUv);
                int packedNormal = quad.bakedNormals().normal(k);
                if (BakedNormals.isUnspecified(packedNormal)) {
                    corner[5] = faceNormal.x();
                    corner[6] = faceNormal.y();
                    corner[7] = faceNormal.z();
                } else {
                    corner[5] = ((byte) packedNormal) / 127.0f;
                    corner[6] = ((byte) (packedNormal >> 8)) / 127.0f;
                    corner[7] = ((byte) (packedNormal >> 16)) / 127.0f;
                }
            }
            var sprite = quad.materialInfo().sprite();
            builder.quad(corners[0], corners[1], corners[2], corners[3],
                    sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), pair.getRight());
        }
        return builder.build();
    }

    public static final class Builder {
        private final FloatArrayList vertices = new FloatArrayList();
        private final FloatArrayList spriteBounds = new FloatArrayList();
        private final FloatArrayList shadeBrightness = new FloatArrayList();
        /** Author-supplied per-corner tangents, {@link #FLOATS_PER_TANGENT} each. Only used when EVERY
         *  face supplied one — {@link #build()} checks the count, so a mesh mixing sources (a glTF whose
         *  primitives disagree about TANGENT) falls back to generating the whole array. */
        private final FloatArrayList tangents = new FloatArrayList();

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

        /**
         * A triangle whose tangents come from the source itself rather than being derived — glTF's
         * {@code TANGENT} attribute, whose {@code vec4} (unit tangent + handedness) is already Photon's
         * convention. Each {@code t*} is {@link #FLOATS_PER_TANGENT} floats.
         */
        public Builder triangle(float[] a, float[] b, float[] c, float[] ta, float[] tb, float[] tc) {
            quad(a, b, c, c, 0f, 0f, 1f, 1f, 1f);
            tangents.addElements(tangents.size(), ta, 0, FLOATS_PER_TANGENT);
            tangents.addElements(tangents.size(), tb, 0, FLOATS_PER_TANGENT);
            tangents.addElements(tangents.size(), tc, 0, FLOATS_PER_TANGENT);
            // corner 3 repeats corner 2, exactly as the position/uv/normal copy above does
            tangents.addElements(tangents.size(), tc, 0, FLOATS_PER_TANGENT);
            return this;
        }

        public PhotonMesh build() {
            if (shadeBrightness.isEmpty()) {
                return EMPTY;
            }
            var supplied = tangents.size() == shadeBrightness.size() * 4 * FLOATS_PER_TANGENT
                    ? tangents.toFloatArray() : null;
            return new PhotonMesh(vertices.toFloatArray(), spriteBounds.toFloatArray(),
                    shadeBrightness.toFloatArray(), supplied);
        }
    }
}
