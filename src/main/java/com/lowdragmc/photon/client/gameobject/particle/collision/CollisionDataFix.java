package com.lowdragmc.photon.client.gameobject.particle.collision;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public final class CollisionDataFix {
    private CollisionDataFix() {}

    public static CompoundTag migrate(CompoundTag data) {
        if (!data.contains("collision", Tag.TAG_COMPOUND)) return data;
        var migrated = data.copy();
        var config = migrated.getCompound("config");
        var physics = config.getCompound("physics");
        var collision = migrated.getCompound("collision").copy();
        collision.merge(physics.getCompound("collision"));
        physics.put("collision", collision);
        config.put("physics", physics);
        migrated.put("config", config);
        migrated.remove("collision");
        return migrated;
    }
}
