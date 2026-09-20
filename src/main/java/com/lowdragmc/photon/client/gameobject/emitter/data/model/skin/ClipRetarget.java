package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Moves clips from one skeleton onto another by matching joint names — what lets an animation-only glb
 * drive a character that lives in a different file. Both are exports of the same rig, so the names line
 * up; the joint ORDER need not.
 *
 * <p>⚠️ Not retargeting in the animation sense: no bone mapping, no proportion correction. A clip built
 * for a different rig will match few names and mostly disappear, which {@link Result#droppedChannels()}
 * is there to report.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClipRetarget {

    /** @param matchedJoints how many of the source's joints exist in the target, for diagnosing a mismatch */
    public record Result(List<AnimationClip> clips, int matchedJoints, int droppedChannels) {
    }

    private ClipRetarget() {
    }

    /**
     * @param label names the result: a single-clip file becomes {@code label}, a multi-clip one
     *              {@code label/clipName} — an exporter's clip names are routinely the same string in
     *              every file it writes ("Armature|mixamo.com|Layer0"), so the file's name is the only
     *              thing that tells two of them apart
     */
    public static Result onto(Skeleton target, Skeleton source, List<AnimationClip> clips, String label) {
        var slotOfName = new HashMap<String, Integer>();
        for (int joint = target.jointCount() - 1; joint >= 0; joint--) {
            slotOfName.put(target.name(joint), joint); // first wins on a duplicate name
        }

        int[] remap = new int[source.jointCount()];
        int matched = 0;
        for (int joint = 0; joint < remap.length; joint++) {
            remap[joint] = slotOfName.getOrDefault(source.name(joint), -1);
            if (remap[joint] >= 0) matched++;
        }

        var out = new ArrayList<AnimationClip>();
        int dropped = 0;
        for (var clip : clips) {
            var channels = new ArrayList<AnimationClip.Channel>();
            for (var channel : clip.channels()) {
                int joint = channel.joint() >= 0 && channel.joint() < remap.length
                        ? remap[channel.joint()] : -1;
                if (joint < 0) {
                    dropped++;
                    continue;
                }
                channels.add(new AnimationClip.Channel(joint, channel.path(), channel.interpolation(),
                        channel.times(), channel.values()));
            }
            if (channels.isEmpty()) continue;
            out.add(new AnimationClip(clips.size() == 1 ? label : label + "/" + clip.name(), channels));
        }
        return new Result(List.copyOf(out), matched, dropped);
    }
}
