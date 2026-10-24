package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.fx.instance.InstanceBaker;
import com.lowdragmc.photon.client.fx.instance.InstanceDiffRules;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceRecord;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.instance.InstanceTemplate;
import com.lowdragmc.photon.client.fx.instance.InstanceWriter;
import com.lowdragmc.photon.client.fx.instance.NbtPatch;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.Timeline;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ParametersAreNonnullByDefault
public final class FXData {
    private final List<IFXObject> objects;
    private final Timeline timeline;

    public FXData() {
        this(new ArrayList<>(), new Timeline());
    }

    public FXData(List<IFXObject> objects) {
        this(objects, new Timeline());
    }

    public FXData(List<IFXObject> objects, Timeline timeline) {
        this.objects = objects;
        this.timeline = timeline;
    }

    public List<IFXObject> objects() {
        return objects;
    }

    /**
     * The timeline that choreographs the child objects. Empty by default (and for legacy FX with no
     * timeline data), in which case the runtime behaves exactly as before.
     */
    public Timeline timeline() {
        return timeline;
    }

    public FXData copy(boolean deepCopy) {
        var copied = objects.stream().map(obj -> obj.copy(deepCopy)).collect(Collectors.toList());
        for (int i = 0; i < copied.size(); i++) {
            // what an instance was placed from is not saved with it, but a copy saves the same way
            if (objects.get(i) instanceof FXInstanceObject from && copied.get(i) instanceof FXInstanceObject to) {
                to.setPlacement(from.getPlacement());
            }
        }
        return new FXData(copied, timeline.copy());
    }

    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var placements = new HashMap<UUID, InstanceBaker.Placement>();
        for (var object : objects) {
            if (object instanceof FXInstanceObject instance && instance.getPlacement() != null) {
                placements.put(instance.id(), instance.getPlacement());
            }
        }
        if (placements.isEmpty()) {
            var tag = new CompoundTag();
            var fxObjects = new ListTag();
            for (var fxObject : objects) {
                fxObjects.add(fxObject.serializeWrapper());
            }
            tag.put("fxObjects", fxObjects);
            // only write timeline data when present, keeping legacy files byte-identical
            if (!timeline.isEmpty()) {
                tag.put("timeline", timeline.serializeNBT(provider));
            }
            return tag;
        }
        // an instance is saved as its record: what it placed is not written
        var animated = animatedTransforms();
        return InstanceWriter.write(serializedObjects(), timeline.isEmpty() ? null : timeline.serializeNBT(provider),
                placements, id -> animated.getOrDefault(id, Set.of()));
    }

    /** Every object as it is saved on its own, instances in their placed form. */
    public List<CompoundTag> serializedObjects() {
        var out = new ArrayList<CompoundTag>(objects.size());
        for (var object : objects) {
            var tag = object.serializeWrapper();
            if (tag != null) out.add(tag);
        }
        return out;
    }

    /** Per object, the transform channels a timeline drives, which an instance's record must not take as edits. */
    public Map<UUID, Set<List<String>>> animatedTransforms() {
        return animatedTransforms(timeline, objects);
    }

    /** The same for {@code objects} under {@code timeline}, together with what their instances' own timelines drive. */
    public static Map<UUID, Set<List<String>>> animatedTransforms(Timeline timeline, Collection<? extends IFXObject> objects) {
        var out = new HashMap<UUID, Set<List<String>>>();
        var timelines = new ArrayList<Timeline>();
        timelines.add(timeline);
        for (var object : objects) {
            if (object instanceof FXInstanceObject instance) timelines.add(instance.getTimeline());
        }
        for (var each : timelines) {
            for (var track : each.leafTracks(false)) {
                if (track.mute() || !(track instanceof AnimationTrack animation) || animation.targetId() == null) continue;
                for (var property : animation.properties()) {
                    var path = switch (property.type().key()) {
                        case "transform.position" -> InstanceDiffRules.POSITION;
                        case "transform.rotation" -> InstanceDiffRules.ROTATION;
                        case "transform.scale" -> InstanceDiffRules.SCALE;
                        default -> null;
                    };
                    if (path != null) out.computeIfAbsent(animation.targetId(), id -> new HashSet<>()).add(path);
                }
            }
        }
        return out;
    }

    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        deserializeNBT(provider, tag, List.of());
    }

    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag, Collection<Identifier> as) {
        objects.clear();
        var baked = InstanceBaker.hasInstances(tag) ? FXInstances.bake(as, tag) : null;
        var list = (baked == null ? tag : baked.fxData()).getListOrEmpty("fxObjects");
        for (var nbt : list) {
            if (nbt instanceof CompoundTag data) {
                var fxObject = IFXObject.deserializeWrapper(data);
                if (fxObject != null) {
                    objects.add(fxObject);
                }
            }
        }
        if (baked != null) {
            attach(objects, baked, new HashMap<>());
        }
        // tolerate a missing "timeline" tag: legacy FX deserialize to an empty timeline
        timeline.deserializeNBT(provider, tag.getCompoundOrEmpty("timeline"));
    }

    /**
     * Give each instance among {@code objects} (read from {@code baked}, not yet linked) what it was placed from,
     * keeping as unused any change its objects did not take. A placed object that cannot be read back (a type no
     * longer installed) keeps its changes, and what hung under it waits under the nearest object that could be read,
     * so saving does not take it as removed.
     *
     * @param outerParents parents, by id, of objects of the effect that were baked along but not read again here
     *                     (an editor's live objects); re-pointed in place like the rest
     */
    public static void attach(Collection<? extends IFXObject> objects, InstanceBaker.Result baked, Map<UUID, UUID> outerParents) {
        var byId = new HashMap<UUID, IFXObject>();
        for (var object : objects) {
            byId.put(object.id(), object);
        }
        baked.placements().forEach((id, placement) -> {
            if (!(byId.get(id) instanceof FXInstanceObject instance)) return;
            if (!placement.isPlaced()) {
                instance.setPlacement(placement);
                return;
            }
            var template = placement.template();
            var unreadable = new HashSet<UUID>();
            for (var tag : InstanceTags.objects(baked.fxData())) {
                var placedId = InstanceTags.id(tag);
                if (placedId != null && !byId.containsKey(placedId) && template.contains(InstanceIds.xor(placedId, id))) {
                    unreadable.add(InstanceIds.xor(placedId, id));
                }
            }
            var refused = new ArrayList<InstanceRecord.Modification>();
            placement.applied().forEach((source, changes) -> {
                var placed = byId.get(InstanceIds.xor(id, source));
                var saved = placed == null ? null : placed.serializeWrapper();
                if (saved == null) {
                    changes.forEach(change -> refused.add(new InstanceRecord.Modification(source, change)));
                    return;
                }
                var data = InstanceTags.data(saved);
                for (var change : changes) {
                    if (!NbtPatch.holds(data, change)) {
                        refused.add(new InstanceRecord.Modification(source, change));
                    }
                }
            });
            var placedNow = placement.withUnused(refused);
            if (!unreadable.isEmpty()) {
                var anchors = new ArrayList<>(placedNow.unused().added());
                rehang(id, template, unreadable, byId, outerParents, anchors);
                var unused = placedNow.unused();
                placedNow = placedNow.withUnused(new InstanceRecord(unused.modified(), unused.removed(), anchors))
                        .withUnreadable(unreadable);
            }
            instance.setPlacement(placedNow);
        });
    }

    private static void rehang(UUID instance, InstanceTemplate template, Set<UUID> unreadable, Map<UUID, IFXObject> byId,
                               Map<UUID, UUID> outerParents, List<InstanceRecord.Added> anchors) {
        for (var object : byId.values()) {
            var parent = rehung(instance, template, unreadable, object.id(), object.transform()._getInternalParentID(), anchors);
            if (parent != null) object.transform()._setInternalParentID(parent);
        }
        outerParents.replaceAll((id, parentId) -> {
            var parent = rehung(instance, template, unreadable, id, parentId, anchors);
            return parent == null ? parentId : parent;
        });
    }

    /** Where object {@code id} goes when it hangs under an unreadable object of {@code instance}; null when it does not. */
    @Nullable
    private static UUID rehung(UUID instance, InstanceTemplate template, Set<UUID> unreadable, UUID id, @Nullable UUID parentId,
                               List<InstanceRecord.Added> anchors) {
        if (parentId == null) return null;
        var parentSource = InstanceIds.xor(parentId, instance);
        if (!unreadable.contains(parentSource)) return null;
        var source = InstanceIds.xor(id, instance);
        if (template.contains(source)) {
            var at = template.parent(source);
            while (unreadable.contains(at)) at = template.parent(at);
            return InstanceIds.ROOT.equals(at) ? instance : InstanceIds.xor(instance, at);
        }
        // an object this effect added there: under the instance, keeping its anchor for when the type is back
        anchors.add(new InstanceRecord.Added(parentSource, id));
        return instance;
    }
}
