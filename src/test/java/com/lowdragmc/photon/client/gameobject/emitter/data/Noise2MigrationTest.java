package com.lowdragmc.photon.client.gameobject.emitter.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Noise2MigrationTest {
    private static CompoundTag value(String marker) {
        var value = new CompoundTag();
        value.putString("marker", marker);
        return value;
    }

    @Test
    void legacyUnifiedValuesReplaceThePreviouslyInactiveAxisValues() {
        var old = new CompoundTag();
        old.putBoolean("separateAxes", false);
        old.put("strength", value("active strength"));
        old.put("strengthAxes", new ListTag());
        var remap = new CompoundTag();
        remap.putBoolean("_enable", true);
        remap.put("curve", value("active curve"));
        old.put("remap", remap);
        var migrated = Noise2Setting.migrateAxes(old);
        var strengths = migrated.getList("strengthAxes", Tag.TAG_COMPOUND);
        var curves = migrated.getCompound("remap").getList("axes", Tag.TAG_COMPOUND);
        assertEquals(3, strengths.size());
        assertEquals(3, curves.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(value("active strength"), strengths.getCompound(i));
            assertEquals(value("active curve"), curves.getCompound(i));
        }
        strengths.getCompound(0).putString("marker", "edited X");
        assertEquals("active strength", strengths.getCompound(1).getString("marker"));
        assertEquals(0, old.getList("strengthAxes", Tag.TAG_COMPOUND).size());
        assertFalse(old.getCompound("remap").contains("axes"));
    }

    @Test
    void legacySeparateAndCurrentFilesPreserveAxisData() {
        for (boolean legacy : new boolean[]{false, true}) {
            var tag = new CompoundTag();
            if (legacy) tag.putBoolean("separateAxes", true);
            tag.put("strength", value("unused"));
            var axes = new ListTag();
            axes.add(value("X"));
            axes.add(value("Y"));
            axes.add(value("Z"));
            tag.put("strengthAxes", axes);
            var remap = new CompoundTag();
            remap.put("axes", axes.copy());
            tag.put("remap", remap);
            assertEquals(tag, Noise2Setting.migrateAxes(tag));
        }
    }
}
