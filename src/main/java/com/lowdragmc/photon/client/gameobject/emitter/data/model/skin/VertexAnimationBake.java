package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * A clip sampled into a flat table of positions — one row per frame, one texel per vertex — so the
 * vertex shader can pose a model by itself and every particle can be at a different frame.
 *
 * <p>Deforming per particle is not an option at particle counts: a thousand copies of a five-thousand
 * vertex model is eighty megabytes of deformed geometry a frame. Baking the poses once trades that for
 * a texture and makes the per-frame cost zero.</p>
 *
 * <p>The normal rides in the texel's fourth channel, octahedral-encoded at 12 bits a component, so it
 * costs nothing: that channel was padding. Worst case is under a sixteenth of a degree, against the
 * ~1.5 degrees of the byte normals vanilla ships.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VertexAnimationBake {

    /** RGBA32F texels: xyz = position, w = the packed normal. */
    public static final int FLOATS_PER_TEXEL = 4;
    /** Quantization of each octahedral component; 4095 * 4096 + 4095 is exactly representable in a float. */
    private static final float OCT_SCALE = 4095f;
    private static final float OCT_STRIDE = 4096f;
    /** Refuse to bake past this many texels (32 MiB); a model this size is not a particle. */
    public static final int MAX_TEXELS = 2_000_000;

    private VertexAnimationBake() {
    }

    /**
     * A unit normal as one float. MIRRORED BY {@code photon_unpack_normal} in particle.glsl — including
     * the sign convention, which is {@code >= 0 ? 1 : -1} and NOT {@code sign()}, whose answer at zero
     * is zero and would collapse a component.
     */
    public static float packNormal(float nx, float ny, float nz) {
        float sum = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
        if (sum < 1.0e-20f || !Float.isFinite(sum)) {
            return packNormal(0f, 0f, 1f);
        }
        float px = nx / sum;
        float py = ny / sum;
        if (nz < 0f) {
            float folded = (1f - Math.abs(py)) * (px >= 0f ? 1f : -1f);
            py = (1f - Math.abs(px)) * (py >= 0f ? 1f : -1f);
            px = folded;
        }
        int qx = Math.round(Math.min(1f, Math.max(0f, px * 0.5f + 0.5f)) * OCT_SCALE);
        int qy = Math.round(Math.min(1f, Math.max(0f, py * 0.5f + 0.5f)) * OCT_SCALE);
        return qx * OCT_STRIDE + qy;
    }

    /** The decode, for testing the round trip; the shader has its own copy of this. */
    public static void unpackNormal(float packed, float[] out) {
        float qy = packed % OCT_STRIDE;
        float qx = (float) Math.floor(packed / OCT_STRIDE);
        float ex = qx / OCT_SCALE * 2f - 1f;
        float ey = qy / OCT_SCALE * 2f - 1f;
        float z = 1f - Math.abs(ex) - Math.abs(ey);
        if (z < 0f) {
            float folded = (1f - Math.abs(ey)) * (ex >= 0f ? 1f : -1f);
            ey = (1f - Math.abs(ex)) * (ey >= 0f ? 1f : -1f);
            ex = folded;
        }
        float length = (float) Math.sqrt(ex * ex + ey * ey + z * z);
        out[0] = ex / length;
        out[1] = ey / length;
        out[2] = z / length;
    }

    /**
     * @param frames how many poses to sample; frame {@code f} is the clip at {@code duration * f / frames},
     *               so wrapping past the last frame continues a looping clip
     * @return {@code frames * vertexCount * }{@link #FLOATS_PER_TEXEL} floats, or null when the model
     *         cannot be posed or the table would be too large
     */
    @Nullable
    public static float[] bake(SkinnedModel model, @Nullable AnimationClip clip, int frames) {
        if (!model.isAnimated() || frames <= 0) {
            return null;
        }
        var mesh = model.mesh();
        var skin = model.skin();
        if (skin == null) return null;
        int vertexCount = mesh.vertexCount();
        long texels = (long) frames * vertexCount;
        if (texels <= 0 || texels > MAX_TEXELS) {
            return null;
        }

        var out = new float[(int) texels * FLOATS_PER_TEXEL];
        var deformer = new SkinDeformer(model.skeleton());
        var pose = new float[vertexCount * PhotonMesh.FLOATS_PER_GEOMETRY];
        float duration = clip == null ? 0f : clip.duration();

        for (int frame = 0; frame < frames; frame++) {
            deformer.pose(clip, duration * frame / frames);
            deformer.deform(mesh, skin, pose);
            int base = frame * vertexCount * FLOATS_PER_TEXEL;
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                int from = vertex * PhotonMesh.FLOATS_PER_GEOMETRY;
                int to = base + vertex * FLOATS_PER_TEXEL;
                out[to] = pose[from];
                out[to + 1] = pose[from + 1];
                out[to + 2] = pose[from + 2];
                out[to + 3] = packNormal(pose[from + 3], pose[from + 4], pose[from + 5]);
            }
        }
        return out;
    }
}
