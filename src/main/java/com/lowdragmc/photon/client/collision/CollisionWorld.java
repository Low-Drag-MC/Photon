package com.lowdragmc.photon.client.collision;

import com.lowdragmc.lowdraglib2.utils.virtuallevel.DummyWorld;
import com.lowdragmc.photon.PhotonConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A level's collision geometry as immutable 16³ section copies, kept current by block and chunk events.
 * Queries are thread-safe. Missing sections are built on the game thread, or for a {@link ClientLevel} also
 * by particle workers inside {@link #beginParallel()}/{@link #endParallel()} (the game thread is blocked then).
 * An editor {@link DummyWorld} is built up front: its chunk source mutates a map on read.
 */
@OnlyIn(Dist.CLIENT)
public final class CollisionWorld implements BlockBoxSource {
    // the world holds its level weakly, or the map would keep every editor level alive
    private static final Map<Level, CollisionWorld> WORLDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong IDS = new AtomicLong();
    private static final int EVICT_INTERVAL = 200;
    private static final int EVICT_AGE = 1200;

    private enum Kind { CLIENT, DUMMY, OTHER }

    private final WeakReference<Level> level;
    final long id = IDS.incrementAndGet();
    private final Kind kind;
    private final int minSection, maxSection;
    private final ConcurrentHashMap<Long, SectionSnapshot> sections = new ConcurrentHashMap<>();
    private final AtomicInteger parallelWindows = new AtomicInteger();
    /** Bumped by every invalidation; per-thread section caches are valid for one version. */
    private volatile int version;
    /** Game time at the latest {@link #of}, so workers never read the level for it. */
    private volatile long now;
    private boolean dummyDirty = true;
    private int dummyFilled = -1;
    private long lastEviction;

    private CollisionWorld(Level level) {
        this.level = new WeakReference<>(level);
        this.kind = level instanceof ClientLevel ? Kind.CLIENT : level instanceof DummyWorld ? Kind.DUMMY : Kind.OTHER;
        this.minSection = level.getMinSection();
        this.maxSection = level.getMaxSection();
    }

    /** The collision world of {@code level}, refreshed for the current tick. Game thread only. */
    public static CollisionWorld of(Level level) {
        var world = WORLDS.computeIfAbsent(level, CollisionWorld::new);
        world.prepare(level);
        return world;
    }

    @Nullable
    private static CollisionWorld existing(Level level) {
        return WORLDS.get(level);
    }

    public static void blockChanged(Level level, int x, int y, int z) {
        var world = existing(level);
        if (world != null) world.invalidateBlock(x, y, z);
    }

    public static void chunkChanged(Level level, int chunkX, int chunkZ) {
        var world = existing(level);
        if (world != null) world.invalidateColumn(chunkX, chunkZ);
    }

    /** Every block of {@code level} may have changed, as when an editor scene is rebuilt. */
    public static void blocksReplaced(Level level) {
        var world = existing(level);
        if (world != null) world.invalidateAll();
    }

    public static void levelUnloaded(Level level) {
        WORLDS.remove(level);
    }

    /** Null once the level has been garbage collected. */
    @Nullable
    public Level level() {
        return level.get();
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Changes whenever any of its blocks may have. */
    public int version() {
        return version;
    }

    /** Whether particle workers may query collision worlds of {@code level}. */
    public static boolean isParallelSafe(Level level) {
        return level instanceof ClientLevel || level instanceof DummyWorld;
    }

    /** Opens a window in which particle workers may build sections; the game thread must wait inside it. */
    public void beginParallel() {
        parallelWindows.incrementAndGet();
    }

    public void endParallel() {
        parallelWindows.decrementAndGet();
    }

    private void prepare(Level level) {
        long now = level.getGameTime();
        this.now = now;
        if (kind == Kind.DUMMY) {
            var filled = ((DummyWorld) level).getFilledBlocks();
            if (dummyDirty || filled.size() != dummyFilled) rebuildDummy(level, filled.toLongArray());
            return;
        }
        if (now - lastEviction >= EVICT_INTERVAL || now < lastEviction) {
            lastEviction = now;
            boolean over = sections.size() > PhotonConfig.INSTANCE.collisionSectionCacheLimit.get();
            long oldest = over ? now - EVICT_INTERVAL : now - EVICT_AGE;
            // empty sections cost nothing to keep (chunk unloads still drop them) but a rescan to rebuild
            sections.values().removeIf(snapshot -> snapshot == SectionSnapshot.EMPTY ? over : snapshot.lastUse < oldest);
        }
    }

    private void rebuildDummy(Level level, long[] filled) {
        sections.clear();
        var keys = new LongOpenHashSet();
        for (long packed : filled) {
            keys.add(SectionPos.asLong(SectionPos.blockToSectionCoord(BlockPos.getX(packed)),
                    SectionPos.blockToSectionCoord(BlockPos.getY(packed)),
                    SectionPos.blockToSectionCoord(BlockPos.getZ(packed))));
        }
        for (long key : keys) {
            sections.put(key, build(level, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key)));
        }
        dummyFilled = filled.length;
        dummyDirty = false;
        version++;
    }

    private void invalidateBlock(int x, int y, int z) {
        if (kind == Kind.DUMMY) {
            dummyDirty = true;
            return;
        }
        int sx = SectionPos.blockToSectionCoord(x), sy = SectionPos.blockToSectionCoord(y), sz = SectionPos.blockToSectionCoord(z);
        sections.remove(SectionPos.asLong(sx, sy, sz));
        version++;
    }

    private void invalidateColumn(int chunkX, int chunkZ) {
        if (kind == Kind.DUMMY) {
            dummyDirty = true;
            return;
        }
        for (int sy = minSection; sy < maxSection; sy++) {
            sections.remove(SectionPos.asLong(chunkX, sy, chunkZ));
        }
        version++;
    }

    private void invalidateAll() {
        sections.clear();
        dummyDirty = true;
        version++;
    }

    /** Null when the section is missing and cannot be built on this thread. */
    @Nullable
    SectionSnapshot section(int sx, int sy, int sz) {
        if (sy < minSection || sy >= maxSection) return SectionSnapshot.EMPTY;
        long key = SectionPos.asLong(sx, sy, sz);
        var snapshot = sections.get(key);
        if (snapshot == null) {
            if (kind == Kind.DUMMY) return SectionSnapshot.EMPTY;
            var level = canBuild() ? this.level.get() : null;
            if (level == null) return null;
            snapshot = sections.computeIfAbsent(key, k -> build(level, sx, sy, sz));
        }
        touch(snapshot);
        return snapshot;
    }

    /** Keeps a section in use from being aged out. */
    private void touch(SectionSnapshot snapshot) {
        // at most once per tick: an unconditional write is a cache line every worker fights over
        long now = this.now;
        if (snapshot != SectionSnapshot.EMPTY && snapshot.lastUse != now) snapshot.lastUse = now;
    }

    @Nullable
    private SectionSnapshot cachedSection(BoxBuffer out, int sx, int sy, int sz) {
        if (sy < minSection || sy >= maxSection) return SectionSnapshot.EMPTY;
        long key = SectionPos.asLong(sx, sy, sz);
        int slot = BoxBuffer.sectionSlot(key);
        var cached = out.sectionValues[slot];
        if (cached != null && out.sectionKeys[slot] == key) {
            touch(cached);
            return cached;
        }
        var snapshot = section(sx, sy, sz);
        if (snapshot != null) {
            out.sectionKeys[slot] = key;
            out.sectionValues[slot] = snapshot;
        }
        return snapshot;
    }

    private boolean canBuild() {
        if (parallelWindows.get() > 0 && kind == Kind.CLIENT) return true;
        return Minecraft.getInstance().isSameThread();
    }

    private SectionSnapshot build(Level level, int sx, int sy, int sz) {
        if (kind != Kind.CLIENT) return SectionSnapshot.build(level, sx, sy, sz, null);
        var chunk = ((ClientLevel) level).getChunkSource().getChunk(sx, sz, false);
        return chunk == null ? SectionSnapshot.EMPTY : SectionSnapshot.build(level, sx, sy, sz, sectionOf(chunk, sy));
    }

    @Nullable
    private LevelChunkSection sectionOf(LevelChunk chunk, int sy) {
        int index = chunk.getSectionIndexFromSectionY(sy);
        var all = chunk.getSections();
        return index >= 0 && index < all.length ? all[index] : null;
    }

    /**
     * The state as collision sees it: AIR outside loaded geometry and in sections with nothing to collide with;
     * null when it cannot be read on this thread.
     */
    @Nullable
    public BlockState stateAt(int x, int y, int z) {
        var snapshot = section(x >> 4, y >> 4, z >> 4);
        if (snapshot == null) return null;
        if (snapshot == SectionSnapshot.EMPTY) return Blocks.AIR.defaultBlockState();
        return snapshot.palette[snapshot.paletteIndex(SectionSnapshot.cell(x, y, z))];
    }

    @Override
    public boolean collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           int mask, @Nullable BlockFilter filter, BoxBuffer out) {
        if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) return true; // NaN or inverted: nothing to gather
        out.useSectionCache(this, version);
        int innerX0 = Mth.floor(minX), innerX1 = Mth.floor(maxX);
        int innerY0 = Mth.floor(minY), innerY1 = Mth.floor(maxY);
        int innerZ0 = Mth.floor(minZ), innerZ1 = Mth.floor(maxZ);
        // one cell of margin, for shapes that reach past their own cell
        int x0 = innerX0 - 1, x1 = innerX1 + 1, z0 = innerZ0 - 1, z1 = innerZ1 + 1;
        int y0 = Math.max(innerY0 - 1, minSection << 4), y1 = Math.min(innerY1 + 1, (maxSection << 4) - 1);
        if (y0 > y1) return true;
        boolean complete = true;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    var snapshot = cachedSection(out, sx, sy, sz);
                    if (snapshot == null) {
                        complete = false;
                        continue;
                    }
                    if (snapshot == SectionSnapshot.EMPTY) continue;
                    boolean margin = snapshot.anyLarge;
                    int fromX = Math.max(margin ? x0 : innerX0, sx << 4), toX = Math.min(margin ? x1 : innerX1, (sx << 4) + 15);
                    int fromY = Math.max(margin ? y0 : innerY0, sy << 4), toY = Math.min(margin ? y1 : innerY1, (sy << 4) + 15);
                    int fromZ = Math.max(margin ? z0 : innerZ0, sz << 4), toZ = Math.min(margin ? z1 : innerZ1, (sz << 4) + 15);
                    for (int x = fromX; x <= toX; x++) {
                        for (int z = fromZ; z <= toZ; z++) {
                            for (int y = fromY; y <= toY; y++) {
                                boolean edge = x < innerX0 || x > innerX1 || y < innerY0 || y > innerY1 || z < innerZ0 || z > innerZ1;
                                collectCell(snapshot, x, y, z, edge, minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out);
                            }
                        }
                    }
                }
            }
        }
        return complete;
    }

    private void collectCell(SectionSnapshot snapshot, int x, int y, int z, boolean edge,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                             int mask, @Nullable BlockFilter filter, BoxBuffer out) {
        int cell = SectionSnapshot.cell(x, y, z);
        int index = snapshot.paletteIndex(cell);
        var shape = snapshot.shapes[index];
        if ((mask & BLOCKS) != 0 && !shape.isEmpty() && (!edge || shape.large)) {
            var state = snapshot.palette[index];
            if (filter == null || filter.test(state)) {
                var boxes = shape.dynamic ? snapshot.dynamicBoxes(cell) : shape.boxes;
                for (int i = 0; i < boxes.length; i += 6) {
                    double bx0 = x + boxes[i], by0 = y + boxes[i + 1], bz0 = z + boxes[i + 2];
                    double bx1 = x + boxes[i + 3], by1 = y + boxes[i + 4], bz1 = z + boxes[i + 5];
                    if (bx0 < maxX && bx1 > minX && by0 < maxY && by1 > minY && bz0 < maxZ && bz1 > minZ) {
                        out.add(bx0, by0, bz0, bx1, by1, bz1, BoxBuffer.BLOCK, BlockPos.asLong(x, y, z), state);
                    }
                }
            }
        }
        if ((mask & FLUIDS) != 0 && !edge && snapshot.anyFluid) {
            var state = snapshot.palette[index];
            var fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                var aboveSection = cachedSection(out, x >> 4, (y + 1) >> 4, z >> 4);
                var above = aboveSection == null || aboveSection == SectionSnapshot.EMPTY ? null
                        : aboveSection.palette[aboveSection.paletteIndex(SectionSnapshot.cell(x, y + 1, z))];
                // only the top of a body of fluid is a surface
                if (above == null || !above.getFluidState().getType().isSame(fluid.getType())) {
                    double height = fluid.getOwnHeight();
                    if (x + 1 > minX && x < maxX && y + height > minY && y < maxY && z + 1 > minZ && z < maxZ) {
                        out.add(x, y, z, x + 1, y + height, z + 1, BoxBuffer.FLUID, BlockPos.asLong(x, y, z), state);
                    }
                }
            }
        }
    }
}
