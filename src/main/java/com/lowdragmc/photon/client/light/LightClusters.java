package com.lowdragmc.photon.client.light;

import com.mojang.blaze3d.buffers.GpuBuffer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Clustered light culling, built on the CPU: {@link #TILE}-pixel screen tiles times {@link #SLICES}
 * exponential depth slices. A pixel loops only over the lights whose bounding box covers its cell.
 * Volumetric lights also get a per-tile list at any depth, since a view ray crosses every slice.
 * <p>
 * Each list goes up as a texel buffer, laid out as {@code dynamic_light.glsl} reads it: the grid is
 * (offset, count) per cell, two cells to an RGBA32F texel; the index list is four entries to a texel.
 */
final class LightClusters {
    static final int TILE = 64;
    static final int SLICES = 16;
    static final float NEAR = 0.5f;
    static final float FAR = 768f;
    static final float SLICE_SCALE = (float) (SLICES / Math.log(FAR / NEAR));
    private static final int MAX_ENTRIES = 1 << 22;

    private static int tilesX = 1;
    private static int tilesY = 1;
    private static final int[] RECTS = new int[DynamicLightRenderer.MAX_LIGHTS * 6];
    private static final Vector4f CORNER = new Vector4f();
    private static final Grid CLUSTERS = new Grid(SLICES, "Photon light clusters");
    private static final Grid VOLUME = new Grid(1, "Photon volume tiles");

    private LightClusters() {
    }

    static int tilesX() {
        return tilesX;
    }

    static int tilesY() {
        return tilesY;
    }

    /** {@code view}: view-space x, y, z and range per light. */
    static void build(float[] view, int count, Matrix4f projection, int width, int height) {
        tilesX = Math.max(1, (width + TILE - 1) / TILE);
        tilesY = Math.max(1, (height + TILE - 1) / TILE);
        for (int i = 0; i < count; i++) {
            float x = view[i * 4], y = view[i * 4 + 1], z = view[i * 4 + 2], r = view[i * 4 + 3];
            float nearDepth = -z - r;
            float farDepth = -z + r;
            if (farDepth <= 0f) {
                // behind the camera; an editor frame has no frustum to cull it
                RECTS[i * 6] = 1; // empty marker: tx0 > tx1
                RECTS[i * 6 + 1] = 0;
                continue;
            }
            int tx0 = 0, tx1 = tilesX - 1, ty0 = 0, ty1 = tilesY - 1;
            if (nearDepth > 0.1f) {
                // in front of the camera, so the projected box corners bound the light, unless a warped
                // projection (nausea, view bobbing) puts a corner behind the eye
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                boolean bounded = true;
                for (int c = 0; c < 8; c++) {
                    projection.transform(CORNER.set(x + ((c & 1) == 0 ? -r : r), y + ((c & 2) == 0 ? -r : r),
                            z + ((c & 4) == 0 ? -r : r), 1f));
                    if (CORNER.w <= 1e-3f) {
                        bounded = false;
                        break;
                    }
                    float sx = (CORNER.x / CORNER.w * 0.5f + 0.5f) * width;
                    float sy = (CORNER.y / CORNER.w * 0.5f + 0.5f) * height;
                    minX = Math.min(minX, sx);
                    maxX = Math.max(maxX, sx);
                    minY = Math.min(minY, sy);
                    maxY = Math.max(maxY, sy);
                }
                if (bounded) {
                    if (maxX < 0 || maxY < 0 || minX >= width || minY >= height) {
                        RECTS[i * 6] = 1;
                        RECTS[i * 6 + 1] = 0;
                        continue;
                    }
                    tx0 = Math.max(0, (int) Math.floor(minX) / TILE);
                    tx1 = Math.min(tilesX - 1, (int) Math.floor(maxX) / TILE);
                    ty0 = Math.max(0, (int) Math.floor(minY) / TILE);
                    ty1 = Math.min(tilesY - 1, (int) Math.floor(maxY) / TILE);
                }
            }
            RECTS[i * 6] = tx0;
            RECTS[i * 6 + 1] = tx1;
            RECTS[i * 6 + 2] = ty0;
            RECTS[i * 6 + 3] = ty1;
            RECTS[i * 6 + 4] = slice(Math.max(nearDepth, NEAR));
            RECTS[i * 6 + 5] = slice(farDepth);
        }
        CLUSTERS.fill(count, null);
    }

    /** After {@link #build}: {@code volumeIndex[i]} is light i's slot in the volumetric list, or -1. */
    static void buildVolume(int[] volumeIndex, int count) {
        VOLUME.fill(count, volumeIndex);
    }

    static int slice(float depth) {
        if (depth <= NEAR) return 0;
        return Math.min(SLICES - 1, (int) Math.floor(Math.log(depth / NEAR) * SLICE_SCALE));
    }

    static GpuBuffer clusterGrid() {
        return CLUSTERS.grid.current();
    }

    static GpuBuffer clusterIndices() {
        return CLUSTERS.indices.current();
    }

    static GpuBuffer volumeGrid() {
        return VOLUME.grid.current();
    }

    static GpuBuffer volumeIndices() {
        return VOLUME.indices.current();
    }

    static void release() {
        CLUSTERS.release();
        VOLUME.release();
    }

    /** Lights per cell as (offset, count) into an index list, uploaded for a shader. */
    private static final class Grid {
        private final int slices;
        private int[] counts = new int[0];
        private int[] offsets = new int[0];
        // every value is an integer below 2^24, exact in a float
        private FloatBuffer cells = MemoryUtil.memAllocFloat(4);
        private FloatBuffer entries = MemoryUtil.memAllocFloat(4);
        private final LightBuffer grid;
        private final LightBuffer indices;

        private Grid(int slices, String label) {
            this.slices = slices;
            this.grid = LightBuffer.texels(label + " grid");
            this.indices = LightBuffer.texels(label + " indices");
        }

        /** {@code remap}: the index stored for light i, -1 to leave it out; {@code null} stores i itself. */
        private void fill(int count, @Nullable int[] remap) {
            int cellCount = tilesX * tilesY * slices;
            if (counts.length < cellCount) {
                counts = new int[cellCount];
                offsets = new int[cellCount];
            }
            Arrays.fill(counts, 0, cellCount, 0);
            int rowStride = tilesX * slices;
            int total = 0;
            // count, then fill
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < count; i++) {
                    int tx0 = RECTS[i * 6], tx1 = RECTS[i * 6 + 1];
                    if (tx0 > tx1 || remap != null && remap[i] < 0) continue;
                    int s0 = slices == 1 ? 0 : RECTS[i * 6 + 4];
                    int s1 = slices == 1 ? 0 : RECTS[i * 6 + 5];
                    int value = remap == null ? i : remap[i];
                    for (int ty = RECTS[i * 6 + 2]; ty <= RECTS[i * 6 + 3]; ty++) {
                        for (int s = s0; s <= s1; s++) {
                            int row = ty * rowStride + s * tilesX;
                            for (int tx = tx0; tx <= tx1; tx++) {
                                int cell = row + tx;
                                if (pass == 0) {
                                    counts[cell]++;
                                    continue;
                                }
                                int slot = offsets[cell] + counts[cell];
                                if (slot < MAX_ENTRIES) {
                                    entries.put(slot, value);
                                    counts[cell]++;
                                }
                            }
                        }
                    }
                }
                if (pass == 0) {
                    total = 0;
                    for (int c = 0; c < cellCount; c++) {
                        offsets[c] = total;
                        total += counts[c];
                    }
                    total = Math.min(total, MAX_ENTRIES);
                    if (entries.capacity() < total + 4) {
                        MemoryUtil.memFree(entries);
                        entries = MemoryUtil.memAllocFloat(Math.max(total + 4, entries.capacity() * 3 / 2));
                    }
                    Arrays.fill(counts, 0, cellCount, 0);
                }
            }
            if (cells.capacity() < cellCount * 2 + 4) {
                MemoryUtil.memFree(cells);
                cells = MemoryUtil.memAllocFloat(cellCount * 2 + 4);
            }
            for (int c = 0; c < cellCount; c++) {
                cells.put(c * 2, offsets[c]);
                cells.put(c * 2 + 1, counts[c]);
            }
            grid.upload(cells.position(0).limit(cellCount * 2));
            indices.upload(entries.position(0).limit(total));
            cells.clear();
            entries.clear();
        }

        private void release() {
            grid.release();
            indices.release();
        }
    }
}
