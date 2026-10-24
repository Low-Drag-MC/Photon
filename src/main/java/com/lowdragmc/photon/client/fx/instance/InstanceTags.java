package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The saved shapes the instance code reads: {@code fxData{fxObjects, timeline}} and {@code {type, data}} objects. */
public final class InstanceTags {
    public static final String OBJECTS = "fxObjects";
    public static final String TIMELINE = "timeline";
    public static final String TYPE = "type";
    public static final String DATA = "data";
    public static final String TRANSFORM = "transform";
    public static final String ID = "id";
    public static final String PARENT = "_parentId";
    public static final String CHILDREN = "_childrenId";
    public static final String NAME = "name";
    public static final String VERSION = "version";

    public static final String INSTANCE_TYPE = "fx_instance";
    public static final String FX = "fx";
    public static final String OVERRIDES = "overrides";

    private InstanceTags() {
    }

    /** The live objects; an element that is not a compound is no object, and is skipped. */
    public static List<CompoundTag> objects(CompoundTag fxData) {
        var list = fxData.getListOrEmpty(OBJECTS);
        var out = new ArrayList<CompoundTag>(list.size());
        for (var element : list) {
            if (element instanceof CompoundTag object) out.add(object);
        }
        return out;
    }

    public static CompoundTag fxData(Collection<CompoundTag> objects, @Nullable CompoundTag timeline) {
        var tag = new CompoundTag();
        var list = new ListTag();
        list.addAll(objects);
        tag.put(OBJECTS, list);
        if (timeline != null) {
            tag.put(TIMELINE, timeline);
        }
        return tag;
    }

    public static String type(CompoundTag object) {
        return object.getStringOr(TYPE, "");
    }

    public static boolean isInstance(CompoundTag object) {
        return INSTANCE_TYPE.equals(type(object));
    }

    public static CompoundTag data(CompoundTag object) {
        return object.getCompoundOrEmpty(DATA);
    }

    public static CompoundTag transform(CompoundTag object) {
        return data(object).getCompoundOrEmpty(TRANSFORM);
    }

    @Nullable
    public static UUID id(CompoundTag object) {
        return InstanceIds.parse(transform(object).getStringOr(ID, ""));
    }

    @Nullable
    public static UUID parent(CompoundTag object) {
        return InstanceIds.parse(transform(object).getStringOr(PARENT, ""));
    }

    public static List<UUID> children(CompoundTag object) {
        var list = transform(object).getListOrEmpty(CHILDREN);
        var out = new ArrayList<UUID>(list.size());
        for (int i = 0; i < list.size(); i++) {
            var id = InstanceIds.parse(list.getStringOr(i, ""));
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    public static void setParent(CompoundTag object, UUID parent) {
        transformOf(object).putString(PARENT, parent.toString());
    }

    public static void setChildren(CompoundTag object, Collection<UUID> children) {
        var list = new ListTag();
        for (var child : children) {
            list.add(StringTag.valueOf(child.toString()));
        }
        transformOf(object).put(CHILDREN, list);
    }

    public static String name(CompoundTag object) {
        return data(object).getStringOr(NAME, "");
    }

    @Nullable
    public static Identifier fx(CompoundTag instance) {
        var value = data(instance).getStringOr(FX, "");
        return value.isEmpty() ? null : Identifier.tryParse(value);
    }

    /** The transform compound, created if absent, so writes land in the object. */
    private static CompoundTag transformOf(CompoundTag object) {
        return child(child(object, DATA), TRANSFORM);
    }

    private static CompoundTag child(CompoundTag parent, String key) {
        if (parent.get(key) instanceof CompoundTag child) {
            return child;
        }
        var child = new CompoundTag();
        parent.put(key, child);
        return child;
    }
}
