package com.lowdragmc.photon.client.collision;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

/** An immutable copy of one 16³ section: a palette of states with their shapes and an index per cell. */
final class SectionSnapshot {
    static final SectionSnapshot EMPTY = new SectionSnapshot(new BlockState[0], new BlockShape[0], null, null,
            false, false);

    final BlockState[] palette;
    final BlockShape[] shapes;
    /** Null when the whole section is one state. */
    @Nullable
    final short[] cells;
    @Nullable
    private final Int2ObjectOpenHashMap<double[]> dynamicBoxes;
    final boolean anyLarge;
    final boolean anyFluid;
    volatile long lastUse;

    private SectionSnapshot(BlockState[] palette, BlockShape[] shapes, @Nullable short[] cells,
                            @Nullable Int2ObjectOpenHashMap<double[]> dynamicBoxes, boolean anyLarge, boolean anyFluid) {
        this.palette = palette;
        this.shapes = shapes;
        this.cells = cells;
        this.dynamicBoxes = dynamicBoxes;
        this.anyLarge = anyLarge;
        this.anyFluid = anyFluid;
    }

    static int cell(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    int paletteIndex(int cell) {
        return cells == null ? 0 : cells[cell];
    }

    double[] dynamicBoxes(int cell) {
        if (dynamicBoxes == null) return BlockShape.EMPTY.boxes;
        var boxes = dynamicBoxes.get(cell);
        return boxes == null ? BlockShape.EMPTY.boxes : boxes;
    }

    /** Reads {@code section} when given, otherwise every cell through {@code level}; dynamic shapes always through {@code level}. */
    static SectionSnapshot build(BlockGetter level, int sectionX, int sectionY, int sectionZ,
                                 @Nullable LevelChunkSection section) {
        if (section != null && section.hasOnlyAir()) return EMPTY;
        var indices = new Reference2IntOpenHashMap<BlockState>();
        indices.defaultReturnValue(-1);
        var states = new ArrayList<BlockState>();
        var cells = new short[4096];
        var pos = new BlockPos.MutableBlockPos();
        int baseX = sectionX << 4, baseY = sectionY << 4, baseZ = sectionZ << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    var state = section != null ? section.getBlockState(x, y, z)
                            : level.getBlockState(pos.set(baseX + x, baseY + y, baseZ + z));
                    int index = indices.getInt(state);
                    if (index < 0) {
                        index = states.size();
                        states.add(state);
                        indices.put(state, index);
                    }
                    cells[y << 8 | z << 4 | x] = (short) index;
                }
            }
        }
        var palette = states.toArray(new BlockState[0]);
        var shapes = new BlockShape[palette.length];
        boolean anyLarge = false, anyFluid = false, anySolid = false, anyDynamic = false;
        for (int i = 0; i < palette.length; i++) {
            shapes[i] = BlockShape.of(palette[i]);
            anyLarge |= shapes[i].large;
            anySolid |= !shapes[i].isEmpty();
            anyDynamic |= shapes[i].dynamic;
            anyFluid |= !palette[i].getFluidState().isEmpty();
        }
        if (!anySolid && !anyFluid) return EMPTY;
        Int2ObjectOpenHashMap<double[]> dynamic = null;
        if (anyDynamic) {
            dynamic = new Int2ObjectOpenHashMap<>();
            for (int cell = 0; cell < 4096; cell++) {
                int index = cells[cell];
                if (!shapes[index].dynamic) continue;
                pos.set(baseX + (cell & 15), baseY + (cell >> 8), baseZ + (cell >> 4 & 15));
                dynamic.put(cell, BlockShape.at(palette[index], level, pos));
            }
        }
        return new SectionSnapshot(palette, shapes, palette.length == 1 ? null : cells, dynamic, anyLarge, anyFluid);
    }
}
