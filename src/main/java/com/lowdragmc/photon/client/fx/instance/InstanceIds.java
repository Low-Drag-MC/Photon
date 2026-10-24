package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ids of placed objects. A source object {@code x} placed by instance {@code I} is {@code I ^ x} (128-bit XOR,
 * Unity's rule). XOR composes, so a nested source's objects stay addressable from any outer level, and the
 * source's root (all zeros) lands on the instance object itself.
 */
public final class InstanceIds {
    /** The id every FX root has, {@code FXRuntime.ROOT_UUID}. */
    public static final UUID ROOT = new UUID(0, 0);

    private InstanceIds() {
    }

    public static UUID xor(UUID a, UUID b) {
        return new UUID(a.getMostSignificantBits() ^ b.getMostSignificantBits(),
                a.getLeastSignificantBits() ^ b.getLeastSignificantBits());
    }

    @Nullable
    public static UUID parse(@Nullable String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException damaged) {
            return null;
        }
    }

    public static int[] toInts(UUID id) {
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        return new int[]{(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
    }

    public static UUID fromInts(int[] ints) {
        return new UUID((long) ints[0] << 32 | (long) ints[1] & 0xFFFFFFFFL,
                (long) ints[2] << 32 | (long) ints[3] & 0xFFFFFFFFL);
    }

    /** Replaces the UUIDs of a fixed map in either of the forms codecs write them: a string or a 4-int array. */
    public static final class Remapper {
        private final Map<UUID, UUID> ids;
        private final Map<String, String> strings = new HashMap<>();
        private final boolean rootArrays;

        public Remapper(Map<UUID, UUID> ids) {
            this(ids, false);
        }

        /**
         * @param rootArrays whether a 4-int array of zeros is the root's id too, as it is where timelines save their
         *                   targets; elsewhere it is more likely a value than a reference
         */
        public Remapper(Map<UUID, UUID> ids, boolean rootArrays) {
            this.ids = ids;
            this.rootArrays = rootArrays;
            ids.forEach((from, to) -> strings.put(from.toString(), to.toString()));
        }

        /** A deep copy of {@code tag} with the ids replaced. */
        public Tag apply(Tag tag) {
            return switch (tag) {
                case CompoundTag compound -> apply(compound);
                case ListTag list -> {
                    var copy = new ListTag();
                    for (var element : list) {
                        copy.add(apply(element));
                    }
                    yield copy;
                }
                case IntArrayTag array when array.size() == 4 -> {
                    var id = fromInts(array.getAsIntArray());
                    var replacement = !rootArrays && ROOT.equals(id) ? null : ids.get(id);
                    yield replacement == null ? array.copy() : new IntArrayTag(toInts(replacement));
                }
                case StringTag string -> {
                    var replacement = strings.get(string.value());
                    yield replacement == null ? string : StringTag.valueOf(replacement);
                }
                default -> tag.copy();
            };
        }

        public CompoundTag apply(CompoundTag compound) {
            var copy = new CompoundTag();
            for (var key : compound.keySet()) {
                copy.put(key, apply(compound.get(key)));
            }
            return copy;
        }

        @Nullable
        public UUID map(UUID id) {
            return ids.get(id);
        }
    }
}
