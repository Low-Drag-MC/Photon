package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** How an object's data is compared with its source: the paths the structure owns, and the values that only change whole. */
public final class InstanceDiffRules implements NbtPatch.Rules {
    public static final List<String> POSITION = List.of(InstanceTags.TRANSFORM, "localPosition");
    public static final List<String> ROTATION = List.of(InstanceTags.TRANSFORM, "localRotation");
    public static final List<String> SCALE = List.of(InstanceTags.TRANSFORM, "localScale");
    /** The key a toggle group saves its switch under; a switched-off group saves nothing else. */
    public static final String ENABLE = "_enable";

    private static final Set<List<String>> STRUCTURE = Set.of(
            List.of(InstanceTags.TRANSFORM, InstanceTags.ID),
            List.of(InstanceTags.TRANSFORM, InstanceTags.PARENT),
            List.of(InstanceTags.TRANSFORM, InstanceTags.CHILDREN),
            List.of(InstanceTags.VERSION));
    /** A nested instance's source, overrides and timeline belong to the level that placed it. */
    private static final Set<List<String>> NESTED_INSTANCE = Set.of(
            List.of(InstanceTags.FX),
            List.of(InstanceTags.OVERRIDES),
            List.of(InstanceTags.TIMELINE));

    /** What a material or mesh that points at a library resource or a shader points with. */
    private static final Map<String, String> REFERENCES = Map.of(
            "ui_resource_material", "resourcePath",
            "resource_mesh", "resourcePath",
            "shader_graph", "graphPath",
            "custom_shader", "shaderLocation");

    private final boolean instance;
    private final Set<List<String>> skipped;

    /** @param skipped further paths to leave out, e.g. transform channels a timeline drives */
    public InstanceDiffRules(String type, Set<List<String>> skipped) {
        this.instance = InstanceTags.INSTANCE_TYPE.equals(type);
        this.skipped = skipped;
    }

    public static InstanceDiffRules of(String type) {
        return new InstanceDiffRules(type, Set.of());
    }

    @Override
    public boolean excluded(List<String> path) {
        return STRUCTURE.contains(path) || instance && NESTED_INSTANCE.contains(path) || skipped.contains(path);
    }

    /** Whether a change at {@code path} may be applied: not to a structure key, nor to anything holding one. */
    public boolean accepts(List<String> path) {
        for (int i = 1; i <= path.size(); i++) {
            if (excluded(path.subList(0, i))) {
                return false;
            }
        }
        for (var structure : STRUCTURE) {
            if (structure.size() > path.size() && structure.subList(0, path.size()).equals(path)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean atomic(List<String> path, Tag from, Tag to) {
        if (ROTATION.equals(path)) {
            return true;   // a quaternion's components mean nothing apart
        }
        if (from instanceof CompoundTag a && to instanceof CompoundTag b) {
            // a different kind of value (constant -> curve, texture -> shader material) is not a merge of keys
            if (a.getString(InstanceTags.TYPE).isPresent() && b.getString(InstanceTags.TYPE).isPresent()
                    && !a.getStringOr(InstanceTags.TYPE, "").equals(b.getStringOr(InstanceTags.TYPE, ""))) {
                return true;
            }
            // pointing elsewhere is another value too, and a pack export only recognises the reference whole
            var key = REFERENCES.get(a.getStringOr(InstanceTags.TYPE, ""));
            if (key != null && !Objects.equals(a.getCompoundOrEmpty(InstanceTags.DATA).get(key), b.getCompoundOrEmpty(InstanceTags.DATA).get(key))) {
                return true;
            }
            // switching a group off drops its values from the save, so it is the whole group that changed
            return a.contains(ENABLE) && b.contains(ENABLE) && a.getBooleanOr(ENABLE, false) != b.getBooleanOr(ENABLE, false);
        }
        return false;
    }
}
