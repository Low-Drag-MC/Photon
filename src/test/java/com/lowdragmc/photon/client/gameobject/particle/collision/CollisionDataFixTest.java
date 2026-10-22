package com.lowdragmc.photon.client.gameobject.particle.collision;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollisionDataFixTest {
    @Test void nativeDataWithoutLegacySettingsIsUnchanged() {
        var data = new CompoundTag();
        data.putString("name", "Original particle");
        assertSame(data, CollisionDataFix.migrate(data));
        assertFalse(data.contains("config"));
    }

    @Test void migratesAllLegacyFieldsWithoutEnablingPhysicsOrChangingTransforms() {
        var original = new CompoundTag();
        var legacy = new CompoundTag();
        legacy.putString("backend", "CACHED_SWEEP");
        legacy.putBoolean("entities", true);
        legacy.putString("entityFilter", "LIVING");
        legacy.putString("deduplicationGroup", "ring");
        original.put("collision", legacy);
        original.putString("name", "Authored collision probe");
        original.putString("transform", "preserve");
        var config = new CompoundTag();
        var physics = new CompoundTag();
        physics.putBoolean("_enable", false);
        physics.putBoolean("removedWhenCollided", true);
        config.put("physics", physics);
        original.put("config", config);
        var migrated = CollisionDataFix.migrate(original);
        assertEquals(legacy, migrated.getCompound("config").getCompound("physics").getCompound("collision"));
        assertFalse(migrated.getCompound("config").getCompound("physics").getBoolean("_enable"));
        assertTrue(migrated.getCompound("config").getCompound("physics").getBoolean("removedWhenCollided"));
        assertEquals(original.getString("name"), migrated.getString("name"));
        assertEquals(original.getString("transform"), migrated.getString("transform"));
        assertTrue(original.contains("collision"));
        assertFalse(migrated.contains("collision"));
        assertSame(migrated, CollisionDataFix.migrate(migrated));
    }

    @Test void nativeSettingsTakePrecedenceWithoutLosingLegacyValues() {
        var data = new CompoundTag();
        var legacy = new CompoundTag();
        legacy.putString("backend", "CACHED_SWEEP");
        legacy.putBoolean("entities", true);
        data.put("collision", legacy);
        var nativeSettings = new CompoundTag();
        nativeSettings.putString("backend", "NATIVE");
        var physics = new CompoundTag();
        physics.put("collision", nativeSettings);
        var config = new CompoundTag();
        config.put("physics", physics);
        data.put("config", config);
        var migrated = CollisionDataFix.migrate(data).getCompound("config").getCompound("physics").getCompound("collision");
        assertEquals("NATIVE", migrated.getString("backend"));
        assertTrue(migrated.getBoolean("entities"));
        assertEquals("CACHED_SWEEP", data.getCompound("collision").getString("backend"));
    }
}
