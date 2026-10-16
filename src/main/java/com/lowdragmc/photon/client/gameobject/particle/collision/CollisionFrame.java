package com.lowdragmc.photon.client.gameobject.particle.collision;

import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSettings;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class CollisionFrame {
    public enum Kind { BLOCK, ENTITY, FLUID }
    public record Target(Kind kind, long id) {}
    public record Candidate(Target target, AABB bounds, Entity entity, Vec3 movement) {}
    public record Hit(Candidate candidate, double time, Vec3 normal) {}

    private final Long2ObjectOpenHashMap<List<Candidate>> blockTiles = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<List<Candidate>> entityTiles = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<List<BlockPos>> sparseTiles = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LevelChunkSection> emptySections = new Long2ObjectOpenHashMap<>();
    private final List<Candidate> oversizedEntities = new ArrayList<>();
    private final List<Candidate> indexedEntities = new ArrayList<>();
    private final Map<String, Map<Target, Long>> remembered = new HashMap<>();
    private final LongOpenHashSet visitedTiles = new LongOpenHashSet();
    private final IntOpenHashSet visitedEntities = new IntOpenHashSet();
    private Level level;
    private long epoch = Long.MIN_VALUE;
    private boolean sparseReady;
    private boolean entitiesReady;
    private boolean sparse;
    public long queries;
    public long blockReads;
    public long tileBuilds;
    public long narrowTests;
    public long cacheHits;
    public long emptySectionHits;

    public void bind(Level world, long tick) {
        if (level == world && epoch == tick) return;
        if (level != world || tick < epoch) {
            remembered.clear();
            emptySections.clear();
        }
        level = world;
        epoch = tick;
        blockTiles.clear();
        entityTiles.clear();
        sparseTiles.clear();
        oversizedEntities.clear();
        indexedEntities.clear();
        sparseReady = false;
        entitiesReady = false;
        sparse = world instanceof TrackedDummyWorld tracked && tracked.proxyWorld.get() == null;
    }

    public void reset() {
        level = null;
        epoch = Long.MIN_VALUE;
        remembered.clear();
        blockTiles.clear();
        entityTiles.clear();
        sparseTiles.clear();
        oversizedEntities.clear();
        indexedEntities.clear();
        queries = blockReads = tileBuilds = narrowTests = cacheHits = emptySectionHits = 0;
    }

    public boolean remember(String group, Target target, int cooldown) {
        Map<Target, Long> hits = remembered.computeIfAbsent(group, ignored -> new HashMap<>());
        Long previous = hits.get(target);
        if (previous != null && (cooldown <= 0 || epoch - previous < cooldown)) return false;
        hits.put(target, epoch);
        return true;
    }

    public IntOpenHashSet sourceEntities(Vec3 origin, double radius) {
        IntOpenHashSet result = new IntOpenHashSet();
        if (!(radius > 0) || !Double.isFinite(radius)) return result;
        ensureEntities();
        for (Candidate candidate : indexedEntities) {
            if (SweepMath.overlapsSphere(candidate.bounds, origin, radius)) result.add((int) candidate.target.id);
        }
        return result;
    }

    public Hit nearest(AABB moving, Vec3 delta, double dt, CollisionSettings settings, int ownerId, Predicate<Target> ignored) {
        queries++;
        if (settings.blocks && !settings.entities && !settings.fluids
                && settings.includedBlockTypes.isBlank() && settings.excludedBlockTypes.isBlank()
                && !settings.rayProbe && !settings.detectStationaryOverlaps
                && settings.response != CollisionSettings.Response.PIERCE) {
            return nearestBlockOnly(moving, delta, settings);
        }
        visitedTiles.clear();
        visitedEntities.clear();
        List<Candidate> candidates = new ArrayList<>();
        AABB broad = moving.expandTowards(delta).inflate(1.000001);
        int minimumX = floorTile(broad.minX), maximumX = floorTile(broad.maxX);
        int minimumY = floorTile(broad.minY), maximumY = floorTile(broad.maxY);
        int minimumZ = floorTile(broad.minZ), maximumZ = floorTile(broad.maxZ);
        long volume = (long) (maximumX - minimumX + 1) * (maximumY - minimumY + 1) * (maximumZ - minimumZ + 1);
        if (settings.entities) ensureEntities();
        if (volume <= 256) {
            for (int tileX = minimumX; tileX <= maximumX; tileX++)
                for (int tileY = minimumY; tileY <= maximumY; tileY++)
                    for (int tileZ = minimumZ; tileZ <= maximumZ; tileZ++)
                        collect(tileX, tileY, tileZ, moving, delta, settings, candidates);
        } else {
            traverse(moving, delta, settings, candidates);
        }
        if (settings.entities) candidates.addAll(oversizedEntities);
        Hit nearest = null;
        for (Candidate candidate : candidates) {
            Kind kind = candidate.target.kind;
            if (kind == Kind.BLOCK && !settings.blocks || kind == Kind.FLUID && !settings.fluids) continue;
            if (kind != Kind.ENTITY && (!settings.includedBlockTypes.isBlank() || !settings.excludedBlockTypes.isBlank())) {
                BlockPos position = BlockPos.of(candidate.target.id);
                ChunkAccess chunk = level.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                String type = BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(position).getBlock()).toString();
                if (!settings.includedBlockTypes.isBlank() && !contains(settings.includedBlockTypes, type) || contains(settings.excludedBlockTypes, type)) continue;
            }
            if (ignored.test(candidate.target)) continue;
            if (kind == Kind.ENTITY) {
                if (!visitedEntities.add((int) candidate.target.id) || !acceptEntity(candidate.entity, settings, ownerId)) continue;
            }
            AABB bounds = candidate.bounds;
            Vec3 relative = delta;
            if (kind == Kind.ENTITY && settings.sweepEntityMotion) {
                bounds = bounds.move(candidate.movement.scale(-dt));
                relative = delta.subtract(candidate.movement.scale(dt));
            }
            AABB sweptBounds = moving.expandTowards(relative);
            if (bounds.maxX < sweptBounds.minX || bounds.minX > sweptBounds.maxX || bounds.maxY < sweptBounds.minY || bounds.minY > sweptBounds.maxY || bounds.maxZ < sweptBounds.minZ || bounds.minZ > sweptBounds.maxZ) continue;
            narrowTests++;
            SweepMath.Contact contact = SweepMath.sweep(moving, relative, bounds);
            if (contact != null && (nearest == null || contact.time() < nearest.time)) {
                nearest = new Hit(candidate, contact.time(), contact.normal());
            }
        }
        return nearest;
    }

    /** Fast path for the common projectile/debris case: static block-only collision with no filters. */
    private Hit nearestBlockOnly(AABB moving, Vec3 delta, CollisionSettings settings) {
        visitedTiles.clear();
        AABB broad = moving.expandTowards(delta).inflate(1.000001);
        int minimumX = floorTile(broad.minX), maximumX = floorTile(broad.maxX);
        int minimumY = floorTile(broad.minY), maximumY = floorTile(broad.maxY);
        int minimumZ = floorTile(broad.minZ), maximumZ = floorTile(broad.maxZ);
        FastNearest result = new FastNearest(moving, delta);
        long volume = (long) (maximumX - minimumX + 1) * (maximumY - minimumY + 1) * (maximumZ - minimumZ + 1);
        if (volume <= 256) {
            for (int tileX = minimumX; tileX <= maximumX; tileX++)
                for (int tileY = minimumY; tileY <= maximumY; tileY++)
                    for (int tileZ = minimumZ; tileZ <= maximumZ; tileZ++)
                        collectBlockOnly(tileX, tileY, tileZ, result, settings);
        } else {
            traverseBlockOnly(result, settings);
        }
        return result.hit();
    }

    private void traverseBlockOnly(FastNearest result, CollisionSettings settings) {
        Vec3 center = result.moving.getCenter();
        int tileX = floorTile(center.x), tileY = floorTile(center.y), tileZ = floorTile(center.z);
        int stepX = (int) Math.signum(result.delta.x), stepY = (int) Math.signum(result.delta.y), stepZ = (int) Math.signum(result.delta.z);
        double nextX = crossing(center.x, result.delta.x, tileX, stepX);
        double nextY = crossing(center.y, result.delta.y, tileY, stepY);
        double nextZ = crossing(center.z, result.delta.z, tileZ, stepZ);
        double incrementX = stepX == 0 ? Double.POSITIVE_INFINITY : 8 / Math.abs(result.delta.x);
        double incrementY = stepY == 0 ? Double.POSITIVE_INFINITY : 8 / Math.abs(result.delta.y);
        double incrementZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 8 / Math.abs(result.delta.z);
        int padX = (int) Math.ceil(result.moving.getXsize() / 16) + 1;
        int padY = (int) Math.ceil(result.moving.getYsize() / 16) + 1;
        int padZ = (int) Math.ceil(result.moving.getZsize() / 16) + 1;
        while (true) {
            for (int offsetX = -padX; offsetX <= padX; offsetX++)
                for (int offsetY = -padY; offsetY <= padY; offsetY++)
                    for (int offsetZ = -padZ; offsetZ <= padZ; offsetZ++)
                        collectBlockOnly(tileX + offsetX, tileY + offsetY, tileZ + offsetZ, result, settings);
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next > 1 || !Double.isFinite(next)) break;
            if (nextX <= next + 1e-12) { tileX += stepX; nextX += incrementX; }
            if (nextY <= next + 1e-12) { tileY += stepY; nextY += incrementY; }
            if (nextZ <= next + 1e-12) { tileZ += stepZ; nextZ += incrementZ; }
        }
    }

    private void collectBlockOnly(int tileX, int tileY, int tileZ, FastNearest result, CollisionSettings settings) {
        long key = BlockPos.asLong(tileX, tileY, tileZ);
        if (!visitedTiles.add(key)) return;
        AABB tile = new AABB(tileX * 8.0, tileY * 8.0, tileZ * 8.0, tileX * 8.0 + 8, tileY * 8.0 + 8, tileZ * 8.0 + 8);
        if (SweepMath.sweep(result.moving, result.delta, tile.inflate(1.000001)) == null) return;
        List<Candidate> blocks = blockTiles.get(key);
        if (blocks == null) {
            blocks = buildTile(tileX, tileY, tileZ, key);
            if (blockTiles.size() < Math.max(1, settings.cachedTileLimit)) blockTiles.put(key, blocks);
        } else cacheHits++;
        for (Candidate candidate : blocks) {
            narrowTests++;
            SweepMath.Contact contact = SweepMath.sweep(result.moving, result.delta, candidate.bounds);
            if (contact != null && contact.time() < result.time) result.set(candidate, contact);
        }
    }

    private final class FastNearest {
        private final AABB moving;
        private final Vec3 delta;
        private Candidate candidate;
        private Vec3 normal;
        private double time = Double.POSITIVE_INFINITY;

        private FastNearest(AABB moving, Vec3 delta) {
            this.moving = moving;
            this.delta = delta;
        }

        private void set(Candidate candidate, SweepMath.Contact contact) {
            this.candidate = candidate;
            this.normal = contact.normal();
            this.time = contact.time();
        }

        private Hit hit() {
            return candidate == null ? null : new Hit(candidate, time, normal);
        }
    }

    private void traverse(AABB moving, Vec3 delta, CollisionSettings settings, List<Candidate> candidates) {
        Vec3 center = moving.getCenter();
        int tileX = floorTile(center.x), tileY = floorTile(center.y), tileZ = floorTile(center.z);
        int stepX = (int) Math.signum(delta.x), stepY = (int) Math.signum(delta.y), stepZ = (int) Math.signum(delta.z);
        double nextX = crossing(center.x, delta.x, tileX, stepX);
        double nextY = crossing(center.y, delta.y, tileY, stepY);
        double nextZ = crossing(center.z, delta.z, tileZ, stepZ);
        double incrementX = stepX == 0 ? Double.POSITIVE_INFINITY : 8 / Math.abs(delta.x);
        double incrementY = stepY == 0 ? Double.POSITIVE_INFINITY : 8 / Math.abs(delta.y);
        double incrementZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 8 / Math.abs(delta.z);
        int padX = (int) Math.ceil(moving.getXsize() / 16) + 1;
        int padY = (int) Math.ceil(moving.getYsize() / 16) + 1;
        int padZ = (int) Math.ceil(moving.getZsize() / 16) + 1;
        while (true) {
            for (int offsetX = -padX; offsetX <= padX; offsetX++)
                for (int offsetY = -padY; offsetY <= padY; offsetY++)
                    for (int offsetZ = -padZ; offsetZ <= padZ; offsetZ++)
                        collect(tileX + offsetX, tileY + offsetY, tileZ + offsetZ, moving, delta, settings, candidates);
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next > 1 || !Double.isFinite(next)) break;
            if (nextX <= next + 1e-12) { tileX += stepX; nextX += incrementX; }
            if (nextY <= next + 1e-12) { tileY += stepY; nextY += incrementY; }
            if (nextZ <= next + 1e-12) { tileZ += stepZ; nextZ += incrementZ; }
        }
    }

    private static double crossing(double center, double delta, int tile, int step) {
        return step == 0 ? Double.POSITIVE_INFINITY : ((tile + (step > 0 ? 1 : 0)) * 8.0 - center) / delta;
    }

    private void collect(int tileX, int tileY, int tileZ, AABB moving, Vec3 delta, CollisionSettings settings, List<Candidate> output) {
        long key = BlockPos.asLong(tileX, tileY, tileZ);
        if (!visitedTiles.add(key)) return;
        AABB tile = new AABB(tileX * 8.0, tileY * 8.0, tileZ * 8.0, tileX * 8.0 + 8, tileY * 8.0 + 8, tileZ * 8.0 + 8);
        if (SweepMath.sweep(moving, delta, tile.inflate(1.000001)) == null) return;
        if (settings.blocks || settings.fluids) {
            List<Candidate> blocks = blockTiles.get(key);
            if (blocks == null) {
                blocks = buildTile(tileX, tileY, tileZ, key);
                if (blockTiles.size() < Math.max(1, settings.cachedTileLimit)) blockTiles.put(key, blocks);
            } else cacheHits++;
            output.addAll(blocks);
        }
        if (settings.entities) {
            List<Candidate> entities = entityTiles.get(key);
            if (entities != null) output.addAll(entities);
        }
    }

    private List<Candidate> buildTile(int tileX, int tileY, int tileZ, long key) {
        tileBuilds++;
        List<Candidate> candidates = new ArrayList<>();
        if (sparse) {
            ensureSparse();
            List<BlockPos> positions = sparseTiles.get(key);
            if (positions != null) for (BlockPos position : positions) {
                ChunkAccess chunk = level.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false);
                if (chunk != null) addBlock(position, chunk.getBlockState(position), candidates);
            }
        } else {
            int baseY = tileY * 8;
            if (baseY + 8 <= level.getMinBuildHeight() || baseY >= level.getMaxBuildHeight()) return List.of();
            ChunkAccess chunk = level.getChunk(tileX >> 1, tileZ >> 1, ChunkStatus.FULL, false);
            if (chunk == null) return List.of();
            int sectionIndex = level.getSectionIndex(baseY);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return List.of();
            var section = chunk.getSections()[sectionIndex];
            if (section == null) return List.of();
            LevelChunkSection knownEmptySection = emptySections.get(key);
            if (knownEmptySection == section && section.hasOnlyAir()) {
                emptySectionHits++;
                return List.of();
            }
            emptySections.remove(key);
            if (section.hasOnlyAir()) {
                emptySections.put(key, section);
                return List.of();
            }
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            for (int offsetX = 0; offsetX < 8; offsetX++)
                for (int offsetY = 0; offsetY < 8; offsetY++)
                    for (int offsetZ = 0; offsetZ < 8; offsetZ++) {
                        position.set(tileX * 8 + offsetX, baseY + offsetY, tileZ * 8 + offsetZ);
                        addBlock(position, section.getBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15), candidates);
                    }
        }
        return candidates.isEmpty() ? List.of() : List.copyOf(candidates);
    }

    private void addBlock(BlockPos position, net.minecraft.world.level.block.state.BlockState state, List<Candidate> output) {
        blockReads++;
        if (state.isAir()) return;
        Target target = new Target(Kind.BLOCK, position.asLong());
        for (AABB bounds : state.getCollisionShape(level, position, CollisionContext.empty()).toAabbs()) {
            output.add(new Candidate(target, bounds.move(position), null, Vec3.ZERO));
        }
        var fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            AABB bounds = new AABB(position.getX(), position.getY(), position.getZ(), position.getX() + 1, position.getY() + fluid.getHeight(level, position), position.getZ() + 1);
            output.add(new Candidate(new Target(Kind.FLUID, position.asLong()), bounds, null, Vec3.ZERO));
        }
    }

    private void ensureSparse() {
        if (sparseReady) return;
        sparseReady = true;
        for (long packed : ((TrackedDummyWorld) level).getFilledBlocks()) {
            BlockPos position = BlockPos.of(packed);
            long key = BlockPos.asLong(position.getX() >> 3, position.getY() >> 3, position.getZ() >> 3);
            sparseTiles.computeIfAbsent(key, ignored -> new ArrayList<>()).add(position);
        }
    }

    private void ensureEntities() {
        if (entitiesReady) return;
        entitiesReady = true;
        Level entityWorld = level;
        while (entityWorld instanceof TrackedDummyWorld tracked && tracked.proxyWorld.get() != null) entityWorld = tracked.proxyWorld.get();
        Iterable<Entity> entities = entityWorld instanceof ClientLevel client ? client.entitiesForRendering()
                : entityWorld instanceof TrackedDummyWorld tracked ? tracked.getAllRenderedEntities() : List.of();
        for (Entity entity : entities) {
            if (entity.isRemoved()) continue;
            Vec3 movement = new Vec3(entity.getX() - entity.xOld, entity.getY() - entity.yOld, entity.getZ() - entity.zOld);
            AABB bounds = entity.getBoundingBox();
            AABB swept = bounds.expandTowards(movement.scale(-1));
            int minimumX = floorTile(swept.minX), maximumX = floorTile(swept.maxX);
            int minimumY = floorTile(swept.minY), maximumY = floorTile(swept.maxY);
            int minimumZ = floorTile(swept.minZ), maximumZ = floorTile(swept.maxZ);
            Candidate candidate = new Candidate(new Target(Kind.ENTITY, entity.getId()), bounds, entity, movement);
            indexedEntities.add(candidate);
            long volume = (long) (maximumX - minimumX + 1) * (maximumY - minimumY + 1) * (maximumZ - minimumZ + 1);
            if (volume > 4096) { oversizedEntities.add(candidate); continue; }
            for (int tileX = minimumX; tileX <= maximumX; tileX++)
                for (int tileY = minimumY; tileY <= maximumY; tileY++)
                    for (int tileZ = minimumZ; tileZ <= maximumZ; tileZ++)
                        entityTiles.computeIfAbsent(BlockPos.asLong(tileX, tileY, tileZ), ignored -> new ArrayList<>()).add(candidate);
        }
    }

    private static boolean acceptEntity(Entity entity, CollisionSettings settings, int ownerId) {
        if (entity.isRemoved() || !entity.isAlive() || settings.ignoreOwner && entity.getId() == ownerId) return false;
        if (settings.ignoreSpectators && entity.isSpectator() || settings.pickableOnly && !entity.isPickable()) return false;
        if (settings.entityFilter == CollisionSettings.EntityFilter.LIVING && !(entity instanceof LivingEntity)) return false;
        if (settings.entityFilter == CollisionSettings.EntityFilter.PLAYERS && !(entity instanceof Player)) return false;
        if (settings.entityFilter == CollisionSettings.EntityFilter.NON_LIVING && entity instanceof LivingEntity) return false;
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (!settings.includedEntityTypes.isBlank() && !contains(settings.includedEntityTypes, type)) return false;
        return settings.excludedEntityTypes.isBlank() || !contains(settings.excludedEntityTypes, type);
    }

    private static boolean contains(String list, String type) {
        for (String item : list.split(",")) if (item.trim().equals(type)) return true;
        return false;
    }

    private static int floorTile(double coordinate) {
        return (int) Math.floor(coordinate / 8);
    }

    public Map<String, Long> statistics() {
        return Map.of("queries", queries, "blockReads", blockReads, "tileBuilds", tileBuilds, "narrowTests", narrowTests, "cacheHits", cacheHits, "emptySectionHits", emptySectionHits, "cachedTiles", (long) blockTiles.size());
    }
}

