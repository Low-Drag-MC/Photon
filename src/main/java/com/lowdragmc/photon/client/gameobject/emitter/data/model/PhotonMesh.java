package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Immutable geometry shared by every {@link IModelSource}: <b>indexed triangles</b> over three
 * parallel vertex streams, plus the index buffer and the per-face bookkeeping the CPU draw path
 * needs.
 *
 * <h2>Three streams, split by what changes them</h2>
 *
 * <pre>
 *   geometry    position 3 + normal 3     6 floats   &lt;- the only thing a deformation rewrites
 *   attribute   uv 2 + shade 1            3 floats   &lt;- never deformed, never per-instance
 *   tangent     tangent 3 + handedness 1  4 floats   &lt;- the source's own, or generated on demand
 * </pre>
 *
 * <p>The saving in bytes is not the point. The point is that an <b>external provider can supply the
 * geometry stream alone</b> — a skinning pass produces positions and normals and physically does not
 * have the UVs — so a dynamic mesh replaces one stream and leaves the other two untouched. Unreal
 * splits its skeletal vertex buffers the same way and for the same reason
 * ({@code FSkeletalMeshLODRenderData} embeds {@code FStaticMeshVertexBuffers} verbatim and adds a
 * skin-weight buffer beside it). Interleaving everything into one array — which is what this class
 * used to do — makes zero-copy injection impossible, because the provider would have to hand back
 * data it does not own and cannot know.
 *
 * <h2>Indexed triangles, not degenerate quads</h2>
 *
 * <p>This class used to store every face as a quad, a triangle being a quad whose fourth corner
 * repeats its third. That cost three ways, all of them per frame and per particle instance: 4 vertex
 * shader invocations per triangle instead of 3, one zero-area primitive per triangle through setup
 * and culling, and — because nothing was ever welded — a glTF's 2600 shared vertices expanded into
 * 20000 corners, which is 7.7x the skinning and upload work a deformation would have to do.
 *
 * <p>So faces are triangles in {@link #indices()} and vertices are welded by whatever the source
 * format already knows: glTF keeps the file's own index buffer (which is also the numbering its
 * {@code JOINTS_0}/{@code WEIGHTS_0} are keyed by), OBJ welds by the {@code v/vt/vn} triplet, and a
 * baked JSON model welds nothing because its faces genuinely share no corners.
 *
 * <p>⚠️ <b>{@link #quadPaired(int)} exists because the CPU draw path draws into a QUADS-mode
 * buffer.</b> Vanilla's {@code VertexConsumer} for Photon's render types wants four vertices per
 * primitive, so that path emits {@code a,b,c,c} for a triangle — and would emit <i>two</i> degenerate
 * quads for what the author drew as one quad, doubling the vertex work for every block-model particle
 * on the default (non-instanced) path. A source that authored real quads records them here and the
 * CPU path emits them unsplit.
 *
 * <p>Positions are in <b>centered model space</b> — a JSON block model's 0..1 cube is stored as
 * -0.5..0.5, OBJ and glTF positions are the raw author space (origin = pivot). Consumers compare
 * instances by identity to detect cache invalidation ({@link PhotonMeshCache} hands out a new
 * instance after reload).
 */
@OnlyIn(Dist.CLIENT)
public final class PhotonMesh {
    /** Floats per vertex in {@link #geometry()}: position xyz + normal xyz. */
    public static final int FLOATS_PER_GEOMETRY = 6;
    /** Floats per vertex in {@link #attributes()}: u, v, per-face shade brightness. */
    public static final int FLOATS_PER_ATTRIBUTE = 3;
    /** Floats per vertex in {@link #tangents()}: tangent xyz + handedness. */
    public static final int FLOATS_PER_TANGENT = MeshTangents.FLOATS_PER_TANGENT;
    /** Floats per vertex in {@link #spriteBounds()}: u0, v0, u1, v1. */
    public static final int FLOATS_PER_SPRITE = 4;

    public static final PhotonMesh EMPTY = new PhotonMesh(new float[0], new float[0], new float[0],
            new int[0], new boolean[0], new float[0]);

    /** vertexCount * {@link #FLOATS_PER_GEOMETRY}. */
    private final float[] geometry;
    /** vertexCount * {@link #FLOATS_PER_ATTRIBUTE}. */
    private final float[] attributes;
    /**
     * vertexCount * {@link #FLOATS_PER_SPRITE}, or <b>empty</b> when the source's UVs are already
     * raw 0..1 — which is every source but baked JSON. Empty means "identity", so the raw-UV
     * sources neither store nor remap anything.
     */
    private final float[] spriteBounds;
    /** triangleCount * 3 vertex indices. */
    private final int[] indices;
    /** triangleCount: this triangle and the next are the two halves of one authored quad. */
    private final boolean[] quadPaired;
    /**
     * vertexCount * {@link #FLOATS_PER_TANGENT}, the source's own when it supplied them (glTF's
     * {@code TANGENT}), otherwise derived from the UVs on first {@link #tangents()} and memoized —
     * only the model render path asks, and only when the emitter's Tangent setting is on, so a mesh
     * used purely for emission shapes never pays for it.
     */
    @Nullable
    private volatile float[] tangents;

    private PhotonMesh(float[] geometry, float[] attributes, float[] spriteBounds, int[] indices,
                       boolean[] quadPaired, @Nullable float[] suppliedTangents) {
        this.geometry = geometry;
        this.attributes = attributes;
        this.spriteBounds = spriteBounds;
        this.indices = indices;
        this.quadPaired = quadPaired;
        this.tangents = suppliedTangents;
    }

    public int vertexCount() {
        return attributes.length / FLOATS_PER_ATTRIBUTE;
    }

    public int triangleCount() {
        return quadPaired.length;
    }

    public boolean isEmpty() {
        return quadPaired.length == 0;
    }

    public float[] geometry() {
        return geometry;
    }

    public float[] attributes() {
        return attributes;
    }

    /** Per-vertex {@code u0,v0,u1,v1}; <b>empty</b> when the UVs are raw (see the field). */
    public float[] spriteBounds() {
        return spriteBounds;
    }

    public int[] indices() {
        return indices;
    }

    /**
     * Whether triangle {@code triangle} and {@code triangle + 1} are one authored quad — read only
     * by the CPU draw path, see the class javadoc.
     */
    public boolean quadPaired(int triangle) {
        return quadPaired[triangle];
    }

    /**
     * Per-vertex {@code tx,ty,tz,w}; the shader rebuilds the bitangent as {@code cross(N, T) * w}.
     * The source's own tangents when it supplied them (glTF), otherwise generated on first call and
     * memoized. The generation is pure and depends only on final fields, so two threads racing to
     * fill the cache produce identical arrays — a benign race, no lock needed, and the instance
     * stays observably immutable.
     */
    public float[] tangents() {
        var cached = tangents;
        if (cached == null) {
            cached = MeshTangents.generate(this);
            tangents = cached;
        }
        return cached;
    }

    /** Offset of {@code vertex} into {@link #geometry()}. */
    public static int geometryOffset(int vertex) {
        return vertex * FLOATS_PER_GEOMETRY;
    }

    /** Offset of {@code vertex} into {@link #attributes()}. */
    public static int attributeOffset(int vertex) {
        return vertex * FLOATS_PER_ATTRIBUTE;
    }

    /** Offset of {@code vertex} into {@link #tangents()}. */
    public static int tangentOffset(int vertex) {
        return vertex * FLOATS_PER_TANGENT;
    }

    /** Offset of {@code vertex} into {@link #spriteBounds()}. */
    public static int spriteOffset(int vertex) {
        return vertex * FLOATS_PER_SPRITE;
    }

    /**
     * Decode baked quads (with their per-face shade factor) into a mesh. Positions are shifted by
     * -0.5 into centered space; sprite bounds are recorded so {@code useBlockUV=false} can remap
     * atlas UVs back to 0..1 at consumption time.
     */
    public static PhotonMesh fromBakedQuads(List<Pair<BakedQuad, Float>> quads) {
        var builder = new Builder();
        var corners = new float[4][8];
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
            var sprite = quad.getSprite();
            if (points == 3) {
                builder.triangle(corners[0], corners[1], corners[2], pair.getRight())
                        .sprite(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
            } else {
                builder.quad(corners[0], corners[1], corners[2], corners[3], pair.getRight())
                        .sprite(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
            }
        }
        return builder.build();
    }

    /**
     * Accumulates vertices and triangles. Two ways in, because the sources differ in what they
     * already know:
     *
     * <ul>
     *   <li><b>Welded</b> — {@link #vertex} returns an index, {@link #triangle(int, int, int)}
     *       references it. glTF and OBJ take this path: they carry an indexing of their own and
     *       throwing it away is what used to cost 7.7x.</li>
     *   <li><b>Unwelded</b> — {@link #triangle(float[], float[], float[], float)} and
     *       {@link #quad} add fresh vertices per face. Baked JSON takes this path; its faces share
     *       no corners, so welding them would only cost a hash lookup each.</li>
     * </ul>
     *
     * <p>{@link #sprite} and {@link #tangent} apply to the vertices of the most recently added face,
     * which is what keeps the per-face formats (a JSON quad's atlas sprite) from having to thread an
     * index around.
     */
    public static final class Builder {
        private final FloatArrayList geometry = new FloatArrayList();
        private final FloatArrayList attributes = new FloatArrayList();
        private final FloatArrayList spriteBounds = new FloatArrayList();
        private final FloatArrayList tangents = new FloatArrayList();
        private final IntArrayList indices = new IntArrayList();
        private final BooleanArrayList quadPaired = new BooleanArrayList();
        /** Vertices of the face added last, for {@link #sprite}/{@link #tangent} to reach back at. */
        private int lastFaceStart = -1;
        private int lastFaceCount;
        /** Any non-identity sprite bound at all; when none, the array is dropped entirely. */
        private boolean anySprite;
        /** How many vertices got an author-supplied tangent — all or nothing, see {@link #build()}. */
        private int taggedTangents;

        /**
         * Adds one vertex and returns its index. Sprite bounds default to the identity
         * {@code 0,0,1,1} and the tangent to a sentinel that {@link #build()} discards unless every
         * vertex overrode it.
         */
        public int vertex(float x, float y, float z, float u, float v,
                          float nx, float ny, float nz, float shade) {
            int index = attributes.size() / FLOATS_PER_ATTRIBUTE;
            geometry.add(x);
            geometry.add(y);
            geometry.add(z);
            geometry.add(nx);
            geometry.add(ny);
            geometry.add(nz);
            attributes.add(u);
            attributes.add(v);
            attributes.add(shade);
            spriteBounds.add(0f);
            spriteBounds.add(0f);
            spriteBounds.add(1f);
            spriteBounds.add(1f);
            for (int i = 0; i < FLOATS_PER_TANGENT; i++) {
                tangents.add(0f);
            }
            return index;
        }

        /** One triangle over already-added vertices. */
        public Builder triangle(int a, int b, int c) {
            indices.add(a);
            indices.add(b);
            indices.add(c);
            quadPaired.add(false);
            lastFaceStart = -1;
            lastFaceCount = 0;
            return this;
        }

        /**
         * One authored quad over already-added vertices, as the triangle pair {@code a,b,c} +
         * {@code c,d,a} — the same split the index buffer has always used — flagged so the CPU path
         * can put it back together ({@link PhotonMesh#quadPaired}).
         */
        public Builder quad(int a, int b, int c, int d) {
            indices.add(a);
            indices.add(b);
            indices.add(c);
            quadPaired.add(true);
            indices.add(c);
            indices.add(d);
            indices.add(a);
            quadPaired.add(false);
            lastFaceStart = -1;
            lastFaceCount = 0;
            return this;
        }

        /** A triangle of three fresh vertices; each corner is {@code x,y,z,u,v,nx,ny,nz}. */
        public Builder triangle(float[] a, float[] b, float[] c, float shade) {
            int base = addCorner(a, shade);
            addCorner(b, shade);
            addCorner(c, shade);
            triangle(base, base + 1, base + 2);
            lastFaceStart = base;
            lastFaceCount = 3;
            return this;
        }

        /** A triangle of three fresh vertices, unshaded. */
        public Builder triangle(float[] a, float[] b, float[] c) {
            return triangle(a, b, c, 1f);
        }

        /** An authored quad of four fresh vertices; each corner is {@code x,y,z,u,v,nx,ny,nz}. */
        public Builder quad(float[] a, float[] b, float[] c, float[] d, float shade) {
            int base = addCorner(a, shade);
            addCorner(b, shade);
            addCorner(c, shade);
            addCorner(d, shade);
            quad(base, base + 1, base + 2, base + 3);
            lastFaceStart = base;
            lastFaceCount = 4;
            return this;
        }

        /**
         * Index of the first vertex of the face added last by one of the fresh-vertex overloads, or
         * {@code -1} when the last face referenced existing vertices. Lets a caller reach back at the
         * vertices it just implicitly created — {@link #tangent} needs an index and the unwelded
         * overloads do not hand one out.
         */
        public int lastFaceStart() {
            return lastFaceStart;
        }

        /** The atlas sprite the last face's UVs came from. Raw-UV sources never call this. */
        public Builder sprite(float u0, float v0, float u1, float v1) {
            if (lastFaceCount == 0) {
                return this; // no face to attach it to; don't allocate an all-identity array either
            }
            anySprite = true;
            for (int i = 0; i < lastFaceCount; i++) {
                int off = spriteOffset(lastFaceStart + i);
                spriteBounds.set(off, u0);
                spriteBounds.set(off + 1, v0);
                spriteBounds.set(off + 2, u1);
                spriteBounds.set(off + 3, v1);
            }
            return this;
        }

        /**
         * An author-supplied tangent for one vertex — glTF's {@code TANGENT}, whose {@code vec4}
         * (unit tangent + handedness) is already Photon's convention. A mesh whose primitives
         * disagree about {@code TANGENT} falls back to generating the whole array, see
         * {@link #build()}.
         */
        public Builder tangent(int vertex, float tx, float ty, float tz, float w) {
            int off = tangentOffset(vertex);
            tangents.set(off, tx);
            tangents.set(off + 1, ty);
            tangents.set(off + 2, tz);
            tangents.set(off + 3, w);
            taggedTangents++;
            return this;
        }

        private int addCorner(float[] corner, float shade) {
            return vertex(corner[0], corner[1], corner[2], corner[3], corner[4],
                    corner[5], corner[6], corner[7], shade);
        }

        public PhotonMesh build() {
            if (quadPaired.isEmpty()) {
                return EMPTY;
            }
            int vertexCount = attributes.size() / FLOATS_PER_ATTRIBUTE;
            // all or nothing: one primitive without TANGENT makes the whole mesh generate, which is
            // also what the glTF spec asks implementations to do
            var supplied = taggedTangents == vertexCount ? tangents.toFloatArray() : null;
            return new PhotonMesh(geometry.toFloatArray(), attributes.toFloatArray(),
                    anySprite ? spriteBounds.toFloatArray() : new float[0],
                    indices.toIntArray(), quadPaired.toBooleanArray(), supplied);
        }
    }
}
