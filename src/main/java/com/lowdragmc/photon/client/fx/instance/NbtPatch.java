package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * NBT diff/patch, the form of an FX instance's overrides. Compounds diff key by key, lists by position
 * (a shorter list is a {@link #SIZE} change, a longer one appended elements, as Unity's {@code Array.data[i]}),
 * anything else whole. {@link #apply} returns what it could not apply so it can be kept as unused.
 */
public final class NbtPatch {

    /** The list-path segment naming the list's length. */
    public static final String SIZE = "size";

    private NbtPatch() {
    }

    public interface Rules {
        Rules NONE = new Rules() {
        };

        default boolean excluded(List<String> path) {
            return false;
        }

        /** A difference anywhere inside {@code path} replaces the whole value. */
        default boolean atomic(List<String> path, Tag from, Tag to) {
            return false;
        }
    }

    /**
     * Set the value at {@code path}, or remove the compound key it ends at. Segments are read against
     * the structure walked: a key in a compound, an index or {@link #SIZE} in a list.
     */
    public record Change(List<String> path, @Nullable Tag value, boolean remove) {

        public Change {
            path = List.copyOf(path);
        }

        public static Change set(List<String> path, Tag value) {
            return new Change(path, value, false);
        }

        public static Change remove(List<String> path) {
            return new Change(path, null, true);
        }

        public CompoundTag write() {
            var tag = new CompoundTag();
            var segments = new ListTag();
            for (var segment : path) {
                segments.add(StringTag.valueOf(segment));
            }
            tag.put("path", segments);
            if (remove) {
                tag.putBoolean("remove", true);
            } else if (value != null) {
                tag.put("value", value.copy());
            }
            return tag;
        }

        /** Null for a malformed tag. */
        @Nullable
        public static Change read(CompoundTag tag) {
            var segments = tag.getListOrEmpty("path");
            if (segments.isEmpty()) {
                return null;
            }
            var path = new ArrayList<String>(segments.size());
            for (var segment : segments) {
                // a path of anything but strings is malformed
                if (!(segment instanceof StringTag(var value))) return null;
                path.add(value);
            }
            if (tag.getBooleanOr("remove", false)) {
                return remove(path);
            }
            var value = tag.get("value");
            return value == null ? null : set(path, value.copy());
        }

        public String describePath() {
            return String.join("/", path);
        }
    }

    public record Result(CompoundTag tag, List<Change> unused) {
    }

    public static List<Change> diff(CompoundTag from, CompoundTag to) {
        return diff(from, to, Rules.NONE);
    }

    public static List<Change> diff(CompoundTag from, CompoundTag to, Rules rules) {
        var out = new ArrayList<Change>();
        diffCompound(new ArrayList<>(), from, to, rules, out);
        return out;
    }

    private static void diffCompound(List<String> path, CompoundTag from, CompoundTag to, Rules rules, List<Change> out) {
        // sorted, so the same values always give the same changes
        for (var key : new TreeSet<>(to.keySet())) {
            var at = append(path, key);
            if (rules.excluded(at)) {
                continue;
            }
            var target = to.get(key);
            var source = from.get(key);
            if (source == null) {
                out.add(Change.set(at, target.copy()));
            } else if (!same(source, target)) {
                diffValue(at, source, target, rules, out);
            }
        }
        for (var key : new TreeSet<>(from.keySet())) {
            if (!to.contains(key)) {
                var at = append(path, key);
                if (!rules.excluded(at)) {
                    out.add(Change.remove(at));
                }
            }
        }
    }

    private static void diffValue(List<String> path, Tag source, Tag target, Rules rules, List<Change> out) {
        if (rules.atomic(path, source, target)) {
            out.add(Change.set(path, target.copy()));
        } else if (source instanceof CompoundTag a && target instanceof CompoundTag b) {
            diffCompound(path, a, b, rules, out);
        } else if (source instanceof ListTag a && target instanceof ListTag b && sameElementType(a, b)) {
            diffList(path, a, b, rules, out);
        } else {
            out.add(Change.set(path, target.copy()));
        }
    }

    private static void diffList(List<String> path, ListTag from, ListTag to, Rules rules, List<Change> out) {
        if (to.size() < from.size()) {
            out.add(Change.set(append(path, SIZE), IntTag.valueOf(to.size())));
        }
        int shared = Math.min(from.size(), to.size());
        for (int i = 0; i < shared; i++) {
            if (!same(from.get(i), to.get(i))) {
                diffValue(append(path, Integer.toString(i)), from.get(i), to.get(i), rules, out);
            }
        }
        for (int i = from.size(); i < to.size(); i++) {
            out.add(Change.set(append(path, Integer.toString(i)), to.get(i).copy()));
        }
    }

    /** An empty list has no element type yet. */
    private static boolean sameElementType(ListTag a, ListTag b) {
        return a.isEmpty() || b.isEmpty() || elementType(a) == elementType(b);
    }

    /**
     * Whether {@code value} may sit in {@code list}: one of the type its elements have. 26.1 lists take mixed elements
     * (saving wraps them), so this is the check 1.21's own lists made, which refused a mistyped element — except in a
     * list of compounds, which in 1.21 is also where mixed elements lived, each wrapped in a compound of its own.
     */
    private static boolean fits(ListTag list, Tag value) {
        if (list.isEmpty()) return true;
        var type = elementType(list);
        return type == Tag.TAG_COMPOUND || type == value.getId();
    }

    /** The type 1.21 gave the list's elements, as saving still does: their own if they share one, else compound. */
    private static byte elementType(ListTag list) {
        var type = list.getFirst().getId();
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).getId() != type) return Tag.TAG_COMPOUND;
        }
        return type;
    }

    /**
     * Equal as values. 26.1's float and double tags are records, so -0.0 and 0.0 differ by {@code equals}: a rotation
     * turned and turned back would be an override. 1.21 compared them with {@code ==}; NaN here still equals itself.
     */
    static boolean same(@Nullable Tag a, @Nullable Tag b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return switch (a) {
            case FloatTag(var x) when b instanceof FloatTag(var y) -> x == y || Float.compare(x, y) == 0;
            case DoubleTag(var x) when b instanceof DoubleTag(var y) -> x == y || Double.compare(x, y) == 0;
            case CompoundTag x when b instanceof CompoundTag y -> sameCompound(x, y);
            case ListTag x when b instanceof ListTag y -> sameList(x, y);
            default -> a.equals(b);
        };
    }

    private static boolean sameCompound(CompoundTag a, CompoundTag b) {
        if (a.size() != b.size()) return false;
        for (var key : a.keySet()) {
            if (!same(a.get(key), b.get(key))) return false;
        }
        return true;
    }

    private static boolean sameList(ListTag a, ListTag b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private static List<String> append(List<String> path, String segment) {
        var longer = new ArrayList<String>(path.size() + 1);
        longer.addAll(path);
        longer.add(segment);
        return longer;
    }

    /** A copy of {@code onto} with {@code changes} made in order; {@code onto} is not modified. */
    public static Result apply(CompoundTag onto, List<Change> changes) {
        var result = onto.copy();
        var unused = new ArrayList<Change>();
        for (var change : changes) {
            if (!applyOne(result, change)) {
                unused.add(change);
            }
        }
        return new Result(result, unused);
    }

    private static boolean applyOne(CompoundTag root, Change change) {
        var path = change.path();
        if (path.isEmpty()) {
            return false;
        }
        Tag at = root;
        for (int i = 0; i < path.size() - 1; i++) {
            at = child(at, path.get(i));
            if (at == null) {
                return false;
            }
        }
        var last = path.getLast();
        if (at instanceof CompoundTag compound) {
            if (change.remove()) {
                if (!compound.contains(last)) {
                    return false;
                }
                compound.remove(last);
                return true;
            }
            compound.put(last, change.value().copy());
            return true;
        }
        if (at instanceof ListTag list && !change.remove()) {
            return applyToList(list, last, change.value());
        }
        return false;
    }

    private static boolean applyToList(ListTag list, String segment, Tag value) {
        if (SIZE.equals(segment)) {
            if (!(value instanceof NumericTag number)) {
                return false;
            }
            int size = Math.max(0, number.intValue());
            // only shortens; growth is written as appended elements
            while (list.size() > size) {
                list.remove(list.size() - 1);
            }
            return true;
        }
        int index = index(segment);
        if (index < 0 || index > list.size()) {
            return false;
        }
        // replacing the only element of a list could change its type; 1.21's lists kept theirs
        if (!fits(list, value)) {
            return false;
        }
        var copy = value.copy();
        return index == list.size() ? list.addTag(index, copy) : list.setTag(index, copy);
    }

    @Nullable
    private static Tag child(Tag parent, String segment) {
        if (parent instanceof CompoundTag compound) {
            return compound.get(segment);
        }
        if (parent instanceof ListTag list) {
            int index = index(segment);
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    private static int index(String segment) {
        if (segment.isEmpty() || segment.length() > 9) {
            return -1;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Integer.parseInt(segment);
    }

    @Nullable
    public static Tag get(CompoundTag data, List<String> path) {
        Tag at = data;
        for (int i = 0; i < path.size() && at != null; i++) {
            at = child(at, path.get(i));
        }
        return at;
    }

    /** Whether one path is the other or inside it: a change at one says something about the other. */
    public static boolean overlaps(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        return a.subList(0, n).equals(b.subList(0, n));
    }

    /**
     * Whether {@code path} ends in a list's {@link #SIZE} segment in {@code data}, not a compound key that
     * happens to be called "size".
     */
    public static boolean isListSize(CompoundTag data, List<String> path) {
        if (path.isEmpty() || !SIZE.equals(path.getLast())) {
            return false;
        }
        return get(data, path.subList(0, path.size() - 1)) instanceof ListTag;
    }

    /** Whether {@code data} already says what {@code change} says, i.e. applying it would change nothing. */
    public static boolean holds(CompoundTag data, Change change) {
        var path = change.path();
        if (path.isEmpty()) {
            return false;
        }
        var at = get(data, path.subList(0, path.size() - 1));
        var last = path.getLast();
        if (change.remove()) {
            return !(at instanceof CompoundTag compound) || !compound.contains(last);
        }
        if (at instanceof ListTag list && SIZE.equals(last)) {
            return change.value() instanceof NumericTag number && list.size() <= Math.max(0, number.intValue());
        }
        return at != null && same(change.value(), child(at, last));
    }
}
