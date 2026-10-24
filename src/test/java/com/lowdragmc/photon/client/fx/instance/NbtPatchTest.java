package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lowdragmc.photon.client.fx.instance.Fixtures.constant;
import static com.lowdragmc.photon.client.fx.instance.Fixtures.curve;
import static com.lowdragmc.photon.client.fx.instance.Fixtures.floats;
import static org.junit.jupiter.api.Assertions.*;

class NbtPatchTest {

    private static ListTag ints(int... values) {
        var list = new ListTag();
        for (var value : values) list.add(IntTag.valueOf(value));
        return list;
    }

    private static CompoundTag sample() {
        var tag = new CompoundTag();
        tag.putInt("leaf", 1);
        tag.putString("name", "a");
        var inner = new CompoundTag();
        inner.putInt("x", 1);
        inner.putInt("y", 2);
        tag.put("inner", inner);
        tag.put("list", ints(1, 2, 3));
        var compounds = new ListTag();
        var first = new CompoundTag();
        first.putInt("v", 1);
        var second = new CompoundTag();
        second.putInt("v", 2);
        compounds.add(first);
        compounds.add(second);
        tag.put("compounds", compounds);
        tag.putInt("gone", 7);
        return tag;
    }

    @Test
    void applyingTheDiffGivesTheTargetForEveryShape() {
        var from = sample();
        var to = sample();
        to.putInt("leaf", 9);
        to.putString("added", "new");
        to.remove("gone");
        to.getCompound("inner").putInt("y", 5);
        to.put("list", ints(1, 7));
        to.getList("compounds", Tag.TAG_COMPOUND).getCompound(1).putInt("v", 8);
        to.putString("name", "b");
        var list = new ListTag();
        list.add(StringTag.valueOf("s"));
        to.put("typed", list);
        from.put("typed", ints(1));

        var diff = NbtPatch.diff(from, to);
        var result = NbtPatch.apply(from, diff);
        assertEquals(to, result.tag());
        assertTrue(result.unused().isEmpty());
        // and the source was not touched
        assertEquals(sample().get("leaf"), from.get("leaf"));
    }

    @Test
    void anEmptyListGrowsByAppendedElements() {
        var from = new CompoundTag();
        from.put("list", new ListTag());
        var to = new CompoundTag();
        to.put("list", ints(4, 5));
        var diff = NbtPatch.diff(from, to);
        assertEquals(List.of(NbtPatch.Change.set(List.of("list", "0"), IntTag.valueOf(4)),
                NbtPatch.Change.set(List.of("list", "1"), IntTag.valueOf(5))), diff);
        assertEquals(to, NbtPatch.apply(from, diff).tag());
    }

    @Test
    void whatAListCannotTakeIsUnused() {
        var onto = new CompoundTag();
        onto.put("list", ints(1, 2));
        var otherType = NbtPatch.Change.set(List.of("list", "0"), StringTag.valueOf("s"));
        var removal = NbtPatch.Change.remove(List.of("list", "0"));
        var result = NbtPatch.apply(onto, List.of(otherType, removal));
        assertEquals(List.of(otherType, removal), result.unused());
        assertEquals(onto, result.tag());
    }

    @Test
    void aRemovalHoldsWhenThereIsNothingToRemove() {
        var data = new CompoundTag();
        var removal = NbtPatch.Change.remove(List.of("absent", "key"));
        assertTrue(NbtPatch.holds(data, removal));
        data.put("absent", new CompoundTag());
        assertTrue(NbtPatch.holds(data, removal));
        data.getCompound("absent").putInt("key", 1);
        assertFalse(NbtPatch.holds(data, removal));
    }

    @Test
    void pathsOverlapWhenOneHoldsTheOther() {
        assertTrue(NbtPatch.overlaps(List.of("a", "b"), List.of("a", "b")));
        assertTrue(NbtPatch.overlaps(List.of("a"), List.of("a", "b", "c")));
        assertTrue(NbtPatch.overlaps(List.of("a", "b", "c"), List.of("a")));
        assertFalse(NbtPatch.overlaps(List.of("a", "b"), List.of("a", "c")));
        assertFalse(NbtPatch.overlaps(List.of("ab"), List.of("a")));
    }

    @Test
    void longerListsAreAppendedElements() {
        var from = new CompoundTag();
        from.put("list", ints(1));
        var to = new CompoundTag();
        to.put("list", ints(1, 2, 3));
        var diff = NbtPatch.diff(from, to);
        assertEquals(List.of(NbtPatch.Change.set(List.of("list", "1"), IntTag.valueOf(2)),
                NbtPatch.Change.set(List.of("list", "2"), IntTag.valueOf(3))), diff);
        assertEquals(to, NbtPatch.apply(from, diff).tag());
    }

    /** A whole-compound replacement would record one change at "inner". */
    @Test
    void compoundsDiffKeyByKey() {
        var from = sample();
        var to = sample();
        to.getCompound("inner").putInt("y", 3);
        assertEquals(List.of(NbtPatch.Change.set(List.of("inner", "y"), IntTag.valueOf(3))), NbtPatch.diff(from, to));
    }

    /** A whole-list replacement would record one change at "list". */
    @Test
    void listsDiffByPosition() {
        var from = sample();
        var to = sample();
        to.put("list", ints(1, 5, 3));
        assertEquals(List.of(NbtPatch.Change.set(List.of("list", "1"), IntTag.valueOf(5))), NbtPatch.diff(from, to));
    }

    /** Ignoring the size change would leave the third element in place. */
    @Test
    void aShorterListRecordsItsSize() {
        var from = sample();
        var to = sample();
        to.put("list", ints(1, 2));
        var diff = NbtPatch.diff(from, to);
        assertEquals(List.of(NbtPatch.Change.set(List.of("list", NbtPatch.SIZE), IntTag.valueOf(2))), diff);
        assertEquals(ints(1, 2), NbtPatch.apply(from, diff).tag().get("list"));
        assertTrue(NbtPatch.isListSize(from, diff.getFirst().path()));
    }

    @Test
    void aCompoundKeyCalledSizeIsAKeyNotALength() {
        var from = new CompoundTag();
        var inner = new CompoundTag();
        inner.putInt("size", 1);
        from.put("c", inner);
        var to = from.copy();
        to.getCompound("c").putInt("size", 2);
        var diff = NbtPatch.diff(from, to);
        assertEquals(List.of(NbtPatch.Change.set(List.of("c", "size"), IntTag.valueOf(2))), diff);
        assertFalse(NbtPatch.isListSize(from, diff.getFirst().path()));
        assertEquals(to, NbtPatch.apply(from, diff).tag());
    }

    @Test
    void whatDoesNotResolveIsReturnedAsUnused() {
        var onto = sample();
        var throughMissing = NbtPatch.Change.set(List.of("absent", "x"), IntTag.valueOf(1));
        var pastTheEnd = NbtPatch.Change.set(List.of("list", "9"), IntTag.valueOf(1));
        var removeAbsent = NbtPatch.Change.remove(List.of("inner", "z"));
        var intoALeaf = NbtPatch.Change.set(List.of("leaf", "x"), IntTag.valueOf(1));
        var good = NbtPatch.Change.set(List.of("inner", "x"), IntTag.valueOf(4));
        var result = NbtPatch.apply(onto, List.of(throughMissing, pastTheEnd, removeAbsent, intoALeaf, good));
        assertEquals(List.of(throughMissing, pastTheEnd, removeAbsent, intoALeaf), result.unused());
        assertEquals(4, result.tag().getCompound("inner").getInt("x"));
    }

    @Test
    void theSameValuesAlwaysGiveTheSameChanges() {
        var from = sample();
        var to = sample();
        to.putInt("zeta", 1);
        to.putInt("alpha", 1);
        to.putInt("leaf", 3);
        var first = NbtPatch.diff(from, to);
        var second = NbtPatch.diff(from.copy(), to.copy());
        assertEquals(first, second);
        // sorted by key
        assertEquals(List.of("alpha"), first.get(0).path());
        assertEquals(List.of("leaf"), first.get(1).path());
        assertEquals(List.of("zeta"), first.get(2).path());
    }

    @Test
    void excludedPathsAreNeverRecorded() {
        var from = sample();
        var to = sample();
        to.getCompound("inner").putInt("x", 99);
        to.remove("gone");
        to.putInt("leaf", 2);
        var rules = new NbtPatch.Rules() {
            @Override
            public boolean excluded(List<String> path) {
                return path.equals(List.of("inner", "x")) || path.equals(List.of("gone"));
            }
        };
        assertEquals(List.of(NbtPatch.Change.set(List.of("leaf"), IntTag.valueOf(2))), NbtPatch.diff(from, to, rules));
    }

    @Test
    void atomicValuesChangeWhole() {
        var from = new CompoundTag();
        from.put("rotation", floats(0, 0, 0, 1));
        from.put("lifetime", constant(1));
        var to = new CompoundTag();
        to.put("rotation", floats(0, 0.7f, 0, 0.7f));
        to.put("lifetime", curve(0, 1, 1, 0));
        var rules = InstanceDiffRules.of("particle_emitter");
        // the fx rules know rotation by its full path; here the same check by name
        var named = new NbtPatch.Rules() {
            @Override
            public boolean atomic(List<String> path, Tag a, Tag b) {
                return path.equals(List.of("rotation")) || rules.atomic(path, a, b);
            }
        };
        var diff = NbtPatch.diff(from, to, named);
        assertEquals(List.of(NbtPatch.Change.set(List.of("lifetime"), curve(0, 1, 1, 0)),
                NbtPatch.Change.set(List.of("rotation"), floats(0, 0.7f, 0, 0.7f))), diff);
    }

    @Test
    void holdsTellsWhetherApplyingWouldChangeAnything() {
        var from = sample();
        var to = sample();
        to.putInt("leaf", 5);
        to.put("list", ints(1));
        to.remove("gone");
        var diff = NbtPatch.diff(from, to);
        var applied = NbtPatch.apply(from, diff).tag();
        for (var change : diff) {
            assertTrue(NbtPatch.holds(applied, change), change.describePath());
            assertFalse(NbtPatch.holds(from, change), change.describePath());
        }
    }

    @Test
    void changesSurviveWritingAndReading() {
        var set = NbtPatch.Change.set(List.of("a", "0", "b"), constant(3));
        var remove = NbtPatch.Change.remove(List.of("a", "c"));
        assertEquals(set, NbtPatch.Change.read(set.write()));
        assertEquals(remove, NbtPatch.Change.read(remove.write()));
        assertNull(NbtPatch.Change.read(new CompoundTag()));
        var valueless = new CompoundTag();
        valueless.put("path", new ListTag());
        assertNull(NbtPatch.Change.read(valueless));
        var noValue = NbtPatch.Change.set(List.of("a"), IntTag.valueOf(1)).write();
        noValue.remove("value");
        assertNull(NbtPatch.Change.read(noValue));
    }

    @Test
    void getFollowsKeysAndIndexes() {
        var tag = sample();
        assertEquals(IntTag.valueOf(2), NbtPatch.get(tag, List.of("compounds", "1", "v")));
        assertNull(NbtPatch.get(tag, List.of("compounds", "5", "v")));
        assertNull(NbtPatch.get(tag, List.of("leaf", "x")));
    }
}
