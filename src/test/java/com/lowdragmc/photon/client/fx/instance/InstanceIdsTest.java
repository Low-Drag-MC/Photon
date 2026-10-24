package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InstanceIdsTest {
    private static final UUID A = UUID.fromString("2303eb24-ec9d-4917-ba32-ddc3b0119100");
    private static final UUID B = UUID.fromString("cda80386-df4c-4911-aa93-5f7cba3b8095");
    private static final UUID C = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    @Test
    void xorUndoesItselfAndComposes() {
        assertEquals(A, InstanceIds.xor(InstanceIds.xor(A, B), B));
        assertEquals(InstanceIds.xor(A, InstanceIds.xor(B, C)), InstanceIds.xor(InstanceIds.xor(A, B), C));
        // the source root lands on the instance itself
        assertEquals(A, InstanceIds.xor(A, InstanceIds.ROOT));
        assertNotEquals(A, InstanceIds.xor(A, B));
    }

    /** The layout {@code UUIDUtil.uuidToIntArray} writes, which timeline tracks save their targets in. */
    @Test
    void intArraysAreMinecraftsLayout() {
        var id = new UUID(0x0102030405060708L, 0x090a0b0c0d0e0f10L);
        assertArrayEquals(new int[]{0x01020304, 0x05060708, 0x090a0b0c, 0x0d0e0f10}, InstanceIds.toInts(id));
        assertEquals(id, InstanceIds.fromInts(InstanceIds.toInts(id)));
        var negative = new UUID(-1L, Long.MIN_VALUE);
        assertEquals(negative, InstanceIds.fromInts(InstanceIds.toInts(negative)));
    }

    @Test
    void parseTakesOnlyWholeUuids() {
        assertEquals(A, InstanceIds.parse(A.toString()));
        assertNull(InstanceIds.parse(null));
        assertNull(InstanceIds.parse(""));
        assertNull(InstanceIds.parse("not-a-uuid"));
        assertNull(InstanceIds.parse(A + "x"));
    }

    @Test
    void fourZerosAreTheRootOnlyWhereTimelinesSaveTargets() {
        var tag = new CompoundTag();
        tag.put("zeros", new IntArrayTag(new int[4]));
        tag.putString("parent", InstanceIds.ROOT.toString());
        var map = Map.of(InstanceIds.ROOT, A);
        var data = new InstanceIds.Remapper(map).apply(tag);
        assertArrayEquals(new int[4], data.getIntArray("zeros").orElseThrow(), "four zeros in an object's data are a value");
        assertEquals(A.toString(), data.getStringOr("parent", ""), "the root's id written as a string is always a reference");
        assertEquals(A, InstanceIds.fromInts(new InstanceIds.Remapper(map, true).apply(tag).getIntArray("zeros").orElseThrow()));
    }

    @Test
    void remappingReplacesBothFormsEverywhereAndNothingElse() {
        var tag = new CompoundTag();
        tag.putString("ref", A.toString());
        tag.putString("partial", "prefix " + A);
        tag.putString("other", B.toString());
        tag.put("track", new IntArrayTag(InstanceIds.toInts(A)));
        tag.put("short", new IntArrayTag(new int[]{1, 2, 3}));
        var list = new ListTag();
        list.add(StringTag.valueOf(A.toString()));
        list.add(StringTag.valueOf(C.toString()));
        var inner = new CompoundTag();
        inner.put("list", list);
        tag.put("inner", inner);
        var before = tag.copy();

        var remapped = new InstanceIds.Remapper(Map.of(A, C)).apply(tag);

        assertEquals(C.toString(), remapped.getStringOr("ref", ""));
        assertEquals("prefix " + A, remapped.getStringOr("partial", ""));
        assertEquals(B.toString(), remapped.getStringOr("other", ""));
        assertEquals(C, InstanceIds.fromInts(remapped.getIntArray("track").orElseThrow()));
        assertArrayEquals(new int[]{1, 2, 3}, remapped.getIntArray("short").orElseThrow());
        var remappedList = remapped.getCompoundOrEmpty("inner").getListOrEmpty("list");
        assertEquals(C.toString(), remappedList.getStringOr(0, ""));
        assertEquals(C.toString(), remappedList.getStringOr(1, ""));
        assertEquals(before, tag, "the input is not modified");
    }
}
