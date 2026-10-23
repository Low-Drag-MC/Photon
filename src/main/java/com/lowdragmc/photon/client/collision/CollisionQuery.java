package com.lowdragmc.photon.client.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** Swept-box queries against a {@link CollisionScene}. One instance per thread ({@link #get()}); casts allocate nothing. */
public final class CollisionQuery {
    public static final int NONE = 0;
    public static final int HIT = 1;
    /** Part of the swept region could not be read on this thread: do not trust the miss. */
    public static final int MISS = 2;
    /** Longer casts walk their path in segments of this length. */
    private static final double SEGMENT = 4;
    /** Motion beyond this per cast is not collided at all (and NaN or infinite motion never is). */
    private static final double MAX_CAST = 512;
    private static final double SURFACE = 1.0E-3;
    /** Push directions, +y first so that it wins ties: +y, +x, -x, +z, -z, -y (as indices into {@link BoxCast#pushes}). */
    private static final int[] PUSH_ORDER = {2, 0, 1, 4, 5, 3};
    private static final double[] PUSH_X = {1, -1, 0, 0, 0, 0}, PUSH_Y = {0, 0, 1, -1, 0, 0}, PUSH_Z = {0, 0, 0, 0, 1, -1};
    private static final ThreadLocal<CollisionQuery> LOCAL = ThreadLocal.withInitial(CollisionQuery::new);

    private final BoxBuffer buffer = new BoxBuffer();
    private final CellWalk walk = new CellWalk();
    private final BoxCast.Hit hit = new BoxCast.Hit();
    private final double[] closest = new double[3];
    private final double[] pushes = new double[6];
    @Nullable
    private CollisionScene gatheredScene;
    private double gatheredMinX, gatheredMinY, gatheredMinZ, gatheredMaxX, gatheredMaxY, gatheredMaxZ;

    public static CollisionQuery get() {
        return LOCAL.get();
    }

    /** Drops the gathered region and the scene it came from. */
    public void reset() {
        gatheredScene = null;
    }

    /**
     * Gathers the blocks a step can reach once, for all its casts: the whole reach of a slow body (a bounce
     * may send it anywhere), the path of a fast one.
     */
    public void prepare(CollisionScene scene, double cx, double cy, double cz, double hx, double hy, double hz,
                        double dx, double dy, double dz) {
        gatheredScene = null;
        if (scene.blocks == null || scene.blockMask == 0) return;
        double reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(reach <= SEGMENT)) return;
        double tx = BoxCast.tolerance(cx), ty = BoxCast.tolerance(cy), tz = BoxCast.tolerance(cz);
        if (reach <= 1) {
            gather(scene, cx - hx - reach - tx, cy - hy - reach - ty, cz - hz - reach - tz,
                    cx + hx + reach + tx, cy + hy + reach + ty, cz + hz + reach + tz);
        } else {
            gather(scene, Math.min(cx, cx + dx) - hx - tx, Math.min(cy, cy + dy) - hy - ty, Math.min(cz, cz + dz) - hz - tz,
                    Math.max(cx, cx + dx) + hx + tx, Math.max(cy, cy + dy) + hy + ty, Math.max(cz, cz + dz) + hz + tz);
        }
    }

    private boolean gather(CollisionScene scene, double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {
        if (gatheredScene == scene && minX >= gatheredMinX && minY >= gatheredMinY && minZ >= gatheredMinZ
                && maxX <= gatheredMaxX && maxY <= gatheredMaxY && maxZ <= gatheredMaxZ) {
            return true;
        }
        buffer.clear();
        gatheredScene = null;
        if (!scene.blocks.collect(minX, minY, minZ, maxX, maxY, maxZ, scene.blockMask, scene.blockFilter, buffer)) {
            return false;
        }
        gatheredScene = scene;
        gatheredMinX = minX;
        gatheredMinY = minY;
        gatheredMinZ = minZ;
        gatheredMaxX = maxX;
        gatheredMaxY = maxY;
        gatheredMaxZ = maxZ;
        return true;
    }

    /** Targets a cast must skip, by {@link Contact#identity()}. */
    @FunctionalInterface
    public interface Exclusion {
        boolean excluded(long identity);
    }

    /**
     * The earliest contact of a box (center {@code c}, half extents {@code h}) moving by {@code d} over
     * {@code dt} ticks, during which entities and colliders move by their per-tick motion times {@code dt}.
     * Returns {@link #HIT} (filling {@code out}), {@link #NONE} or {@link #MISS}.
     */
    public int cast(CollisionScene scene, double cx, double cy, double cz, double hx, double hy, double hz,
                    double dx, double dy, double dz, double dt, @Nullable Exclusion exclusion, Contact out) {
        scene.stats.casts.increment();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length <= MAX_CAST)) return NONE;
        double bestT = Double.POSITIVE_INFINITY;
        if (length > 0 && scene.blocks != null && scene.blockMask != 0) {
            int steps = length > SEGMENT ? (int) Math.ceil(length / SEGMENT) : 1;
            for (int i = 0; i < steps; i++) {
                double from = (double) i / steps, span = 1.0 / steps;
                int result = castBlocks(scene, cx + dx * from, cy + dy * from, cz + dz * from, hx, hy, hz,
                        dx * span, dy * span, dz * span, exclusion, out);
                if (result == MISS) return MISS;
                if (result == HIT) {
                    out.t = from + out.t * span;
                    bestT = out.t;
                    break;
                }
            }
        }
        if (scene.entities.length == 0 && scene.colliders.length == 0) {
            if (bestT == Double.POSITIVE_INFINITY) return NONE;
            scene.stats.contacts.increment();
            return HIT;
        }
        double tx = BoxCast.tolerance(cx), ty = BoxCast.tolerance(cy), tz = BoxCast.tolerance(cz);
        for (var entity : scene.entities) {
            long identity = (long) entity.id() * 4 + Contact.ENTITY;
            if (exclusion != null && exclusion.excluded(identity)) continue;
            double mx = entity.motionX() * dt, my = entity.motionY() * dt, mz = entity.motionZ() * dt;
            // in the entity's frame: its box where this step starts, the body moving relative to it
            if (!BoxCast.boxVsBox(cx, cy, cz, hx, hy, hz, dx - mx, dy - my, dz - mz,
                    entity.minX() - mx, entity.minY() - my, entity.minZ() - mz,
                    entity.maxX() - mx, entity.maxY() - my, entity.maxZ() - mz, tx, ty, tz, hit)) continue;
            if (!(hit.t < bestT)) continue;
            bestT = hit.t;
            double ex = -mx * (1 - hit.t), ey = -my * (1 - hit.t), ez = -mz * (1 - hit.t);
            fill(out, hit, Contact.ENTITY, entity.id(), entity.entity(), entity.trigger());
            out.vx = entity.motionX();
            out.vy = entity.motionY();
            out.vz = entity.motionZ();
            out.px = clamp(cx + dx * hit.t - hit.nx * hx, entity.minX() + ex, entity.maxX() + ex);
            out.py = clamp(cy + dy * hit.t - hit.ny * hy, entity.minY() + ey, entity.maxY() + ey);
            out.pz = clamp(cz + dz * hit.t - hit.nz * hz, entity.minZ() + ez, entity.maxZ() + ez);
        }
        double radius = Math.max(hx, Math.max(hy, hz));
        double tol = Math.max(tx, Math.max(ty, tz));
        for (var collider : scene.colliders) {
            long identity = collider.index * 4 + Contact.COLLIDER;
            if (exclusion != null && exclusion.excluded(identity)) continue;
            double mx = collider.mx * dt, my = collider.my * dt, mz = collider.mz * dt;
            // in the collider's frame, as for entities: offset the body by what the collider still moves
            double rx = cx + mx, ry = cy + my, rz = cz + mz;
            if (!collider.cast(rx, ry, rz, radius, dx - mx, dy - my, dz - mz, tol, hit) || !(hit.t < bestT)) continue;
            bestT = hit.t;
            fill(out, hit, Contact.COLLIDER, collider.index, collider.source, collider.trigger);
            out.vx = collider.mx;
            out.vy = collider.my;
            out.vz = collider.mz;
            double back = 1 - hit.t;
            collider.closestPoint(rx + (dx - mx) * hit.t, ry + (dy - my) * hit.t, rz + (dz - mz) * hit.t, closest);
            out.px = closest[0] - mx * back;
            out.py = closest[1] - my * back;
            out.pz = closest[2] - mz * back;
        }
        if (bestT != Double.POSITIVE_INFINITY) {
            scene.stats.contacts.increment();
            return HIT;
        }
        return NONE;
    }

    private int castBlocks(CollisionScene scene, double cx, double cy, double cz, double hx, double hy, double hz,
                           double dx, double dy, double dz, @Nullable Exclusion exclusion, Contact out) {
        double tx = BoxCast.tolerance(cx), ty = BoxCast.tolerance(cy), tz = BoxCast.tolerance(cz);
        if (!gather(scene, Math.min(cx, cx + dx) - hx - tx, Math.min(cy, cy + dy) - hy - ty,
                Math.min(cz, cz + dz) - hz - tz, Math.max(cx, cx + dx) + hx + tx,
                Math.max(cy, cy + dy) + hy + ty, Math.max(cz, cz + dz) + hz + tz)) {
            scene.stats.misses.increment();
            return MISS;
        }
        scene.stats.boxTests.add(buffer.size);
        double bestT = Double.POSITIVE_INFINITY;
        var boxes = buffer.boxes;
        for (int i = 0; i < buffer.size; i++) {
            int n = i * 6;
            double minY = boxes[n + 1];
            double maxY = boxes[n + 4];
            boolean fluid = buffer.kinds[i] == BoxBuffer.FLUID;
            if (fluid) {
                // a surface: met only by a body that starts above it and moves down
                if (dy >= 0 || cy - hy < maxY - ty) continue;
                minY = maxY - SURFACE;
            }
            byte kind = fluid ? Contact.FLUID : Contact.BLOCK;
            long key = buffer.keys[i];
            if (exclusion != null && exclusion.excluded(key * 4 + kind)) continue;
            if (!BoxCast.boxVsBox(cx, cy, cz, hx, hy, hz, dx, dy, dz,
                    boxes[n], minY, boxes[n + 2], boxes[n + 3], maxY, boxes[n + 5], tx, ty, tz, hit)) continue;
            if ((fluid && hit.ny != 1) || hit.t >= bestT) continue;
            bestT = hit.t;
            fill(out, hit, kind, key, buffer.payloads[i], false);
            out.px = clamp(cx + dx * hit.t - hit.nx * hx, boxes[n], boxes[n + 3]);
            out.py = clamp(cy + dy * hit.t - hit.ny * hy, minY, maxY);
            out.pz = clamp(cz + dz * hit.t - hit.nz * hz, boxes[n + 2], boxes[n + 5]);
        }
        return bestT == Double.POSITIVE_INFINITY ? NONE : HIT;
    }

    /** {@link Math#clamp} without its throw on NaN or crossed bounds. */
    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void fill(Contact out, BoxCast.Hit hit, byte kind, long key, @Nullable Object payload, boolean trigger) {
        out.t = hit.t;
        out.nx = hit.nx;
        out.ny = hit.ny;
        out.nz = hit.nz;
        out.vx = out.vy = out.vz = 0;
        out.kind = kind;
        out.key = key;
        out.payload = payload;
        out.trigger = trigger;
    }

    /**
     * Pushes a box out of the blocks and solid colliders it overlaps at the start of a step of {@code dt} ticks,
     * at most {@code maxDepth} in total. Writes the push into {@code push}; false when it cannot be freed (it
     * stays, and casts ignore what it is in).
     */
    public boolean depenetrate(CollisionScene scene, double cx, double cy, double cz, double hx, double hy, double hz,
                               double dt, double maxDepth, double[] push) {
        push[0] = push[1] = push[2] = 0;
        boolean blocks = scene.blocks != null && (scene.blockMask & BlockBoxSource.BLOCKS) != 0;
        if (!blocks && scene.colliders.length == 0) return true;
        double radius = Math.max(hx, Math.max(hy, hz));
        double total = 0;
        for (int iteration = 0; iteration < 4; iteration++) {
            double x = cx + push[0], y = cy + push[1], z = cz + push[2];
            double tx = BoxCast.tolerance(x), ty = BoxCast.tolerance(y), tz = BoxCast.tolerance(z);
            double depth = 0, nx = 0, ny = 0, nz = 0;
            if (blocks) {
                if (!gather(scene, x - hx - tx, y - hy - ty, z - hz - tz, x + hx + tx, y + hy + ty, z + hz + tz)) {
                    push[0] = push[1] = push[2] = 0;
                    return false;
                }
                if (overlapping(x, y, z, hx, hy, hz, tx, ty, tz, true) > 0) {
                    // where every way out could end, so a push into a neighbouring block is seen as one
                    double reach = maxDepth - total;
                    if (!gather(scene, x - hx - tx - reach, y - hy - ty - reach, z - hz - tz - reach,
                            x + hx + tx + reach, y + hy + ty + reach, z + hz + tz + reach)) {
                        push[0] = push[1] = push[2] = 0;
                        return false;
                    }
                    // every way out of every overlapped box: the one leaving the fewest overlaps wins, then the
                    // shortest. The shortest alone pushes a body near a seam into the neighbouring block and back.
                    int bestLeft = Integer.MAX_VALUE, bestDirection = -1;
                    double bestDepth = 0;
                    var boxes = buffer.boxes;
                    for (int i = 0; i < buffer.size; i++) {
                        if (buffer.kinds[i] != BoxBuffer.BLOCK) continue;
                        int n = i * 6;
                        if (!BoxCast.pushes(x, y, z, hx, hy, hz, boxes[n], boxes[n + 1], boxes[n + 2],
                                boxes[n + 3], boxes[n + 4], boxes[n + 5], tx, ty, tz, pushes)) continue;
                        for (int direction : PUSH_ORDER) {
                            double d = pushes[direction];
                            if (total + d > maxDepth || (bestLeft == 0 && d >= bestDepth)) continue;
                            int left = overlapping(x + PUSH_X[direction] * d, y + PUSH_Y[direction] * d,
                                    z + PUSH_Z[direction] * d, hx, hy, hz, tx, ty, tz, false);
                            if (left < bestLeft || (left == bestLeft && d < bestDepth)) {
                                bestLeft = left;
                                bestDepth = d;
                                bestDirection = direction;
                            }
                        }
                    }
                    if (bestDirection < 0) {
                        push[0] = push[1] = push[2] = 0;
                        return false;
                    }
                    depth = bestDepth;
                    nx = PUSH_X[bestDirection];
                    ny = PUSH_Y[bestDirection];
                    nz = PUSH_Z[bestDirection];
                }
            }
            if (depth == 0) {
                double tol = Math.max(tx, Math.max(ty, tz));
                for (var collider : scene.colliders) {
                    if (collider.trigger) continue;
                    // where it is when the step starts: the cast sweeps its motion during the step
                    double d = collider.separation(x + collider.mx * dt, y + collider.my * dt, z + collider.mz * dt,
                            radius, tol, hit);
                    if (d > depth && total + d <= maxDepth) {
                        depth = d;
                        nx = hit.nx;
                        ny = hit.ny;
                        nz = hit.nz;
                    }
                }
            }
            if (depth == 0) {
                if (total > 0) scene.stats.depenetrations.increment();
                return true;
            }
            total += depth;
            push[0] += nx * depth;
            push[1] += ny * depth;
            push[2] += nz * depth;
        }
        push[0] = push[1] = push[2] = 0;
        return false;
    }

    /** How many gathered blocks the box overlaps; with {@code any}, stops at the first. */
    private int overlapping(double x, double y, double z, double hx, double hy, double hz, double tx, double ty, double tz,
                            boolean any) {
        int count = 0;
        var boxes = buffer.boxes;
        for (int i = 0; i < buffer.size; i++) {
            int n = i * 6;
            if (buffer.kinds[i] == BoxBuffer.BLOCK && BoxCast.overlaps(x, y, z, hx, hy, hz, boxes[n], boxes[n + 1],
                    boxes[n + 2], boxes[n + 3], boxes[n + 4], boxes[n + 5], tx, ty, tz)) {
                count++;
                if (any) return count;
            }
        }
        return count;
    }

    public void overlapTriggers(CollisionScene scene, double cx, double cy, double cz, double hx, double hy, double hz,
                                LongConsumer identities) {
        for (var entity : scene.entities) {
            if (entity.trigger() && cx + hx > entity.minX() && cx - hx < entity.maxX() && cy + hy > entity.minY()
                    && cy - hy < entity.maxY() && cz + hz > entity.minZ() && cz - hz < entity.maxZ()) {
                identities.accept((long) entity.id() * 4 + Contact.ENTITY);
            }
        }
        double radius = Math.max(hx, Math.max(hy, hz));
        for (var collider : scene.colliders) {
            if (collider.trigger && collider.contains(cx, cy, cz, radius)) {
                identities.accept(collider.index * 4 + Contact.COLLIDER);
            }
        }
    }

    /**
     * A ray from {@code o} to {@code e} against the blocks of {@code world}, with vanilla's {@link ClipContext}
     * shape and fluid rules. Game thread only: non-collision shapes and fluids are read from the level.
     */
    public int raycast(CollisionWorld world, double ox, double oy, double oz, double ex, double ey, double ez,
                       ClipContext.Block blockMode, ClipContext.Fluid fluidMode, Contact out) {
        double dx = ex - ox, dy = ey - oy, dz = ez - oz;
        if (dx == 0 && dy == 0 && dz == 0) return NONE;
        var level = world.level();
        if (level == null) return MISS;
        boolean collider = blockMode == ClipContext.Block.COLLIDER;
        var pos = new BlockPos.MutableBlockPos();
        var walk = this.walk;
        walk.start(ox, oy, oz, ex, ey, ez);
        // a ray crosses at most this many cells
        int cells = (int) Math.min(1 << 20, Math.abs(dx) + Math.abs(dy) + Math.abs(dz) + 8);
        for (int guard = 0; guard < cells; guard++) {
            int x = walk.x, y = walk.y, z = walk.z;
            pos.set(x, y, z);
            // the snapshot keeps collision shapes and fluids only: outlines and visuals need the real block
            var state = collider ? world.stateAt(x, y, z) : level.getBlockState(pos);
            if (state == null) return MISS;
            double best = Double.POSITIVE_INFINITY;
            if (!state.isAir()) {
                double[] boxes;
                if (collider) {
                    var shape = BlockShape.of(state);
                    boxes = shape.dynamic ? BlockShape.at(state, level, pos) : shape.boxes;
                } else {
                    boxes = BlockShape.pack(blockMode.get(state, level, pos, CollisionContext.empty()));
                }
                best = rayBoxes(boxes, x, y, z, ox, oy, oz, dx, dy, dz, best, Contact.BLOCK, pos.asLong(), state, out);
            }
            var fluid = state.getFluidState();
            if (!fluid.isEmpty() && fluidMode.canPick(fluid)) {
                best = rayBoxes(BlockShape.pack(fluid.getShape(level, pos)), x, y, z, ox, oy, oz, dx, dy, dz,
                        best, Contact.FLUID, pos.asLong(), state, out);
            }
            if (best != Double.POSITIVE_INFINITY) return HIT;
            if (!walk.next()) return NONE;
        }
        return NONE;
    }

    private double rayBoxes(double[] boxes, int x, int y, int z, double ox, double oy, double oz,
                            double dx, double dy, double dz, double best, byte kind, long key, Object payload, Contact out) {
        for (int i = 0; i < boxes.length; i += 6) {
            if (!BoxCast.rayVsBox(ox, oy, oz, dx, dy, dz, x + boxes[i], y + boxes[i + 1], z + boxes[i + 2],
                    x + boxes[i + 3], y + boxes[i + 4], z + boxes[i + 5], hit) || hit.t >= best) continue;
            best = hit.t;
            fill(out, hit, kind, key, payload, false);
            out.px = ox + dx * hit.t;
            out.py = oy + dy * hit.t;
            out.pz = oz + dz * hit.t;
        }
        return best;
    }

    /** A ray, inflated by {@code inflate}, against the entities of {@code level}. Game thread only. */
    public boolean raycastEntities(Level level, double ox, double oy, double oz, double ex, double ey, double ez,
                                   double inflate, Predicate<Entity> filter, Contact out) {
        var box = new AABB(ox, oy, oz, ex, ey, ez).inflate(inflate + 1);
        double best = Double.POSITIVE_INFINITY;
        double dx = ex - ox, dy = ey - oy, dz = ez - oz;
        for (var entity : level.getEntities((Entity) null, box, filter)) {
            var bounds = entity.getBoundingBox().inflate(inflate);
            if (!BoxCast.rayVsBox(ox, oy, oz, dx, dy, dz, bounds.minX, bounds.minY, bounds.minZ,
                    bounds.maxX, bounds.maxY, bounds.maxZ, hit) || hit.t >= best) continue;
            best = hit.t;
            fill(out, hit, Contact.ENTITY, entity.getId(), entity, false);
            out.px = ox + dx * hit.t;
            out.py = oy + dy * hit.t;
            out.pz = oz + dz * hit.t;
        }
        return best != Double.POSITIVE_INFINITY;
    }
}
