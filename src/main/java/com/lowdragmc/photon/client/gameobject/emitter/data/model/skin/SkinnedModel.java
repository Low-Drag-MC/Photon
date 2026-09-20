package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** What a glTF file has to say about one model. A static model has a null skin and skeleton. */
@OnlyIn(Dist.CLIENT)
public record SkinnedModel(PhotonMesh mesh, @Nullable MeshSkin skin, @Nullable Skeleton skeleton,
                           List<AnimationClip> clips) {

    public static SkinnedModel staticModel(PhotonMesh mesh) {
        return new SkinnedModel(mesh, null, null, List.of());
    }

    public boolean isAnimated() {
        return skeleton != null && skin != null && !skin.isEmpty() && skeleton.jointCount() > 0;
    }

    /** The named clip, or null. Duplicates resolve to the first. */
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
