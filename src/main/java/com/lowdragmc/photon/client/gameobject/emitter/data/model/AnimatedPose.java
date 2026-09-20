package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.AnimationClip;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinDeformer;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One deformed pose, shared by everything asking for the same animation at the same instant.
 *
 * <p>The reason this is a cache and not a field on the source: an emitter's renderer config owns its own
 * {@link AnimatedGltfModelSource}, so five emitters playing one clip on one model are five sources — and
 * would be five identical deformations of the same thousands of vertices, every frame. They all read the
 * same clock, so keying on {@code (model, clip, time)} collapses that to one: the first through computes,
 * the rest hit it.</p>
 *
 * <p>⚠️ <b>The revision only advances when the pose actually changes.</b> That is what lets the render
 * backend skip its upload — a paused scene, or one whose clock has not crossed into a new instant, costs
 * nothing at all downstream. A time that never repeats exactly (a real clock) means one deformation a
 * frame, which is the floor for CPU skinning.</p>
 */
@OnlyIn(Dist.CLIENT)
final class AnimatedPose {

    /**
     * What a pose is shared by: a model, one of its clips, and <b>everything that decides what time the
     * clip is at</b>.
     *
     * <p>⚠️ The last part is not optional. The deformed array is handed out by reference, so two sources
     * sharing a slot while disagreeing about the instant would overwrite each other's geometry in place —
     * each would draw the other's pose, alternating, depending on which asked last. Every reader of one
     * slot has to compute the same {@code seconds}, and these are the inputs that decide it.</p>
     */
    private record Key(SkinnedModel model, @Nullable AnimationClip clip, float speed, boolean loop) {
    }

    /**
     * Entries live until the model they belong to is invalidated, which is what
     * {@link PhotonMeshCache#clear()} does on a resource reload — but the CLOCK is part of the key, so the
     * map would otherwise grow by one entry a frame forever. Only the newest pose per (model, clip) is
     * kept; nothing ever asks for an older one, because everything reads one clock.
     */
    private static final Map<Object, AnimatedPose> POSES = new ConcurrentHashMap<>();

    private final SkinDeformer deformer;
    private float[] geometry;
    private long revision;
    private float posedAt = Float.NaN;

    private AnimatedPose(SkinnedModel model) {
        this.deformer = new SkinDeformer(model.skeleton());
        this.geometry = new float[model.mesh().vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
    }

    /**
     * The pose of {@code model} under {@code clip} at {@code seconds}, deformed if nobody has already.
     *
     * @return null when the model cannot be posed at all (no skeleton, or no skinned vertices)
     */
    @Nullable
    static AnimatedPose of(SkinnedModel model, @Nullable AnimationClip clip, float seconds,
                           float speed, boolean loop) {
        if (!model.isAnimated()) {
            return null;
        }
        // one slot per (model, clip, clock): the newest pose replaces the previous, since every reader of
        // this slot is on the same clock and nothing looks backwards
        var slot = new Key(model, clip, speed, loop);
        var pose = POSES.computeIfAbsent(slot, k -> new AnimatedPose(model));
        pose.ensurePosed(model, clip, seconds);
        return pose;
    }

    private synchronized void ensurePosed(SkinnedModel model, @Nullable AnimationClip clip, float seconds) {
        if (posedAt == seconds) {
            return; // somebody else already did this frame's work
        }
        var skin = model.skin();
        if (skin == null) return;
        int floats = model.mesh().vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY;
        if (geometry.length != floats) {
            geometry = new float[floats];
        }
        deformer.pose(clip, seconds);
        deformer.deform(model.mesh(), skin, geometry);
        posedAt = seconds;
        revision++;
    }

    float[] geometry() {
        return geometry;
    }

    /**
     * Starts at 1, so it is never mistaken for the topology's own revision 0 — which is what
     * {@link IDynamicMesh#revision()} warns about.
     */
    long revision() {
        return revision;
    }

    static void clear() {
        POSES.clear();
    }
}
