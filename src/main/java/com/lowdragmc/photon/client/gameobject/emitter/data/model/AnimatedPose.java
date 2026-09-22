package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.AnimationClip;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinDeformer;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One deformed pose, shared by everything asking for the same animation at the same instant — an
 * emitter owns its own source, so five emitters playing one clip would otherwise be five identical
 * deformations a frame.
 */
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
    /** Above this many slots, drop the ones nothing has asked for lately. A live emitter holds one slot,
     *  but {@code speed} is in the key, so dragging the field once mints a slot per value it passes
     *  through — each holding a deformer and a geometry array the size of the model. */
    private static final int CROWDED = 16;
    private static final long STALE_NANOS = 5_000_000_000L;

    private final SkinDeformer deformer;
    private float[] geometry;
    /** Allocated the first time something draws with tangents, and deformed from then on. */
    @Nullable
    private float[] tangents;
    private long revision;
    private float posedAt = Float.NaN;
    private volatile long touched = System.nanoTime();

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
        // before the insert, never inside computeIfAbsent — its mapping function must not touch the map
        if (POSES.size() > CROWDED) {
            long cutoff = System.nanoTime() - STALE_NANOS;
            POSES.values().removeIf(pose -> pose.touched < cutoff);
        }
        var pose = POSES.computeIfAbsent(new Key(model, clip, speed, loop), k -> new AnimatedPose(model));
        pose.touched = System.nanoTime();
        pose.ensurePosed(model, clip, seconds);
        return pose;
    }

    private synchronized void ensurePosed(SkinnedModel model, @Nullable AnimationClip clip, float seconds) {
        if (posedAt == seconds) {
            return;
        }
        var skin = model.skin();
        if (skin == null) return;
        int vertices = model.mesh().vertexCount();
        int floats = vertices * PhotonMesh.FLOATS_PER_GEOMETRY;
        if (geometry.length != floats) {
            geometry = new float[floats];
        }
        float[] restTangents = null;
        if (tangents != null) {
            int tangentFloats = vertices * PhotonMesh.FLOATS_PER_TANGENT;
            if (tangents.length != tangentFloats) {
                tangents = new float[tangentFloats];
            }
            restTangents = model.mesh().tangents();
        }
        deformer.pose(clip, seconds);
        deformer.deform(model.mesh(), skin, geometry, restTangents, tangents);
        posedAt = seconds;
        revision++;
    }

    float[] geometry() {
        return geometry;
    }

    /** The deformed tangent frame. The first call allocates and re-poses, so nothing is spent on
     *  tangents for the passes — the great majority — that never ask. */
    synchronized float[] tangents(SkinnedModel model, @Nullable AnimationClip clip) {
        if (tangents == null) {
            tangents = new float[model.mesh().vertexCount() * PhotonMesh.FLOATS_PER_TANGENT];
            float at = posedAt;
            if (!Float.isNaN(at)) {
                posedAt = Float.NaN; // the pose is current but its tangents are not; redo it
                ensurePosed(model, clip, at);
            }
        }
        return tangents;
    }

    long revision() {
        return revision;
    }

    static void clear() {
        POSES.clear();
    }
}
