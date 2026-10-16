package com.lowdragmc.photon.client.gameobject.particle.collision;

import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.CollisionSettings;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

public final class CollisionRuntime {
    private final ParticleEmitter emitter;
    private final CollisionFrame ownFrame = new CollisionFrame();
    private CollisionRuntime cacheOwner;
    private CollisionFrame currentFrame;
    private IntOpenHashSet sourceEntities;
    private final ArrayDeque<Map<String, Object>> recentHits = new ArrayDeque<>();
    private long hitCount;
    private long simulationNanos;

    public CollisionRuntime(ParticleEmitter emitter) {
        this.emitter = emitter;
    }

    private CollisionSettings settings() {
        return emitter.config.physics.collision;
    }

    public CollisionFrame frame() {
        if (cacheOwner == null || cacheOwner.emitter.getScene() != emitter.getScene()) {
            cacheOwner = this;
            if (emitter.getScene() instanceof FXRuntime runtime) {
                for (var object : runtime.objects.values()) {
                    if (object instanceof ParticleEmitter candidate
                            && candidate.config.physics.collision.backend == CollisionSettings.Backend.CACHED_SWEEP) {
                        cacheOwner = candidate.collisionRuntime();
                        break;
                    }
                }
            }
        }
        currentFrame = cacheOwner.ownFrame;
        currentFrame.bind(emitter.getLevel(), collisionTick());
        if (sourceEntities == null && settings().entities && settings().sourceEntityExclusionRadius > 0) {
            var origin = emitter.getScene() instanceof FXRuntime runtime
                    ? runtime.root.transform().position() : emitter.transform().position();
            sourceEntities = currentFrame.sourceEntities(new Vec3(origin.x, origin.y, origin.z), settings().sourceEntityExclusionRadius);
        }
        return currentFrame;
    }

    public long collisionTick() {
        return emitter.getScene() instanceof FXRuntime runtime && runtime.root instanceof FXObject root
                ? root.getLastTick() : emitter.getLastTick();
    }

    public int ownerId() {
        if (settings().ownerEntityId >= 0) return settings().ownerEntityId;
        return emitter.getEffectExecutor() instanceof EntityEffectExecutor executor ? executor.entity.getId() : -1;
    }

    public boolean isSourceEntity(CollisionFrame.Target target) {
        return target.kind() == CollisionFrame.Kind.ENTITY && sourceEntities != null && sourceEntities.contains((int) target.id());
    }

    public boolean recordHit(CollisionFrame.Hit hit, Vec3 point) {
        String group = settings().deduplicationGroup.isBlank() ? emitter.id().toString() : settings().deduplicationGroup;
        if (settings().deduplicateEmitterHits && !frame().remember(group, hit.candidate().target(), settings().contactCooldownTicks)) return false;
        hitCount++;
        recentHits.add(Map.of("kind", hit.candidate().target().kind().name(), "target", hit.candidate().target().id(),
                "position", List.of(point.x, point.y, point.z), "normal", List.of(hit.normal().x, hit.normal().y, hit.normal().z), "tick", collisionTick()));
        while (recentHits.size() > 128) recentHits.remove();
        return true;
    }

    public void recordSimulationTime(long nanos) {
        simulationNanos += nanos;
    }

    public Map<String, Object> statistics() {
        return Map.of("hits", hitCount, "simulationMilliseconds", simulationNanos / 1e6,
                "cache", currentFrame == null ? Map.of() : currentFrame.statistics(), "recentHits", List.copyOf(recentHits));
    }

    public void reset() {
        ownFrame.reset();
        currentFrame = null;
        sourceEntities = null;
        cacheOwner = null;
        recentHits.clear();
        hitCount = simulationNanos = 0;
    }
}
