package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.CurveClip;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link AnimatedProperty} used by {@link ConfigPropertyType} (config-backed NUMBER_FUNCTION /
 * NUMBER_FUNCTION3 / discrete values). On top of the base keyframe channels it adds <b>curve clips</b>:
 * per-channel time windows in which the channel's runtime value becomes the clip's real
 * {@link NumberFunction} (a {@code Curve}, sampled per-particle) instead of the static keyframe value.
 * Curve clips only apply to NUMBER_FUNCTION/NUMBER_FUNCTION3; discrete (int/bool/float) types ignore them.
 */
public class ConfigAnimatedProperty extends AnimatedProperty {

    /** Per-channel curve clips (parallel to the base {@code exprClips}). */
    private final List<CurveClip>[] curveClips;

    @SuppressWarnings("unchecked")
    public ConfigAnimatedProperty(AnimatedPropertyType type, float[] base, ECBCurves[] channels,
                                  float rangeMin, float rangeMax) {
        super(type, base, channels, rangeMin, rangeMax);
        this.curveClips = (List<CurveClip>[]) new List[channels.length];
        for (int i = 0; i < channels.length; i++) this.curveClips[i] = new ArrayList<>();
    }

    /** The (mutable) list of curve clips on {@code axis}. */
    public List<CurveClip> curveClips(int axis) {
        return curveClips[axis];
    }

    /** The curve clip containing {@code time} on {@code axis}, earliest wins on overlap, or {@code null}. */
    @Nullable
    public CurveClip activeCurveClip(int axis, double time) {
        for (var clip : curveClips[axis]) {
            if (clip.contains(time)) return clip;
        }
        return null;
    }

    @Override
    public void apply(FXObject target, double time) {
        if (type() instanceof ConfigPropertyType cfg && cfg.valueType().channelCount() >= 1
                && (cfg.valueType() == ConfigValueType.NUMBER_FUNCTION || cfg.valueType() == ConfigValueType.NUMBER_FUNCTION3)) {
            var fns = new NumberFunction[channelCount()];
            for (int i = 0; i < fns.length; i++) {
                var clip = activeCurveClip(i, time);
                fns[i] = clip != null && clip.curve() != null
                        ? clip.curve()
                        : NumberFunction.constant(sampleChannelValue(i, (float) time));
            }
            cfg.applyFunctions(target, fns);
            return;
        }
        super.apply(target, time);
    }

    @Override
    public void insertTime(double atTick, double delta) {
        super.insertTime(atTick, delta); // base keyframe channels + expression clips
        for (var list : curveClips) {
            for (var clip : list) shiftSubClip(clip, atTick, delta);
        }
    }

    public List<CurveClip> snapshotCurveClips(int axis) {
        var copy = new ArrayList<CurveClip>();
        for (var c : curveClips[axis]) copy.add(c.copy());
        return copy;
    }

    public void restoreCurveClips(int axis, List<CurveClip> snapshot) {
        // Count match (a move/resize): mutate start/duration in place so UI element references stay valid
        // across a drag/undo (mirrors AnimatedProperty#restoreExprClips). Only a structural change rebuilds.
        var list = curveClips[axis];
        if (list.size() == snapshot.size()) {
            for (int i = 0; i < list.size(); i++) {
                list.get(i).start(snapshot.get(i).start()).duration(snapshot.get(i).duration());
            }
        } else {
            list.clear();
            for (var c : snapshot) list.add(c.copy());
        }
    }

    @Override
    public ConfigAnimatedProperty copy() {
        var copy = new ConfigAnimatedProperty(type(), base(), snapshotChannels(), rangeMin(), rangeMax());
        copy.restoreExprClipsFrom(this);
        for (int i = 0; i < curveClips.length; i++) copy.restoreCurveClips(i, curveClips[i]);
        return copy;
    }

    @Override
    public void restoreFrom(AnimatedProperty other) {
        super.restoreFrom(other);
        if (other instanceof ConfigAnimatedProperty cfg) {
            for (int i = 0; i < curveClips.length && i < cfg.curveClips.length; i++) {
                restoreCurveClips(i, cfg.curveClips[i]);
            }
        }
    }
}
