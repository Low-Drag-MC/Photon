package com.lowdragmc.photon.client.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** The collision boxes of one block state, unpacked once and shared by every query on every thread. */
public final class BlockShape {
    public static final BlockShape EMPTY = new BlockShape(new double[0], false, false);
    public static final BlockShape FULL = new BlockShape(new double[]{0, 0, 0, 1, 1, 1}, false, false);
    private static final ConcurrentHashMap<BlockState, BlockShape> BY_STATE = new ConcurrentHashMap<>();

    /** Packed {@code minX, minY, minZ, maxX, maxY, maxZ} per box, relative to the block's corner. */
    public final double[] boxes;
    /** Some box reaches outside its own cell (fences, walls). */
    public final boolean large;
    /** Resolved per cell: the shape depends on the position (random offsets) or a block entity. */
    public final boolean dynamic;

    private BlockShape(double[] boxes, boolean large, boolean dynamic) {
        this.boxes = boxes;
        this.large = large;
        this.dynamic = dynamic;
    }

    public boolean isEmpty() {
        return boxes.length == 0 && !dynamic;
    }

    public static BlockShape of(BlockState state) {
        var cached = BY_STATE.get(state);
        return cached != null ? cached : BY_STATE.computeIfAbsent(state, BlockShape::compute);
    }

    private static BlockShape compute(BlockState state) {
        if (state.isAir()) return EMPTY;
        // an offset (grass, bamboo) comes with a collision shape only on dynamic blocks: vanilla checks at startup
        if (state.getBlock().hasDynamicShape()) {
            return new BlockShape(new double[0], true, true);
        }
        // the two-argument form returns vanilla's precomputed shape: no block code runs on particle workers
        return of(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), state.hasLargeCollisionShape());
    }

    /** ⚠️ Runs the block's own code (block entities included): the level must not be changing meanwhile. */
    public static double[] at(BlockState state, BlockGetter level, BlockPos pos) {
        return pack(state.getCollisionShape(level, pos, CollisionContext.empty()));
    }

    static BlockShape of(VoxelShape shape, boolean large) {
        if (shape.isEmpty()) return EMPTY;
        if (shape == Shapes.block()) return FULL;
        var boxes = pack(shape);
        for (int i = 0; i < boxes.length && !large; i += 6) {
            large = boxes[i] < 0 || boxes[i + 1] < 0 || boxes[i + 2] < 0
                    || boxes[i + 3] > 1 || boxes[i + 4] > 1 || boxes[i + 5] > 1;
        }
        return new BlockShape(boxes, large, false);
    }

    static double[] pack(VoxelShape shape) {
        if (shape.isEmpty()) return new double[0];
        var out = new double[][]{new double[24]};
        var count = new int[1];
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            int n = count[0]++ * 6;
            if (n + 6 > out[0].length) out[0] = Arrays.copyOf(out[0], out[0].length * 2);
            var a = out[0];
            a[n] = minX;
            a[n + 1] = minY;
            a[n + 2] = minZ;
            a[n + 3] = maxX;
            a[n + 4] = maxY;
            a[n + 5] = maxZ;
        });
        return Arrays.copyOf(out[0], count[0] * 6);
    }
}
