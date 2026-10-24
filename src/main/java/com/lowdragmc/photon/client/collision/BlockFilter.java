package com.lowdragmc.photon.client.collision;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;

/** Which block states a query collides with, resolved from block ids and {@code #tags} into bit sets of state ids. */
public final class BlockFilter {
    private static volatile int tags;
    /** Null: every block. */
    @Nullable
    private final BitSet only;
    private final BitSet except;

    private BlockFilter(@Nullable BitSet only, BitSet except) {
        this.only = only;
        this.except = except;
    }

    /** Bumped when the client receives new tags: filters resolved before are stale. */
    public static int tagGeneration() {
        return tags;
    }

    public static void tagsChanged() {
        tags++;
    }

    public boolean test(BlockState state) {
        int id = Block.getId(state);
        return (only == null || only.get(id)) && !except.get(id);
    }

    /**
     * The blocks in {@code only} (every block while it has no entries) that are not in {@code except}; entries
     * are ids ({@code minecraft:stone}) or tags ({@code #minecraft:leaves}). Null when neither restricts anything.
     */
    @Nullable
    public static BlockFilter of(List<String> only, List<String> except) {
        boolean restricted = hasEntries(only);
        if (!restricted && !hasEntries(except)) return null;
        return new BlockFilter(restricted ? resolve(only) : null, resolve(except));
    }

    /** Whether a list names anything; the editor's add button leaves blank entries behind. */
    public static boolean hasEntries(List<String> entries) {
        for (var entry : entries) {
            if (entry != null && !entry.isBlank()) return true;
        }
        return false;
    }

    private static BitSet resolve(List<String> entries) {
        var states = new BitSet();
        for (var entry : entries) {
            if (entry == null) continue;
            var text = entry.trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("#")) {
                var id = Identifier.tryParse(text.substring(1));
                if (id == null) continue;
                BuiltInRegistries.BLOCK.getTagOrEmpty(TagKey.create(Registries.BLOCK, id)).forEach(holder -> mark(states, holder.value()));
            } else {
                var id = Identifier.tryParse(text);
                if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> mark(states, block));
            }
        }
        return states;
    }

    private static void mark(BitSet states, Block block) {
        for (var state : block.getStateDefinition().getPossibleStates()) {
            states.set(Block.getId(state));
        }
    }
}
