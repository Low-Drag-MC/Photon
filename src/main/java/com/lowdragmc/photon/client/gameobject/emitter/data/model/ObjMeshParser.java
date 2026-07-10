package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny standalone Wavefront <b>.obj</b> parser — vertex positions, UVs and normals, everything
 * else defaulted (no MTL; textures come from the material system). NeoForge's {@code ObjLoader}
 * is wired to the resource-pack/atlas pipeline and can't produce raw runtime geometry, hence this.
 * Handles {@code v}/{@code vt}/{@code vn}, the {@code v} / {@code v/vt} / {@code v//vn} /
 * {@code v/vt/vn} face forms, negative (relative) indices, multiple {@code o}/{@code g} objects
 * (OBJ indices are global, so all merge into one mesh), and <b>ear-clips</b> n-gon faces
 * (concave-safe, with a fan fallback). Missing UVs default to {@code (0,0)}; missing normals are
 * computed per face (Newell's method). Positions are used <b>raw</b> (no centering/scaling), so
 * size/origin are exactly what the author modelled. UV's V axis is optionally flipped
 * ({@code flipV}) since OBJ's bottom-left UV origin is upside-down relative to the MC sampler.
 */
@OnlyIn(Dist.CLIENT)
public final class ObjMeshParser {

    private ObjMeshParser() {
    }

    public static PhotonMesh parse(InputStream in, boolean flipV) throws IOException {
        return parseText(new String(in.readAllBytes(), StandardCharsets.UTF_8), flipV);
    }

    /** Parse OBJ text into a mesh of degenerate-quad triangles. Package-visible for tests. */
    static PhotonMesh parseText(String text, boolean flipV) {
        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<int[]> faceVerts = new ArrayList<>(); // reused per face: {posIdx, uvIdx, nrmIdx} (0-based, -1 = none)
        var builder = new PhotonMesh.Builder();

        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] tok = line.split("\\s+");
            switch (tok[0]) {
                case "v" -> {
                    if (tok.length >= 4) positions.add(new float[]{f(tok[1]), f(tok[2]), f(tok[3])});
                }
                case "vt" -> uvs.add(new float[]{tok.length >= 2 ? f(tok[1]) : 0f, tok.length >= 3 ? f(tok[2]) : 0f});
                case "vn" -> {
                    if (tok.length >= 4) normals.add(new float[]{f(tok[1]), f(tok[2]), f(tok[3])});
                }
                case "f" -> {
                    faceVerts.clear();
                    for (int i = 1; i < tok.length; i++) {
                        int[] ref = ref(tok[i], positions.size(), uvs.size(), normals.size());
                        if (ref != null) faceVerts.add(ref);
                    }
                    triangulate(faceVerts, positions, uvs, normals, flipV, builder);
                }
                default -> {
                    // ignore o/g/s/usemtl/mtllib/l/p/etc.
                }
            }
        }
        return builder.build();
    }

    /** Triangulate one (already-resolved) face via ear-clipping; fan fallback if it fails. */
    private static void triangulate(List<int[]> face, List<float[]> positions, List<float[]> uvs,
                                    List<float[]> normals, boolean flipV, PhotonMesh.Builder builder) {
        int n = face.size();
        if (n < 3) return;
        float[][] p = new float[n][];
        for (int i = 0; i < n; i++) {
            p[i] = pos(positions, face.get(i)[0]);
            if (p[i] == null) return; // invalid face — skip whole face
        }
        float[] polyN = polygonNormal(p);
        if (n == 3) {
            emitTri(face, p, uvs, normals, flipV, polyN, 0, 1, 2, builder);
            return;
        }
        int[] order = earClip(p, polyN);
        if (order == null) { // degenerate / self-intersecting — fall back to a simple fan
            for (int i = 1; i + 1 < n; i++) emitTri(face, p, uvs, normals, flipV, polyN, 0, i, i + 1, builder);
            return;
        }
        for (int i = 0; i + 2 < order.length; i += 3) {
            emitTri(face, p, uvs, normals, flipV, polyN, order[i], order[i + 1], order[i + 2], builder);
        }
    }

    private static void emitTri(List<int[]> face, float[][] p, List<float[]> uvs, List<float[]> normals,
                                boolean flipV, float[] polyN, int a, int b, int c,
                                PhotonMesh.Builder builder) {
        builder.triangle(
                vertex(p[a], uvOf(face.get(a)[1], uvs, flipV), normal(normals, face.get(a)[2], polyN)),
                vertex(p[b], uvOf(face.get(b)[1], uvs, flipV), normal(normals, face.get(b)[2], polyN)),
                vertex(p[c], uvOf(face.get(c)[1], uvs, flipV), normal(normals, face.get(c)[2], polyN)));
    }

    private static float[] vertex(float[] p, float[] uv, float[] n) {
        return new float[]{p[0], p[1], p[2], uv[0], uv[1], n[0], n[1], n[2]};
    }

    // ---- ear clipping (3D polygon projected onto its best-fit plane) -------------------------

    /**
     * Ear-clip a planar polygon into triangles. Returns triangle corner indices (3 per triangle, into
     * {@code 0..n-1}), or {@code null} if no valid ear decomposition is found (caller falls back to a fan).
     */
    private static int[] earClip(float[][] p, float[] normal) {
        int n = p.length;
        // 2D basis in the polygon plane.
        float[] axis = Math.abs(normal[0]) > 0.9f ? new float[]{0f, 1f, 0f} : new float[]{1f, 0f, 0f};
        float[] u = normalize(cross(axis, normal));
        float[] v = cross(normal, u); // unit (normal ⟂ u, both unit)
        float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = dot(p[i], u);
            y[i] = dot(p[i], v);
        }
        // Work on an index list wound CCW (positive signed area) so a convex corner has a positive cross.
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += (double) x[i] * y[j] - (double) x[j] * y[i];
        }
        List<Integer> idx = new ArrayList<>(n);
        if (area < 0) for (int i = n - 1; i >= 0; i--) idx.add(i);
        else for (int i = 0; i < n; i++) idx.add(i);

        int[] out = new int[(n - 2) * 3];
        int oi = 0;
        int guard = 0;
        while (idx.size() > 3) {
            if (guard++ > n * n) return null; // no progress — bail to fan
            boolean clipped = false;
            int m = idx.size();
            for (int a = 0; a < m; a++) {
                int ia = idx.get((a + m - 1) % m), ib = idx.get(a), ic = idx.get((a + 1) % m);
                if (isEar(x, y, idx, ia, ib, ic)) {
                    out[oi++] = ia;
                    out[oi++] = ib;
                    out[oi++] = ic;
                    idx.remove(a);
                    clipped = true;
                    break;
                }
            }
            if (!clipped) return null;
        }
        out[oi++] = idx.get(0);
        out[oi++] = idx.get(1);
        out[oi] = idx.get(2);
        return out;
    }

    /** True if triangle (ia,ib,ic) is a convex corner of the CCW polygon containing no other vertex. */
    private static boolean isEar(float[] x, float[] y, List<Integer> idx, int ia, int ib, int ic) {
        double cross = (double) (x[ib] - x[ia]) * (y[ic] - y[ia]) - (double) (y[ib] - y[ia]) * (x[ic] - x[ia]);
        if (cross <= 0) return false; // reflex or degenerate corner
        for (int k : idx) {
            if (k == ia || k == ib || k == ic) continue;
            if (pointInTriangle(x[k], y[k], x[ia], y[ia], x[ib], y[ib], x[ic], y[ic])) return false;
        }
        return true;
    }

    private static boolean pointInTriangle(float px, float py, float ax, float ay,
                                           float bx, float by, float cx, float cy) {
        double d1 = sign(px, py, ax, ay, bx, by);
        double d2 = sign(px, py, bx, by, cx, cy);
        double d3 = sign(px, py, cx, cy, ax, ay);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos); // all same sign (allowing on-edge) → inside
    }

    private static double sign(float px, float py, float ax, float ay, float bx, float by) {
        return (double) (px - bx) * (ay - by) - (double) (ax - bx) * (py - by);
    }

    /** Newell's-method normal of a polygon (normalized; {@code +Z} for a degenerate polygon). */
    private static float[] polygonNormal(float[][] p) {
        float nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < p.length; i++) {
            float[] cur = p[i], nxt = p[(i + 1) % p.length];
            nx += (cur[1] - nxt[1]) * (cur[2] + nxt[2]);
            ny += (cur[2] - nxt[2]) * (cur[0] + nxt[0]);
            nz += (cur[0] - nxt[0]) * (cur[1] + nxt[1]);
        }
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-6f) return new float[]{0f, 0f, 1f};
        return new float[]{nx / len, ny / len, nz / len};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] normalize(float[] a) {
        float len = (float) Math.sqrt(dot(a, a));
        if (len < 1.0e-6f) return new float[]{1f, 0f, 0f};
        return new float[]{a[0] / len, a[1] / len, a[2] / len};
    }

    // ---- face-token resolution ---------------------------------------------------------------

    /** Resolve one {@code v[/vt[/vn]]} face token to 0-based indices ({@code -1} = absent), or {@code null} if invalid. */
    private static int[] ref(String token, int posCount, int uvCount, int nrmCount) {
        String[] parts = token.split("/", -1);
        int p = resolve(parts[0], posCount);
        if (p < 0) return null;
        int vt = parts.length >= 2 ? resolve(parts[1], uvCount) : -1;
        int vn = parts.length >= 3 ? resolve(parts[2], nrmCount) : -1;
        return new int[]{p, vt, vn};
    }

    /** OBJ index (1-based, negatives relative to the end) → 0-based, or {@code -1} if blank / out of range. */
    private static int resolve(String s, int count) {
        if (s == null || s.isEmpty()) return -1;
        int idx;
        try {
            idx = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        int zero = idx < 0 ? count + idx : idx - 1;
        return (zero >= 0 && zero < count) ? zero : -1;
    }

    private static float[] pos(List<float[]> positions, int i) {
        return (i >= 0 && i < positions.size()) ? positions.get(i) : null;
    }

    private static float[] uvOf(int i, List<float[]> uvs, boolean flipV) {
        float[] uv = (i >= 0 && i < uvs.size()) ? uvs.get(i) : new float[]{0f, 0f};
        return flipV ? new float[]{uv[0], 1f - uv[1]} : uv;
    }

    private static float[] normal(List<float[]> normals, int i, float[] fallback) {
        return (i >= 0 && i < normals.size()) ? normals.get(i) : fallback;
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }
}
