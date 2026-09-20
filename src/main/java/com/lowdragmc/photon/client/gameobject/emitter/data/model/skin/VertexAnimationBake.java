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
 * <p>⚠️ Positions only. The normals stay the rest pose's, so lighting does not follow the deformation.
 * Storing them would double the table for something a particle material rarely uses.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VertexAnimationBake {

    /** RGBA32F texels: xyz = position, w unused. */
    public static final int FLOATS_PER_TEXEL = 4;
    /** Refuse to bake past this many texels (32 MiB); a model this size is not a particle. */
    public static final int MAX_TEXELS = 2_000_000;

    private VertexAnimationBake() {
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
            }
        }
        return out;
    }
}
