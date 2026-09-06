package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-corner tangent generation for {@link PhotonMesh} — Lengyel's method (the classic
 * "Computing Tangent Space Basis Vectors", the same construction MikkTSpace is built on).
 *
 * <p>Only glTF can carry a tangent of its own, and only when the exporter wrote one: the Wavefront spec
 * is {@code v}/{@code vt}/{@code vn} only, and a {@code BakedQuad} stores nothing but a byte-packed face
 * normal. So OBJ and JSON always land here, as does a glTF primitive without {@code TANGENT} — which is
 * what that spec asks implementations to do anyway.</p>
 *
 * <p>Each triangle contributes {@code dP/du} and {@code dP/dv}; contributions are accumulated into
 * <b>weld groups</b> and only then orthonormalized against the corner's own normal, so smooth-shaded
 * geometry (sphere, capsule, cylinder) gets a continuous frame instead of one hard tangent per face.
 * The result is {@code (tx, ty, tz, w)} per corner, where {@code w} is the handedness the shader needs
 * to rebuild the bitangent as {@code cross(N, T) * w}.</p>
 */
final class MeshTangents {

    /** Floats per corner in the generated array: tangent xyz + handedness. */
    static final int FLOATS_PER_TANGENT = 4;

    /** UV-area determinants below this are treated as "no UV parameterization" (see {@link #fallbackTangent}). */
    private static final float DEGENERATE_UV = 1.0e-12f;
    /** A tangent this short after Gram-Schmidt has collapsed into the normal — fall back. */
    private static final float DEGENERATE_TANGENT = 1.0e-8f;

    private MeshTangents() {
    }

    /**
     * Corners that share a position, a normal <b>and</b> a UV-winding sign average together.
     *
     * <p>Position and normal are keyed on exact bits rather than a quantization bucket: both OBJ and
     * baked-JSON geometry produce bit-identical floats for shared corners (the same decimal token, or
     * the same int unpacked twice), and a bucket would split neighbours that straddle a boundary.</p>
     *
     * <p>{@code sign} is the sign of the triangle's UV-area determinant. Two faces meeting at the same
     * position/normal with <b>mirrored</b> UVs carry opposite-facing tangents; averaging them cancels
     * to zero. Keying on the sign keeps mirrored islands in separate groups.</p>
     */
    private record WeldKey(int px, int py, int pz, int nx, int ny, int nz, int sign) {
    }

    /**
     * @param vertices     the mesh's interleaved corners, {@link PhotonMesh#FLOATS_PER_VERTEX} floats each
     * @param spriteBounds per-quad {@code u0,v0,u1,v1}; UVs are normalized by these before differentiating
     *                     so atlas-baked quads weigh in comparably to raw-UV ones
     * @param quadCount    number of quads (a triangle is a degenerate quad, corner 3 == corner 2)
     * @return {@code quadCount * 4 * 4} floats: {@code tx,ty,tz,w} per corner
     */
    static float[] generate(float[] vertices, float[] spriteBounds, int quadCount) {
        int cornerCount = quadCount * 4;
        float[] out = new float[cornerCount * FLOATS_PER_TANGENT];
        if (quadCount == 0) {
            return out;
        }

        // ---- pass 1: accumulate dP/du and dP/dv per weld group -------------------------------
        Map<WeldKey, Integer> groups = new HashMap<>();
        int[] groupOf = new int[cornerCount];
        Arrays.fill(groupOf, -1);
        FloatArrayList accT = new FloatArrayList(); // 3 floats per group
        FloatArrayList accB = new FloatArrayList();

        // Sprite-normalized UVs of the quad currently being processed (4 corners x u,v).
        float[] uv = new float[8];
        for (int quad = 0; quad < quadCount; quad++) {
            normalizedUVs(vertices, spriteBounds, quad, uv);
            accumulate(vertices, uv, quad, 0, 1, 2, groups, groupOf, accT, accB);
            if (!isDegenerate(vertices, quad)) {
                accumulate(vertices, uv, quad, 2, 3, 0, groups, groupOf, accT, accB);
            } else {
                // Corner 3 is a bitwise copy of corner 2, so it welds into the same group; assigning it
                // here keeps every corner mapped without contributing a zero-area triangle.
                groupOf[quad * 4 + 3] = groupOf[quad * 4 + 2];
            }
        }

        // ---- pass 2: orthonormalize each corner against its own normal ------------------------
        for (int corner = 0; corner < cornerCount; corner++) {
            int off = corner * PhotonMesh.FLOATS_PER_VERTEX;
            // JSON normals arrive byte-quantized (packed / 127) and are never renormalized, so the
            // Gram-Schmidt projection below needs a unit normal of its own making.
            float nx = vertices[off + 5], ny = vertices[off + 6], nz = vertices[off + 7];
            float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nLen < DEGENERATE_TANGENT) {
                nx = 0f;
                ny = 1f;
                nz = 0f;
            } else {
                nx /= nLen;
                ny /= nLen;
                nz /= nLen;
            }

            int g = Math.max(groupOf[corner], 0) * 3;
            float tx = accT.getFloat(g), ty = accT.getFloat(g + 1), tz = accT.getFloat(g + 2);
            // Gram-Schmidt: drop whatever part of the accumulated tangent leans along the normal.
            float dot = nx * tx + ny * ty + nz * tz;
            tx -= nx * dot;
            ty -= ny * dot;
            tz -= nz * dot;

            int o = corner * FLOATS_PER_TANGENT;
            float tLen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tLen < DEGENERATE_TANGENT) {
                fallbackTangent(nx, ny, nz, out, o);
                continue;
            }
            tx /= tLen;
            ty /= tLen;
            tz /= tLen;

            // handedness: does cross(N, T) agree with the accumulated dP/dv?
            float bx = accB.getFloat(g), by = accB.getFloat(g + 1), bz = accB.getFloat(g + 2);
            float cx = ny * tz - nz * ty;
            float cy = nz * tx - nx * tz;
            float cz = nx * ty - ny * tx;
            out[o] = tx;
            out[o + 1] = ty;
            out[o + 2] = tz;
            out[o + 3] = (cx * bx + cy * by + cz * bz) < 0f ? -1f : 1f;
        }
        return out;
    }

    /**
     * One triangle's contribution. UVs are the quad's sprite-normalized pair; positions come straight
     * from the interleaved array. A zero-area UV triangle carries no parameterization, so it is skipped
     * entirely — its corners still get a group (an empty one) and fall back in pass 2.
     */
    private static void accumulate(float[] vertices, float[] uv, int quad, int a, int b, int c,
                                   Map<WeldKey, Integer> groups, int[] groupOf,
                                   FloatArrayList accT, FloatArrayList accB) {
        int oa = PhotonMesh.vertexOffset(quad, a);
        int ob = PhotonMesh.vertexOffset(quad, b);
        int oc = PhotonMesh.vertexOffset(quad, c);

        float e1x = vertices[ob] - vertices[oa];
        float e1y = vertices[ob + 1] - vertices[oa + 1];
        float e1z = vertices[ob + 2] - vertices[oa + 2];
        float e2x = vertices[oc] - vertices[oa];
        float e2y = vertices[oc + 1] - vertices[oa + 1];
        float e2z = vertices[oc + 2] - vertices[oa + 2];

        float du1 = uv[b * 2] - uv[a * 2], dv1 = uv[b * 2 + 1] - uv[a * 2 + 1];
        float du2 = uv[c * 2] - uv[a * 2], dv2 = uv[c * 2 + 1] - uv[a * 2 + 1];

        float det = du1 * dv2 - du2 * dv1;
        boolean degenerate = Math.abs(det) < DEGENERATE_UV;
        int sign = degenerate ? 0 : (det < 0f ? -1 : 1);

        float tx = 0f, ty = 0f, tz = 0f, bx = 0f, by = 0f, bz = 0f;
        if (!degenerate) {
            float r = 1f / det;
            tx = (e1x * dv2 - e2x * dv1) * r;
            ty = (e1y * dv2 - e2y * dv1) * r;
            tz = (e1z * dv2 - e2z * dv1) * r;
            bx = (e2x * du1 - e1x * du2) * r;
            by = (e2y * du1 - e1y * du2) * r;
            bz = (e2z * du1 - e1z * du2) * r;
        }

        // three explicit calls rather than a loop over a temp array: this runs per triangle, and a
        // 100k-tri mesh would otherwise allocate 100k throwaway int[3]s
        contribute(vertices, quad, a, sign, groups, groupOf, accT, accB, degenerate, tx, ty, tz, bx, by, bz);
        contribute(vertices, quad, b, sign, groups, groupOf, accT, accB, degenerate, tx, ty, tz, bx, by, bz);
        contribute(vertices, quad, c, sign, groups, groupOf, accT, accB, degenerate, tx, ty, tz, bx, by, bz);
    }

    private static void contribute(float[] vertices, int quad, int corner, int sign,
                                   Map<WeldKey, Integer> groups, int[] groupOf,
                                   FloatArrayList accT, FloatArrayList accB, boolean degenerate,
                                   float tx, float ty, float tz, float bx, float by, float bz) {
        int g = group(vertices, PhotonMesh.vertexOffset(quad, corner), sign, groups, accT, accB);
        int i = quad * 4 + corner;
        // A corner shared by both of a quad's triangles is visited twice. A UV-degenerate triangle
        // contributes nothing, so it must not steal the corner away from a good one it already joined —
        // otherwise the output would depend on which triangle came first.
        if (!degenerate || groupOf[i] < 0) {
            groupOf[i] = g;
        }
        if (degenerate) return;
        int acc = g * 3;
        accT.set(acc, accT.getFloat(acc) + tx);
        accT.set(acc + 1, accT.getFloat(acc + 1) + ty);
        accT.set(acc + 2, accT.getFloat(acc + 2) + tz);
        accB.set(acc, accB.getFloat(acc) + bx);
        accB.set(acc + 1, accB.getFloat(acc + 1) + by);
        accB.set(acc + 2, accB.getFloat(acc + 2) + bz);
    }

    /** The weld group for a corner, allocating (and zero-filling) a fresh accumulator on first sight. */
    private static int group(float[] vertices, int off, int sign, Map<WeldKey, Integer> groups,
                             FloatArrayList accT, FloatArrayList accB) {
        var key = new WeldKey(bits(vertices[off]), bits(vertices[off + 1]), bits(vertices[off + 2]),
                bits(vertices[off + 5]), bits(vertices[off + 6]), bits(vertices[off + 7]), sign);
        var existing = groups.get(key);
        if (existing != null) {
            return existing;
        }
        int index = groups.size();
        groups.put(key, index);
        for (int k = 0; k < 3; k++) {
            accT.add(0f);
            accB.add(0f);
        }
        return index;
    }

    /** Exact float identity, with {@code -0.0} folded onto {@code 0.0} so the two hash alike. */
    private static int bits(float f) {
        return Float.floatToIntBits(f == 0f ? 0f : f);
    }

    /**
     * The quad's UVs remapped into its sprite's 0..1 space. The atlas -> sprite remap is a positive
     * per-axis scale, so it cannot rotate a tangent — but it does scale one, and welding corners off
     * differently-sized sprites would then average vectors of mismatched magnitude. Normalizing here
     * makes generation independent of {@code useBlockUV} and of the sprite's atlas footprint. Raw-UV
     * (OBJ) sources record {@code 0,0,1,1}, so this is an identity for them.
     */
    private static void normalizedUVs(float[] vertices, float[] spriteBounds, int quad, float[] out) {
        float u0 = spriteBounds[quad * 4];
        float v0 = spriteBounds[quad * 4 + 1];
        float uw = spriteBounds[quad * 4 + 2] - u0;
        float vh = spriteBounds[quad * 4 + 3] - v0;
        if (uw == 0f) uw = 1f;
        if (vh == 0f) vh = 1f;
        for (int corner = 0; corner < 4; corner++) {
            int off = PhotonMesh.vertexOffset(quad, corner);
            out[corner * 2] = (vertices[off + 3] - u0) / uw;
            out[corner * 2 + 1] = (vertices[off + 4] - v0) / vh;
        }
    }

    /**
     * An arbitrary unit vector perpendicular to the normal, for corners with no usable UV gradient —
     * an OBJ face with no {@code vt} (every corner defaults to {@code (0,0)}) is the common case, not a
     * rare one. Picks the basis axis least aligned with the normal, matching {@code ObjMeshParser.earClip}.
     */
    private static void fallbackTangent(float nx, float ny, float nz, float[] out, int o) {
        float ax = Math.abs(nx) > 0.9f ? 0f : 1f;
        float ay = Math.abs(nx) > 0.9f ? 1f : 0f;
        // cross(axis, normal)
        float tx = ay * nz;
        float ty = -ax * nz;
        float tz = ax * ny - ay * nx;
        float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < DEGENERATE_TANGENT) {
            out[o] = 1f;
            out[o + 1] = 0f;
            out[o + 2] = 0f;
        } else {
            out[o] = tx / len;
            out[o + 1] = ty / len;
            out[o + 2] = tz / len;
        }
        out[o + 3] = 1f;
    }

    /** Mirrors {@link PhotonMesh#isTriangle(int)} on the raw array: corner 3 repeats corner 2. */
    private static boolean isDegenerate(float[] vertices, int quad) {
        int c2 = PhotonMesh.vertexOffset(quad, 2);
        int c3 = PhotonMesh.vertexOffset(quad, 3);
        return vertices[c2] == vertices[c3]
                && vertices[c2 + 1] == vertices[c3 + 1]
                && vertices[c2 + 2] == vertices[c3 + 2];
    }
}
