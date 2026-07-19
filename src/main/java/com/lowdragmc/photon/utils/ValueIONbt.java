package com.lowdragmc.photon.utils;

import com.lowdragmc.photon.Photon;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

/**
 * Tag-level bridges for {@link ValueIOSerializable} values. 26.1 removed INBTSerializable's direct
 * {@code CompoundTag} methods; Photon's editor resources and copy/paste payloads still speak raw
 * NBT, so these wrap the standard TagValueOutput/TagValueInput dance in one place.
 */
public final class ValueIONbt {

    private ValueIONbt() {
    }

    public static CompoundTag toTag(ValueIOSerializable value, HolderLookup.Provider provider) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, provider);
            value.serialize(output);
            return output.buildResult();
        }
    }

    public static void fromTag(ValueIOSerializable value, HolderLookup.Provider provider, CompoundTag tag) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            value.deserialize(TagValueInput.create(reporter, provider, tag));
        }
    }
}
