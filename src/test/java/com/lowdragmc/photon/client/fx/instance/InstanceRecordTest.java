package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lowdragmc.photon.client.fx.instance.Fixtures.id;
import static org.junit.jupiter.api.Assertions.*;

class InstanceRecordTest {

    private static InstanceRecord full() {
        return new InstanceRecord(
                List.of(new InstanceRecord.Modification(id("a"), NbtPatch.Change.set(List.of("config", "duration"), IntTag.valueOf(5))),
                        new InstanceRecord.Modification(id("b"), NbtPatch.Change.remove(List.of("config", "noise")))),
                List.of(id("c"), id("d")),
                List.of(new InstanceRecord.Added(InstanceIds.ROOT, id("e")), new InstanceRecord.Added(id("a"), id("f"))));
    }

    @Test
    void everyKindOfEntrySurvivesWritingAndReading() {
        var record = full();
        var read = InstanceRecord.read(record.write());
        assertEquals(record, read);
        assertEquals(record.modified(), read.modified());
        assertEquals(record.removed(), read.removed());
        assertEquals(record.added(), read.added());
        assertFalse(read.isPristine());
    }

    @Test
    void aPristineRecordWritesNothing() {
        assertTrue(InstanceRecord.EMPTY.write().isEmpty());
        assertTrue(InstanceRecord.read(null).isPristine());
        assertTrue(InstanceRecord.read(new CompoundTag()).isPristine());
    }

    @Test
    void aDamagedEntryIsSkippedNotMisread() {
        var tag = full().write();
        var modified = tag.getList("modified", Tag.TAG_COMPOUND);
        modified.getCompound(0).putString("target", "not-a-uuid");
        modified.getCompound(1).remove("path");
        tag.getList("removed", Tag.TAG_STRING).add(StringTag.valueOf("broken"));
        var added = tag.getList("added", Tag.TAG_COMPOUND);
        added.getCompound(1).remove("object");
        var read = InstanceRecord.read(tag);
        assertTrue(read.modified().isEmpty());
        assertEquals(List.of(id("c"), id("d")), read.removed());
        assertEquals(List.of(new InstanceRecord.Added(InstanceIds.ROOT, id("e"))), read.added());
    }

    @Test
    void entriesKeepTheirOrder() {
        var tag = full().write();
        var reversed = new ListTag();
        var removed = tag.getList("removed", Tag.TAG_STRING);
        reversed.add(removed.get(1));
        reversed.add(removed.get(0));
        tag.put("removed", reversed);
        assertEquals(List.of(id("d"), id("c")), InstanceRecord.read(tag).removed());
    }
}
