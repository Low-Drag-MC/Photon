package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Everything a glTF file has to say about one model: the bind-pose geometry, and — when it is skinned —
 * who moves it and how.
 *
 * <p>A static model is a {@code SkinnedModel} too, with {@link #skin()} and {@link #skeleton()} null. That
 * keeps one loader and one cache for both instead of a parallel set, and {@link #isAnimated()} is the one
 * question a caller has to ask.</p>
 *
 * @param mesh     bind-pose geometry, in the numbering the skin's per-vertex data is indexed by
 * @param skin     per-vertex joints and weights, or null for a model nothing deforms
 * @param skeleton the joint hierarchy, or null likewise
 * @param clips    every animation in the file, in file order; may be empty even for a skinned model
 */
@OnlyIn(Dist.CLIENT)
public record SkinnedModel(PhotonMesh mesh, @Nullable MeshSkin skin, @Nullable Skeleton skeleton,
                           List<AnimationClip> clips) {

    public static SkinnedModel staticModel(PhotonMesh mesh) {
        return new SkinnedModel(mesh, null, null, List.of());
    }

    /** Whether this model can be posed at all — it has a skeleton and something for it to move. */
    public boolean isAnimated() {
        return skeleton != null && skin != null && !skin.isEmpty() && skeleton.jointCount() > 0;
    }

    /** The named clip, or null. Names are the file's; duplicates resolve to the first. */
    @Nullable
    public AnimationClip clip(String name) {
        for (var clip : clips) {
            if (clip.name().equals(name)) return clip;
        }
        return null;
    }

    /** The clip at {@code index}, clamped into range, or null when there are none. */
    @Nullable
    public AnimationClip clipAt(int index) {
        if (clips.isEmpty()) return null;
        return clips.get(Math.max(0, Math.min(index, clips.size() - 1)));
    }

    public List<String> clipNames() {
        return clips.stream().map(AnimationClip::name).toList();
    }
}
