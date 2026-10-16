package com.lowdragmc.photon.client.light;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * One level's blocks around its shadow-casting lights, as occupancy the light pass can ray-march: a 2x2x2
 * mask per block, 16^3-block bricks in a 2D atlas, and a page table over a window of sections around the
 * camera. Built within a time budget from the level's block states; rebuilt when a block changes.
 */
@OnlyIn(Dist.CLIENT)
public final class VoxelWorld {
    static final int REGION_X = 32;
    static final int REGION_Y = 16;
    static final int REGION_Z = 32;
    private static final int PAGE_SIZE = 128;
    private static final int SLOT = 64;
    private static final int ATLAS = 2048;
    private static final int SLOTS_PER_ROW = ATLAS / SLOT;
    private static final int MAX_SLOTS = SLOTS_PER_ROW * SLOTS_PER_ROW;
    private static final int UNKNOWN = 0;
    private static final int EMPTY = 1;
    private static final int SOLID = 2;
    private static final int MIXED = 3;
    // shared by every instance: they only ever build on the render thread
    private static final Reference2IntOpenHashMap<BlockState> MASKS = new Reference2IntOpenHashMap<>();
    private static final IntBuffer PAGES = MemoryUtil.memAllocInt(REGION_X * REGION_Y * REGION_Z);
    private static final ByteBuffer UPLOAD = MemoryUtil.memAlloc(SLOT * SLOT);
    private static long builtTotal;
    private static long buildNanosTotal;
    // 1x1 stand-ins that keep the light pass's integer samplers complete while a scene has no atlas
    private static int emptyPages = -1;
    private static int emptyBricks = -1;

    static {
        MASKS.defaultReturnValue(-1);
    }

    private static final class Brick {
        int kind = UNKNOWN;
        int slot = -1;
        boolean dirty = true;
        long lastUsed;
    }

    private final Long2ObjectOpenHashMap<Brick> bricks = new Long2ObjectOpenHashMap<>();
    private final LongArrayList needed = new LongArrayList();
    private final IntArrayList free = new IntArrayList();
    private int pageTexture = -1;
    private int brickTexture = -1;
    private int originX = Integer.MIN_VALUE;
    private int originY;
    private int originZ;
    private boolean pagesDirty = true;
    private long frame;
    private int nextSlot;

    /** Sections voxelised since startup by every instance, and the CPU time they took; for profiling. */
    public static long builtTotal() {
        return builtTotal;
    }

    public static double buildMillisTotal() {
        return buildNanosTotal / 1e6;
    }

    /** Tags decide some masks (leaves), so a tag reload starts over. */
    public static void clearMasks() {
        MASKS.clear();
    }

    public int brickCount() {
        return nextSlot - free.size();
    }

    /** Region min corner in blocks. */
    int originBlockX() {
        return originX << 4;
    }

    int originBlockY() {
        return originY << 4;
    }

    int originBlockZ() {
        return originZ << 4;
    }

    public void markDirty(int blockX, int blockY, int blockZ) {
        var brick = bricks.get(SectionPos.asLong(blockX >> 4, blockY >> 4, blockZ >> 4));
        if (brick != null) brick.dirty = true;
    }

    public void markColumnDirty(int chunkX, int chunkZ) {
        for (int sy = originY; sy < originY + REGION_Y; sy++) {
            var brick = bricks.get(SectionPos.asLong(chunkX, sy, chunkZ));
            if (brick != null) brick.dirty = true;
        }
    }

    public void clear() {
        bricks.clear();
        free.clear();
        nextSlot = 0;
        pagesDirty = true;
    }

    /** Frees the textures as well; everything is rebuilt on demand. */
    void release() {
        clear();
        if (pageTexture != -1) {
            TextureUtil.releaseTextureId(pageTexture);
            TextureUtil.releaseTextureId(brickTexture);
            pageTexture = brickTexture = -1;
        }
    }

    void update(Level level, Vec3 camera, List<DynamicLight> shadowed, long budgetNanos) {
        frame++;
        ensureTextures();
        int ox = SectionPos.blockToSectionCoord(Mth.floor(camera.x)) - REGION_X / 2;
        int oy = SectionPos.blockToSectionCoord(Mth.floor(camera.y)) - REGION_Y / 2;
        int oz = SectionPos.blockToSectionCoord(Mth.floor(camera.z)) - REGION_Z / 2;
        if (ox != originX || oy != originY || oz != originZ) {
            originX = ox;
            originY = oy;
            originZ = oz;
            pagesDirty = true;
            // only the window is addressable, so bricks that left it go and the map stays window-sized
            bricks.long2ObjectEntrySet().removeIf(entry -> {
                long key = entry.getLongKey();
                int x = SectionPos.x(key) - originX, y = SectionPos.y(key) - originY, z = SectionPos.z(key) - originZ;
                if (x >= 0 && y >= 0 && z >= 0 && x < REGION_X && y < REGION_Y && z < REGION_Z) return false;
                freeSlot(entry.getValue(), UNKNOWN);
                return true;
            });
        }
        int minSection = level.getMinSection();
        int maxSection = level.getMaxSection();
        // every brick the frame needs is marked used before any is built, so no build evicts another's slot
        needed.clear();
        for (var light : shadowed) {
            var p = light.position;
            double r = light.range;
            int x0 = Math.max(originX, SectionPos.blockToSectionCoord(Mth.floor(p.x - r)));
            int x1 = Math.min(originX + REGION_X - 1, SectionPos.blockToSectionCoord(Mth.floor(p.x + r)));
            int y0 = Math.max(Math.max(originY, minSection), SectionPos.blockToSectionCoord(Mth.floor(p.y - r)));
            int y1 = Math.min(Math.min(originY + REGION_Y - 1, maxSection - 1), SectionPos.blockToSectionCoord(Mth.floor(p.y + r)));
            int z0 = Math.max(originZ, SectionPos.blockToSectionCoord(Mth.floor(p.z - r)));
            int z1 = Math.min(originZ + REGION_Z - 1, SectionPos.blockToSectionCoord(Mth.floor(p.z + r)));
            for (int sy = y0; sy <= y1; sy++) {
                for (int sz = z0; sz <= z1; sz++) {
                    for (int sx = x0; sx <= x1; sx++) {
                        long key = SectionPos.asLong(sx, sy, sz);
                        bricks.computeIfAbsent(key, k -> new Brick()).lastUsed = frame;
                        needed.add(key);
                    }
                }
            }
        }
        long deadline = System.nanoTime() + budgetNanos;
        boolean atlasFull = false;
        for (int i = 0; i < needed.size(); i++) {
            long key = needed.getLong(i);
            var brick = bricks.get(key);
            // once the atlas is full, a brick without a slot would be scanned only to fail again
            if (!brick.dirty || atlasFull && brick.slot < 0) continue;
            long now = System.nanoTime();
            if (now >= deadline) break;
            int page = page(brick);
            atlasFull |= !build(level, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), brick);
            pagesDirty |= page(brick) != page;
            buildNanosTotal += System.nanoTime() - now;
            builtTotal++;
        }
        if (pagesDirty) {
            uploadPages();
            pagesDirty = false;
        }
    }

    private static int page(@Nullable Brick brick) {
        return brick == null ? UNKNOWN : brick.kind == MIXED ? MIXED + brick.slot : brick.kind;
    }

    /** False when the brick needed an atlas slot and none was free; it stays dirty. */
    private boolean build(Level level, int sx, int sy, int sz, Brick brick) {
        // a client level reads its chunk sections directly; any other level (an editor scene) goes block by block
        LevelChunkSection section = null;
        if (level instanceof ClientLevel client) {
            var chunk = client.getChunkSource().getChunk(sx, sz, false);
            if (chunk == null) {
                // not received yet: the chunk load marks the column dirty again
                brick.dirty = false;
                freeSlot(brick, UNKNOWN);
                return true;
            }
            section = chunk.getSection(client.getSectionIndexFromSectionY(sy));
            if (section.hasOnlyAir()) {
                brick.dirty = false;
                freeSlot(brick, EMPTY);
                return true;
            }
        }
        brick.dirty = false;
        var pos = new BlockPos.MutableBlockPos();
        boolean allEmpty = true;
        boolean allSolid = true;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int row = (z + 16 * (y >> 2)) * SLOT + 16 * (y & 3);
                for (int x = 0; x < 16; x++) {
                    var state = section != null ? section.getBlockState(x, y, z)
                            : level.getBlockState(pos.set((sx << 4) + x, (sy << 4) + y, (sz << 4) + z));
                    int mask = mask(state);
                    allEmpty &= mask == 0;
                    allSolid &= mask == 0xFF;
                    UPLOAD.put(row + x, (byte) mask);
                }
            }
        }
        if (allEmpty) {
            freeSlot(brick, EMPTY);
            return true;
        }
        if (allSolid) {
            freeSlot(brick, SOLID);
            return true;
        }
        if (brick.slot < 0) {
            brick.slot = allocate();
            if (brick.slot < 0) {
                brick.kind = UNKNOWN;
                brick.dirty = true;
                return false;
            }
        }
        LightClusters.resetUnpack();
        GlStateManager._bindTexture(brickTexture);
        UPLOAD.position(0).limit(SLOT * SLOT);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (brick.slot % SLOTS_PER_ROW) * SLOT, (brick.slot / SLOTS_PER_ROW) * SLOT,
                SLOT, SLOT, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, UPLOAD);
        UPLOAD.clear();
        GlStateManager._bindTexture(0);
        brick.kind = MIXED;
        return true;
    }

    private void freeSlot(Brick brick, int kind) {
        if (brick.slot >= 0) {
            free.add(brick.slot);
            brick.slot = -1;
        }
        brick.kind = kind;
    }

    /** A free atlas slot, evicting the least recently used brick that nothing needed this frame. */
    private int allocate() {
        if (!free.isEmpty()) return free.popInt();
        if (nextSlot < MAX_SLOTS) return nextSlot++;
        Brick oldest = null;
        for (var brick : bricks.values()) {
            if (brick.slot >= 0 && brick.lastUsed != frame && (oldest == null || brick.lastUsed < oldest.lastUsed)) {
                oldest = brick;
            }
        }
        if (oldest == null) return -1;
        int slot = oldest.slot;
        oldest.slot = -1;
        oldest.kind = UNKNOWN;
        oldest.dirty = true;
        pagesDirty = true;
        return slot;
    }

    private void uploadPages() {
        for (int sy = 0; sy < REGION_Y; sy++) {
            for (int sz = 0; sz < REGION_Z; sz++) {
                for (int sx = 0; sx < REGION_X; sx++) {
                    var brick = bricks.get(SectionPos.asLong(originX + sx, originY + sy, originZ + sz));
                    PAGES.put(sx + REGION_X * (sz + REGION_Z * sy), page(brick));
                }
            }
        }
        LightClusters.resetUnpack();
        GlStateManager._bindTexture(pageTexture);
        PAGES.position(0).limit(PAGE_SIZE * PAGE_SIZE);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, PAGE_SIZE, PAGE_SIZE, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, PAGES);
        PAGES.clear();
        GlStateManager._bindTexture(0);
    }

    private void ensureTextures() {
        if (pageTexture != -1) return;
        pageTexture = LightClusters.newDataTexture();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, PAGE_SIZE, PAGE_SIZE, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);
        brickTexture = LightClusters.newDataTexture();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8UI, ATLAS, ATLAS, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GlStateManager._bindTexture(0);
        pagesDirty = true;
    }

    int pageTexture() {
        if (pageTexture != -1) return pageTexture;
        if (emptyPages == -1) emptyPages = emptyTexture(GL30.GL_R32UI, GL11.GL_UNSIGNED_INT);
        return emptyPages;
    }

    int brickTexture() {
        if (brickTexture != -1) return brickTexture;
        if (emptyBricks == -1) emptyBricks = emptyTexture(GL30.GL_R8UI, GL11.GL_UNSIGNED_BYTE);
        return emptyBricks;
    }

    private static int emptyTexture(int internalFormat, int type) {
        int id = LightClusters.newDataTexture();
        LightClusters.resetUnpack();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, 1, 1, 0, GL30.GL_RED_INTEGER, type, new int[1]);
        GlStateManager._bindTexture(0);
        return id;
    }

    /**
     * Light-blocking occupancy of a state as a 2x2x2 mask (bit = x + 2y + 4z). Leaves block light; glass and
     * other non-occluding blocks let it through; everything else follows its occlusion shape.
     */
    static int mask(BlockState state) {
        int cached = MASKS.getInt(state);
        if (cached >= 0) return cached;
        int mask = computeMask(state);
        MASKS.put(state, mask);
        return mask;
    }

    private static int computeMask(BlockState state) {
        if (state.isAir()) return 0;
        if (state.is(BlockTags.LEAVES)) return 0xFF;
        if (!state.canOcclude()) return 0;
        VoxelShape shape;
        try {
            shape = state.getOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return 0xFF;
        }
        if (shape.isEmpty()) return 0;
        if (Block.isShapeFullBlock(shape)) return 0xFF;
        int mask = 0;
        var boxes = shape.toAabbs();
        for (int i = 0; i < 8; i++) {
            double cx = 0.25 + 0.5 * (i & 1);
            double cy = 0.25 + 0.5 * (i >> 1 & 1);
            double cz = 0.25 + 0.5 * (i >> 2 & 1);
            for (var box : boxes) {
                if (box.contains(cx, cy, cz)) {
                    mask |= 1 << i;
                    break;
                }
            }
        }
        return mask;
    }
}
