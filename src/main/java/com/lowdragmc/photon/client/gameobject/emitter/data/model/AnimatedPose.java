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
 * One deformed pose, shared by everything asking for the same animation at the same instant — an
 * emitter owns its own source, so five emitters playing one clip would otherwise be five identical
 * deformations a frame.
 */
@OnlyIn(Dist.CLIENT)
final class AnimatedPose {

    /**
     * ⚠️ The key must cover everything that decides what time the clip is at. The deformed array is
     * handed out by reference, so readers of one slot that disagree about the instant would overwrite
     * each other's geometry in place.
     */
    private record Key(SkinnedModel model, @Nullable AnimationClip clip, float speed, boolean loop) {
    }

    /** Only the newest pose per key is kept; everything reads one clock, so nothing looks backwards. */
    private static final Map<Object, AnimatedPose> POSES = new ConcurrentHashMap<>();

    private final SkinDeformer deformer;
    private float[] geometry;
    private long revision;
    private float posedAt = Float.NaN;

    private AnimatedPose(SkinnedModel model) {
        this.deformer = new SkinDeformer(model.skeleton());
        this.geometry = new float[model.mesh().vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
    }

    /** Null when the model cannot be posed at all. */
    @Nullable
    static AnimatedPose of(SkinnedModel model, @Nullable AnimationClip clip, float seconds,
                           float speed, boolean loop) {
        if (!model.isAnimated()) {
            return null;
        }
        var pose = POSES.computeIfAbsent(new Key(model, clip, speed, loop), k -> new AnimatedPose(model));
        pose.ensurePosed(model, clip, seconds);
        return pose;
    }

    private synchronized void ensurePosed(SkinnedModel model, @Nullable AnimationClip clip, float seconds) {
        if (posedAt == seconds) {
            return;
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

    long revision() {
        return revision;
    }

    static void clear() {
        POSES.clear();
    }
}
