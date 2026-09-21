package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.Photon;
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
import java.util.function.Supplier;

/**
 * Immutable geometry shared by every {@link IModelSource}: indexed triangles over three vertex
 * streams — geometry (position + normal), attribute (uv + shade) and tangent.
 *
 * <p>The streams are split because an external provider can only supply the geometry one: a skinning
 * pass produces positions and normals and does not have the UVs. Unreal splits its skeletal vertex
 * buffers the same way.</p>
 *
 * <p>Vertices are welded by whatever the source format already knows: glTF keeps the file's own
 * indices (the numbering its {@code JOINTS_0}/{@code WEIGHTS_0} use), OBJ welds by the
 * {@code v/vt/vn} triplet, baked JSON welds nothing.</p>
 *
 * <p>⚠️ {@link #quadPaired(int)} exists because the CPU draw path draws into a QUADS-mode buffer: it
 * emits {@code a,b,c,c} for a triangle, and would emit two of those for an authored quad.</p>
 *
 * <p>Positions are in centered model space — a JSON block model's 0..1 cube is -0.5..0.5, OBJ and
 * glTF are raw author space. Consumers compare instances by identity to detect invalidation.</p>
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
    /** Empty when the UVs are already raw 0..1, which is every source but baked JSON. */
    private final float[] spriteBounds;
    /** triangleCount * 3 vertex indices. */
    private final int[] indices;
    /** triangleCount: this triangle and the next are the two halves of one authored quad. */
    private final boolean[] quadPaired;
    /** The source's own (glTF's {@code TANGENT}) or derived from the UVs on first {@link #tangents()}. */
    @Nullable
    private volatile float[] tangents;
    /** A deforming source's tangents for this pose, asked for only if something draws with them — the
     *  fallback is a weld-map rebuild over the whole mesh, which is not a per-frame price. */
    @Nullable
    private final Supplier<float[]> deformedTangents;
    /** {@code this} for a built mesh, the original for a {@link #withGeometry} derivative. */
    private final PhotonMesh topology;
    /** Which revision of that topology's geometry this instance holds; see {@link #withGeometry}. */
    private final long geometryRevision;

    private PhotonMesh(float[] geometry, float[] attributes, float[] spriteBounds, int[] indices,
                       boolean[] quadPaired, @Nullable float[] suppliedTangents) {
        this.geometry = geometry;
        this.attributes = attributes;
        this.spriteBounds = spriteBounds;
        this.indices = indices;
        this.quadPaired = quadPaired;
        this.tangents = suppliedTangents;
        this.deformedTangents = null;
        this.topology = this;
        this.geometryRevision = 0L;
    }

    /** Derivative constructor: same topology, different geometry. */
    private PhotonMesh(PhotonMesh topology, float[] geometry, @Nullable Supplier<float[]> tangents,
                       long revision) {
        this.geometry = geometry;
        this.attributes = topology.attributes;
        this.spriteBounds = topology.spriteBounds;
        this.indices = topology.indices;
        this.quadPaired = topology.quadPaired;
        this.deformedTangents = tangents;
        this.topology = topology.topology;
        this.geometryRevision = revision;
    }

    /**
     * This mesh's geometry replaced, sharing every stream a deformation does not touch. The result
     * reports the same {@link #topology()}, so consumers keep their cached buffers and re-read only
     * the geometry — the mechanism behind {@link IDynamicMesh}.
     *
     * @param tangents asked for this pose's deformed tangents, at most once and only if something draws
     *                 with them; null, or a null result, derives them from the UVs instead. ⚠️ Deriving
     *                 is a weld-map rebuild over the whole mesh, so a dynamic mesh drawn with tangents
     *                 should supply them.
     * @param revision must differ whenever the contents do
     */
    public PhotonMesh withGeometry(float[] geometry, @Nullable Supplier<float[]> tangents, long revision) {
        if (geometry.length != this.geometry.length) {
            throw new IllegalArgumentException("geometry stream is " + geometry.length
                    + " floats but this topology has " + vertexCount() + " vertices ("
                    + this.geometry.length + " floats)");
        }
        return new PhotonMesh(this, geometry, tangents, revision);
    }

    /** Compare by identity for "still the same model", as opposed to "still the same pose". */
    public PhotonMesh topology() {
        return topology;
    }

    /** Which revision of {@link #topology()}'s geometry this holds; 0 for a statically built mesh. */
    public long geometryRevision() {
        return geometryRevision;
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

    /** Whether this triangle and the next are one authored quad; read only by the CPU draw path. */
    public boolean quadPaired(int triangle) {
        return quadPaired[triangle];
    }

    /** Per-vertex {@code tx,ty,tz,w}; the shader rebuilds the bitangent as {@code cross(N, T) * w}.
     *  Generated on first call; two threads racing produce identical arrays, so no lock. */
    public float[] tangents() {
        var cached = tangents;
        if (cached == null) {
            var supplied = deformedTangents == null ? null : deformedTangents.get();
            // a wrong-length array from an external provider would be read past the end of, in a
            // glBufferSubData; deriving instead is wrong-looking, not unsafe
            cached = supplied != null && supplied.length == vertexCount() * FLOATS_PER_TANGENT
                    ? supplied : MeshTangents.generate(this);
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
     * Accumulates vertices and triangles, welded ({@link #vertex} returns an index — glTF and OBJ) or
     * unwelded ({@link #triangle(float[], float[], float[], float)} adds fresh vertices — baked JSON,
     * whose faces share no corners anyway). {@link #sprite} and {@link #tangent} apply to the last
     * face added.
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

        /** Adds one vertex and returns its index. */
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

        /** An authored quad as the pair {@code a,b,c} + {@code c,d,a}, flagged for the CPU path. */
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

        /** First vertex of the last fresh-vertex face, or -1; {@link #tangent} needs an index. */
        public int lastFaceStart() {
            return lastFaceStart;
        }

        /** The atlas sprite the last face's UVs came from. Raw-UV sources never call this. */
        public Builder sprite(float u0, float v0, float u1, float v1) {
            if (lastFaceCount == 0) {
                return this;
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

        /** glTF's {@code TANGENT}, whose vec4 is already Photon's convention. All or nothing. */
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
            dropUnreferenceableFaces(vertexCount);
            if (quadPaired.isEmpty()) {
                return EMPTY;
            }
            var supplied = taggedTangents == vertexCount ? tangents.toFloatArray() : null;
            return new PhotonMesh(geometry.toFloatArray(), attributes.toFloatArray(),
                    anySprite ? spriteBounds.toFloatArray() : new float[0],
                    indices.toIntArray(), quadPaired.toBooleanArray(), supplied);
        }

        /**
         * Drop any face naming a vertex the mesh does not have. A file can say so, and these indices go
         * straight into a GL element buffer — where out of range is an out-of-bounds read on the GPU
         * rather than an exception. Paired halves of a quad go together, or the pair flag would mark the
         * wrong face.
         */
        private void dropUnreferenceableFaces(int vertexCount) {
            boolean clean = true;
            for (int i = 0; i < indices.size(); i++) {
                int index = indices.getInt(i);
                if (index < 0 || index >= vertexCount) {
                    clean = false;
                    break;
                }
            }
            if (clean) {
                return;
            }

            var keptIndices = new IntArrayList(indices.size());
            var keptPaired = new BooleanArrayList(quadPaired.size());
            int dropped = 0;
            int face = 0;
            while (face < quadPaired.size()) {
                int faces = quadPaired.getBoolean(face) && face + 1 < quadPaired.size() ? 2 : 1;
                boolean ok = true;
                for (int i = face * 3; ok && i < (face + faces) * 3 && i < indices.size(); i++) {
                    int index = indices.getInt(i);
                    ok = index >= 0 && index < vertexCount;
                }
                if (ok) {
                    for (int f = 0; f < faces; f++) {
                        keptIndices.add(indices.getInt((face + f) * 3));
                        keptIndices.add(indices.getInt((face + f) * 3 + 1));
                        keptIndices.add(indices.getInt((face + f) * 3 + 2));
                        keptPaired.add(quadPaired.getBoolean(face + f));
                    }
                } else {
                    dropped += faces;
                }
                face += faces;
            }
            Photon.LOGGER.warn("dropped {} of {} faces naming a vertex outside 0..{}",
                    dropped, quadPaired.size(), vertexCount - 1);
            indices.clear();
            indices.addAll(keptIndices);
            quadPaired.clear();
            quadPaired.addAll(keptPaired);
        }
    }
}
