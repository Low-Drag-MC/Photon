package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * A target-less track whose clips are post-processing windows ({@link PostProcessClip}): the
 * timeline player submits the clip's effect to the execution context's post-effect sink every
 * frame the clip is active, weighted by the clip's fade envelope. Multiple overlapping clips of
 * the same effect blend by weight (the stack's Unity-Volume semantics).
 */
public class PostProcessTrack extends Track {

    @Override
    protected Clip createClip(double start, double duration, float speed) {
        return new PostProcessClip(start, duration, speed);
    }

    @Override
    protected void writeClipExtra(Clip clip, CompoundTag clipTag, HolderLookup.Provider provider) {
        if (clip instanceof PostProcessClip post) {
            if (!post.effect().isEmpty()) clipTag.putString("effect", post.effect());
            clipTag.put("weight", post.weight().serializeWrapper());
            if (post.maskCulling()) {
                clipTag.putBoolean("maskCulling", true);
                if (!post.maskGroup().isEmpty()) clipTag.putString("maskGroup", post.maskGroup());
            }
            if (post.independent()) clipTag.putBoolean("independent", true);
            if (!post.params().isEmpty()) {
                var paramsTag = new CompoundTag();
                post.params().forEach((name, override) -> {
                    var overrideTag = new CompoundTag();
                    overrideTag.putString("kind", override.kind().name());
                    var channels = new ListTag();
                    for (var fn : override.channels()) {
                        channels.add(fn.serializeWrapper());
                    }
                    overrideTag.put("fns", channels);
                    paramsTag.put(name, overrideTag);
                });
                clipTag.put("params", paramsTag);
            }
        }
    }

    @Override
    protected void readClipExtra(Clip clip, CompoundTag clipTag, HolderLookup.Provider provider) {
        if (clip instanceof PostProcessClip post) {
            if (clipTag.contains("effect")) post.effect(clipTag.getStringOr("effect", ""));
            post.maskCulling(clipTag.getBooleanOr("maskCulling", false));
            post.maskGroup(clipTag.getStringOr("maskGroup", ""));
            if (clipTag.contains("maskFilter")) { // pre-string clips: numeric groups keep their name
                var legacy = clipTag.getIntOr("maskFilter", 0);
                post.maskCulling(legacy >= 0);
                post.maskGroup(legacy > 0 ? String.valueOf(legacy) : "");
            }
            post.independent(clipTag.getBooleanOr("independent", false));
            if (clipTag.contains("weight")) {
                post.weight(NumberFunction.deserializeWrapper(clipTag.getCompoundOrEmpty("weight")));
            } else if (clipTag.contains("maxWeight")) { // pre-function clips: constant envelope
                post.weight(NumberFunction.constant(clipTag.getFloatOr("maxWeight", 0.0F)));
            }
            var paramsTag = clipTag.getCompoundOrEmpty("params");
            for (var name : paramsTag.keySet()) {
                var overrideTag = paramsTag.getCompoundOrEmpty(name);
                PostProcessClip.ParamKind kind;
                try {
                    kind = PostProcessClip.ParamKind.valueOf(overrideTag.getStringOr("kind", ""));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                var channels = new java.util.ArrayList<NumberFunction>(kind.channelCount());
                var channelsTag = overrideTag.getListOrEmpty("fns");
                for (int i = 0; i < channelsTag.size(); i++) {
                    channels.add(NumberFunction.deserializeWrapper(channelsTag.getCompoundOrEmpty(i)));
                }
                if (channels.isEmpty() && overrideTag.contains("fn")) { // pre-vector single-fn form
                    channels.add(NumberFunction.deserializeWrapper(overrideTag.getCompoundOrEmpty("fn")));
                }
                while (channels.size() < kind.channelCount()) {
                    channels.add(NumberFunction.constant(0));
                }
                post.params().put(name, new PostProcessClip.ParamOverride(kind, channels));
            }
        }
    }
}
