package com.lowdragmc.photon.client.light;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Clustered light culling, built on the CPU: {@link #TILE}-pixel screen tiles times {@link #SLICES}
 * exponential depth slices. A pixel loops only over the lights whose bounding box covers its cell.
 */
@OnlyIn(Dist.CLIENT)
final class LightClusters {
    static final int TILE = 64;
    static final int SLICES = 16;
    static final float NEAR = 0.5f;
    static final float FAR = 768f;
    static final float SLICE_SCALE = (float) (SLICES / Math.log(FAR / NEAR));
    private static final int INDEX_WIDTH = 4096;
    private static final int MAX_ENTRIES = 1 << 22;

    private static int tilesX = 1;
    private static int tilesY = 1;
    private static int[] counts = new int[0];
    private static int[] offsets = new int[0];
    private static final int[] RECTS = new int[DynamicLightRenderer.MAX_LIGHTS * 6];
    // float, not integer textures: lit particle shaders null their unused samplers, and a nulled usampler
    // would sit on unit 0 next to a sampler2D. Every value is an integer below 2^24, exact in a float.
    private static FloatBuffer grid = MemoryUtil.memAllocFloat(2);
    private static FloatBuffer indices = MemoryUtil.memAllocFloat(INDEX_WIDTH);
    private static int gridTexture = -1;
    private static int indexTexture = -1;
    private static int gridWidth;
    private static int gridHeight;
    private static int indexRows;
    private static final Vector4f CORNER = new Vector4f();

    private LightClusters() {
    }

    /** {@code view}: view-space x, y, z and range per light. */
    static void build(float[] view, int count, Matrix4f projection, int width, int height) {
        tilesX = Math.max(1, (width + TILE - 1) / TILE);
        tilesY = Math.max(1, (height + TILE - 1) / TILE);
        int cells = tilesX * tilesY * SLICES;
        if (counts.length < cells) {
            counts = new int[cells];
            offsets = new int[cells];
        }
        Arrays.fill(counts, 0, cells, 0);
        int rowStride = tilesX * SLICES;

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
            int s0 = slice(Math.max(nearDepth, NEAR));
            int s1 = slice(farDepth);
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
            RECTS[i * 6 + 4] = s0;
            RECTS[i * 6 + 5] = s1;
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int s = s0; s <= s1; s++) {
                    int row = ty * rowStride + s * tilesX;
                    for (int tx = tx0; tx <= tx1; tx++) {
                        counts[row + tx]++;
                    }
                }
            }
        }

        int total = 0;
        for (int c = 0; c < cells; c++) {
            offsets[c] = total;
            total += counts[c];
        }
        total = Math.min(total, MAX_ENTRIES);
        int rows = Math.max(1, (total + INDEX_WIDTH - 1) / INDEX_WIDTH);
        if (indices.capacity() < rows * INDEX_WIDTH) {
            MemoryUtil.memFree(indices);
            indices = MemoryUtil.memAllocFloat(rows * INDEX_WIDTH);
        }
        Arrays.fill(counts, 0, cells, 0);
        for (int i = 0; i < count; i++) {
            int tx0 = RECTS[i * 6], tx1 = RECTS[i * 6 + 1];
            if (tx0 > tx1) continue;
            for (int ty = RECTS[i * 6 + 2]; ty <= RECTS[i * 6 + 3]; ty++) {
                for (int s = RECTS[i * 6 + 4]; s <= RECTS[i * 6 + 5]; s++) {
                    int row = ty * rowStride + s * tilesX;
                    for (int tx = tx0; tx <= tx1; tx++) {
                        int cell = row + tx;
                        int slot = offsets[cell] + counts[cell];
                        if (slot < MAX_ENTRIES) {
                            indices.put(slot, i);
                            counts[cell]++;
                        }
                    }
                }
            }
        }

        if (grid.capacity() < cells * 2) {
            MemoryUtil.memFree(grid);
            grid = MemoryUtil.memAllocFloat(cells * 2);
        }
        for (int c = 0; c < cells; c++) {
            grid.put(c * 2, offsets[c]);
            grid.put(c * 2 + 1, counts[c]);
        }
        upload(rowStride, tilesY, rows);
    }

    static int slice(float depth) {
        if (depth <= NEAR) return 0;
        return Math.min(SLICES - 1, (int) Math.floor(Math.log(depth / NEAR) * SLICE_SCALE));
    }

    private static void upload(int width, int height, int rows) {
        resetUnpack();
        if (gridTexture == -1) gridTexture = newDataTexture();
        GlStateManager._bindTexture(gridTexture);
        grid.limit(width * height * 2).position(0);
        if (width != gridWidth || height != gridHeight) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, width, height, 0, GL30.GL_RG, GL11.GL_FLOAT, grid);
            gridWidth = width;
            gridHeight = height;
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL30.GL_RG, GL11.GL_FLOAT, grid);
        }
        grid.clear();

        if (indexTexture == -1) indexTexture = newDataTexture();
        GlStateManager._bindTexture(indexTexture);
        indices.limit(rows * INDEX_WIDTH).position(0);
        if (rows > indexRows) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, INDEX_WIDTH, rows, 0, GL11.GL_RED, GL11.GL_FLOAT, indices);
            indexRows = rows;
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, INDEX_WIDTH, rows, GL11.GL_RED, GL11.GL_FLOAT, indices);
        }
        indices.clear();
        GlStateManager._bindTexture(0);
    }

    /** {@code originX, originY}: where the viewport the clusters were built for starts in the target being drawn. */
    static void bind(ShaderInstance shader, int originX, int originY) {
        shader.setSampler("PhotonClusterGrid", gridTexture);
        shader.setSampler("PhotonClusterIndices", indexTexture);
        shader.safeGetUniform("PhotonClusterInfo").set(TILE, tilesX, tilesY, SLICES);
        shader.safeGetUniform("PhotonClusterView").set(NEAR, SLICE_SCALE, originX, originY);
    }

    static int newDataTexture() {
        int id = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
        return id;
    }

    static void resetUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
    }
}
