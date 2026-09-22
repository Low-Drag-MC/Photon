package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-vertex tangent generation — Lengyel's method. Only glTF can carry its own, so OBJ and JSON always
 * land here, as does a glTF primitive without {@code TANGENT}.
 *
 * <p>Contributions accumulate into weld groups and are orthonormalized against the vertex's normal, so
 * smooth geometry gets a continuous frame rather than one tangent per face.</p>
 *
 * <p>⚠️ One tangent per vertex, so a vertex shared by two mirrored UV islands gets one frame. Splitting
 * it is the exporter's job — glTF stores one {@code TANGENT} per vertex too.</p>
 */
final class MeshTangents {

    /** Floats per vertex in the generated array: tangent xyz + handedness. */
    static final int FLOATS_PER_TANGENT = 4;

    /** UV-area determinants below this are treated as "no UV parameterization" (see {@link #fallbackTangent}). */
    private static final float DEGENERATE_UV = 1.0e-12f;
    /** A tangent this short after Gram-Schmidt has collapsed into the normal — fall back. */
    private static final float DEGENERATE_TANGENT = 1.0e-8f;

    private MeshTangents() {
    }

    /**
     * Vertices sharing a position, a normal and a UV-winding sign average together — which welds
     * <i>beyond</i> the index buffer, keeping a smooth surface continuous across primitives.
     *
     * <p>{@code sign} keeps mirrored UV islands apart; averaging them would cancel to zero.</p>
     */
    private record WeldKey(int px, int py, int pz, int nx, int ny, int nz, int sign) {
    }

    /**
     * @param mesh the mesh to derive from; its {@link PhotonMesh#indices()} drive the iteration and its
     *             {@link PhotonMesh#spriteBounds()} normalize the UVs so atlas-baked faces weigh in
     *             comparably to raw-UV ones
     * @return {@code vertexCount * 4} floats: {@code tx,ty,tz,w} per vertex
     */
    static float[] generate(PhotonMesh mesh) {
        int vertexCount = mesh.vertexCount();
        float[] out = new float[vertexCount * FLOATS_PER_TANGENT];
        if (vertexCount == 0) {
            return out;
        }
        var geometry = mesh.geometry();
        var attributes = mesh.attributes();
        var sprites = mesh.spriteBounds();
        var indices = mesh.indices();

        // ---- pass 1: accumulate dP/du and dP/dv per weld group -------------------------------
        Map<WeldKey, Integer> groups = new HashMap<>();
        int[] groupOf = new int[vertexCount];
        Arrays.fill(groupOf, -1);
        // whether groupOf came from a triangle with a real UV gradient; see contribute()
        boolean[] claimed = new boolean[vertexCount];
        FloatArrayList accT = new FloatArrayList(); // 3 floats per group
        FloatArrayList accB = new FloatArrayList();

        // one triangle's sprite-normalized UVs (3 corners x u,v), reused
        float[] uv = new float[6];
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int a = indices[i], b = indices[i + 1], c = indices[i + 2];
            normalizedUV(attributes, sprites, a, uv, 0);
            normalizedUV(attributes, sprites, b, uv, 1);
            normalizedUV(attributes, sprites, c, uv, 2);
            accumulate(geometry, uv, a, b, c, groups, groupOf, claimed, accT, accB);
        }

        // ---- pass 2: orthonormalize each vertex against its own normal ------------------------
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int off = PhotonMesh.geometryOffset(vertex);
            // JSON normals arrive byte-quantized (packed / 127) and are never renormalized, so the
            // Gram-Schmidt projection below needs a unit normal of its own making.
            float nx = geometry[off + 3], ny = geometry[off + 4], nz = geometry[off + 5];
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

            int o = PhotonMesh.tangentOffset(vertex);
            if (groupOf[vertex] < 0) {
                // a vertex no triangle references at all
                fallbackTangent(nx, ny, nz, out, o);
                continue;
            }
            int g = groupOf[vertex] * 3;
            float tx = accT.getFloat(g), ty = accT.getFloat(g + 1), tz = accT.getFloat(g + 2);
            // Gram-Schmidt: drop whatever part of the accumulated tangent leans along the normal.
            float dot = nx * tx + ny * ty + nz * tz;
            tx -= nx * dot;
            ty -= ny * dot;
            tz -= nz * dot;

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

    /** One triangle's contribution; a zero-area UV triangle is skipped and falls back in pass 2. */
    private static void accumulate(float[] geometry, float[] uv, int a, int b, int c,
                                   Map<WeldKey, Integer> groups, int[] groupOf, boolean[] claimed,
                                   FloatArrayList accT, FloatArrayList accB) {
        int oa = PhotonMesh.geometryOffset(a);
        int ob = PhotonMesh.geometryOffset(b);
        int oc = PhotonMesh.geometryOffset(c);

        float e1x = geometry[ob] - geometry[oa];
        float e1y = geometry[ob + 1] - geometry[oa + 1];
        float e1z = geometry[ob + 2] - geometry[oa + 2];
        float e2x = geometry[oc] - geometry[oa];
        float e2y = geometry[oc + 1] - geometry[oa + 1];
        float e2z = geometry[oc + 2] - geometry[oa + 2];

        float du1 = uv[2] - uv[0], dv1 = uv[3] - uv[1];
        float du2 = uv[4] - uv[0], dv2 = uv[5] - uv[1];

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
        contribute(geometry, a, sign, groups, groupOf, claimed, accT, accB, degenerate, tx, ty, tz, bx, by, bz);
        contribute(geometry, b, sign, groups, groupOf, claimed, accT, accB, degenerate, tx, ty, tz, bx, by, bz);
        contribute(geometry, c, sign, groups, groupOf, claimed, accT, accB, degenerate, tx, ty, tz, bx, by, bz);
    }

    private static void contribute(float[] geometry, int vertex, int sign,
                                   Map<WeldKey, Integer> groups, int[] groupOf, boolean[] claimed,
                                   FloatArrayList accT, FloatArrayList accB, boolean degenerate,
                                   float tx, float ty, float tz, float bx, float by, float bz) {
        int g = group(geometry, PhotonMesh.geometryOffset(vertex), sign, groups, accT, accB);
        // first triangle with a real UV gradient wins, so the result does not depend on what is
        // appended afterwards
        if (!claimed[vertex]) {
            groupOf[vertex] = g;
            claimed[vertex] = !degenerate;
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

    /** The weld group for a vertex, allocating (and zero-filling) a fresh accumulator on first sight. */
    private static int group(float[] geometry, int off, int sign, Map<WeldKey, Integer> groups,
                             FloatArrayList accT, FloatArrayList accB) {
        var key = new WeldKey(bits(geometry[off]), bits(geometry[off + 1]), bits(geometry[off + 2]),
                bits(geometry[off + 3]), bits(geometry[off + 4]), bits(geometry[off + 5]), sign);
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

    /** One vertex's UV in its sprite's 0..1 space, so welding across differently-sized sprites does
     *  not average vectors of mismatched magnitude. Identity for raw-UV sources. */
    private static void normalizedUV(float[] attributes, float[] sprites, int vertex, float[] out, int slot) {
        int off = PhotonMesh.attributeOffset(vertex);
        float u = attributes[off];
        float v = attributes[off + 1];
        if (sprites.length > 0) {
            int s = PhotonMesh.spriteOffset(vertex);
            float u0 = sprites[s];
            float v0 = sprites[s + 1];
            float uw = sprites[s + 2] - u0;
            float vh = sprites[s + 3] - v0;
            if (uw == 0f) uw = 1f;
            if (vh == 0f) vh = 1f;
            u = (u - u0) / uw;
            v = (v - v0) / vh;
        }
        out[slot * 2] = u;
        out[slot * 2 + 1] = v;
    }

    /** A unit vector perpendicular to the normal, for vertices with no usable UV gradient. */
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
}
