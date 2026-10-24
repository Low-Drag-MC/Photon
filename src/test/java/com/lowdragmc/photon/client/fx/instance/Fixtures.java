package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Saved FX data written by hand, in the shapes the editor saves. */
final class Fixtures {
    private Fixtures() {
    }

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    static ResourceLocation fx(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    static CompoundTag object(String type, String name, @Nullable UUID parent, UUID... children) {
        var transform = new CompoundTag();
        transform.putString("id", id(name).toString());
        transform.putString("_parentId", (parent == null ? InstanceIds.ROOT : parent).toString());
        var childList = new ListTag();
        for (var child : children) {
            childList.add(StringTag.valueOf(child.toString()));
        }
        transform.put("_childrenId", childList);
        transform.put("localPosition", floats(0, 0, 0));
        transform.put("localRotation", floats(0, 0, 0, 1));
        transform.put("localScale", floats(1, 1, 1));
        var data = new CompoundTag();
        data.putString("name", name);
        data.put("transform", transform);
        var object = new CompoundTag();
        object.putString("type", type);
        object.put("data", data);
        return object;
    }

    /** An emitter-like object with a small config. */
    static CompoundTag emitter(String name, @Nullable UUID parent, UUID... children) {
        var object = object("particle_emitter", name, parent, children);
        var config = new CompoundTag();
        config.putInt("duration", 100);
        config.putBoolean("looping", true);
        config.put("startLifetime", constant(20));
        var noise = new CompoundTag();
        noise.putBoolean("_enable", true);
        noise.putFloat("strength", 0.5f);
        config.put("noise", noise);
        var startSize = new ListTag();
        startSize.add(constant(1));
        startSize.add(constant(1));
        startSize.add(constant(1));
        config.put("startSize", startSize);
        InstanceTags.data(object).put("config", config);
        return object;
    }

    static CompoundTag instance(String name, @Nullable UUID parent, ResourceLocation fx, @Nullable CompoundTag overrides) {
        var object = object(InstanceTags.INSTANCE_TYPE, name, parent);
        InstanceTags.data(object).putString("fx", fx.toString());
        if (overrides != null) {
            InstanceTags.data(object).put("overrides", overrides);
        }
        return object;
    }

    static CompoundTag constant(float value) {
        var data = new CompoundTag();
        data.putFloat("value", value);
        var function = new CompoundTag();
        function.putString("type", "constant");
        function.put("data", data);
        return function;
    }

    static CompoundTag curve(float... points) {
        var data = new CompoundTag();
        data.put("points", floats(points));
        var function = new CompoundTag();
        function.putString("type", "curve");
        function.put("data", data);
        return function;
    }

    static ListTag floats(float... values) {
        var list = new ListTag();
        for (var value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    static CompoundTag fxData(CompoundTag... objects) {
        return InstanceTags.fxData(List.of(objects), null);
    }

    static CompoundTag fxDataWith(@Nullable CompoundTag timeline, CompoundTag... objects) {
        return InstanceTags.fxData(List.of(objects), timeline);
    }

    /** A timeline with an activator track bound to {@code target} and a control clip bound to {@code clipTarget}. */
    static CompoundTag timeline(UUID target, UUID clipTarget) {
        var clip = new CompoundTag();
        clip.putDouble("start", 0);
        clip.putDouble("duration", 20);
        clip.put("clipTarget", new IntArrayTag(InstanceIds.toInts(clipTarget)));
        var clips = new ListTag();
        clips.add(clip);
        var controlData = new CompoundTag();
        controlData.put("clips", clips);
        var control = new CompoundTag();
        control.putString("type", "control");
        control.put("data", controlData);

        var activatorData = new CompoundTag();
        activatorData.put("target", new IntArrayTag(InstanceIds.toInts(target)));
        activatorData.put("clips", new ListTag());
        var activator = new CompoundTag();
        activator.putString("type", "activator");
        activator.put("data", activatorData);

        var tracks = new ListTag();
        tracks.add(activator);
        tracks.add(control);
        var timeline = new CompoundTag();
        timeline.put("tracks", tracks);
        return timeline;
    }

    static UUID activatorTarget(CompoundTag timeline) {
        var track = timeline.getList("tracks", Tag.TAG_COMPOUND).getCompound(0);
        return InstanceIds.fromInts(track.getCompound("data").getIntArray("target"));
    }

    static UUID clipTarget(CompoundTag timeline) {
        var track = timeline.getList("tracks", Tag.TAG_COMPOUND).getCompound(1);
        var clip = track.getCompound("data").getList("clips", Tag.TAG_COMPOUND).getCompound(0);
        return InstanceIds.fromInts(clip.getIntArray("clipTarget"));
    }

    static CompoundTag overrides(InstanceRecord record) {
        return record.write();
    }

    static InstanceRecord.Modification set(UUID target, Tag value, String... path) {
        return new InstanceRecord.Modification(target, NbtPatch.Change.set(List.of(path), value));
    }

    /** Sources by id, read as a map; counts reads so a test can tell what was baked again. */
    static final class Sources implements InstanceTemplates.Source {
        final Map<ResourceLocation, CompoundTag> files = new HashMap<>();
        final Map<ResourceLocation, Integer> reads = new HashMap<>();

        Sources put(ResourceLocation id, CompoundTag fxData) {
            files.put(id, fxData);
            return this;
        }

        @Override
        public @Nullable CompoundTag read(ResourceLocation id) {
            reads.merge(id, 1, Integer::sum);
            var tag = files.get(id);
            return tag == null ? null : tag.copy();
        }
    }

    static final class Problems implements InstanceTemplates.Reporter {
        final List<String> all = new ArrayList<>();

        @Override
        public void report(String problem) {
            all.add(problem);
        }
    }

    static InstanceTemplates templates(Sources sources, Problems problems) {
        return new InstanceTemplates(sources, object -> object, problems);
    }

    /** Objects of a baked FX data by id. */
    static Map<UUID, CompoundTag> byId(CompoundTag fxData) {
        var out = new HashMap<UUID, CompoundTag>();
        for (var object : InstanceTags.objects(fxData)) {
            out.put(InstanceTags.id(object), object);
        }
        return out;
    }

    static List<UUID> ids(CompoundTag fxData) {
        var out = new ArrayList<UUID>();
        for (var object : InstanceTags.objects(fxData)) {
            out.add(InstanceTags.id(object));
        }
        return out;
    }

    static UUID placed(UUID instance, String sourceName) {
        return InstanceIds.xor(instance, id(sourceName));
    }

    static CompoundTag config(CompoundTag object) {
        return InstanceTags.data(object).getCompound("config");
    }
}
