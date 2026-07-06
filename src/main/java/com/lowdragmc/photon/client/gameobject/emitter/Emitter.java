package com.lowdragmc.photon.client.gameobject.emitter;

import com.lowdragmc.lowdraglib2.utils.virtuallevel.DummyWorld;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.ParticleQueueRenderType;
import lombok.Getter;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public abstract class Emitter extends FXObject implements IParticleEmitter {
    // runtime
    @Nullable
    protected Vector3f previousPosition;
    protected Vector3f velocity = new Vector3f();
    @Getter
    protected float t;
    /** Fractional simulation age (ticks). Replaces the integer {@code age} so the speed track can advance
     *  it by a fractional {@code dt}; {@link #getAge()} exposes the rounded value for display/emission. */
    protected float ageF = 0;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockPos, Integer> lightCache = new ConcurrentHashMap<>();
    /** True while particles update on worker threads: {@link #getLightColor} must not touch the level. */
    private volatile boolean parallelLightPhase = false;
    /** Positions particles asked for during the parallel phase; refreshed on the game thread post-loop. */
    private final Set<BlockPos> lightQueryQueue = ConcurrentHashMap.newKeySet();

    protected Emitter() {
        this.friction = 1;
    }

    public RandomSource getRandomSource() {
        return random;
    }

    @Override
    protected void onTickBegin() {
        if (!isAlive()) {
            return;
        }
        if (previousPosition != null) {
            velocity = transform.position().sub(previousPosition, new Vector3f());
        }
        previousPosition = transform.position();

        if (clearsLightCacheOnTickBegin()) {
            lightCache.clear();
        }
        updateOrigin(); // snapshot render origin once per tick (see FXObject.tick)
    }

    @Override
    public final void updateTick(float dt) {
        super.updateTick(dt);
        if (!isAlive()) {
            return;
        }
        update(dt);
    }

    @Override
    public void setPos(double x, double y, double z) {
        //noinspection ConstantValue
        if (this.transform == null) return;
        transform.position(new Vector3f((float)x, (float)y, (float)z));
    }

    @Override
    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }

    protected void update(float dt) {
        this.ageF += dt;
        this.age = (int) this.ageF;
        if (this.ageF >= getLifetime() && !isLooping()) {
            this.remove(false);
        }
        if (getLifetime() > 0) {
            if(isLooping())
                t = (this.ageF % getLifetime()) / getLifetime();
            else
                t = Math.clamp(this.ageF / getLifetime(), 0f, 1f);
        }
    }

    protected void updateOrigin() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
    }

    protected int getLightColor(float partialTick) {
        BlockPos blockPos = new BlockPos((int) this.x, (int) this.y, (int) this.z);
        var level = getLevel();
        if (level != null && (level.isLoaded(blockPos) || level instanceof DummyWorld)) {
            return LevelRenderer.getLightColor(level, blockPos);
        }
        return 0;
    }

    public float getT(float partialTicks) {
        if (this.lifetime > 0){
            if (!isLooping()) return Math.clamp(t + partialTicks / this.lifetime, 0f, 1f);
            return t + partialTicks / this.lifetime;
        }
        return 0;
    }

    public float getMemRandom(Object object) {
        return getMemRandom(object, RandomSource::nextFloat);
    }

    public float getMemRandom(Object object, Function<RandomSource, Float> randomFunc) {
        var value = memRandom.get(object);
        if (value == null) return memRandom.computeIfAbsent(object, o -> randomFunc.apply(getRandomSource()));
        return value;
    }

    public void reset() {
        super.reset();
        this.memRandom.clear();
        this.previousPosition = null;
        this.velocity.zero();
        this.t = 0;
        this.ageF = 0;
    }

    public boolean useTranslucentPipeline() {
        return true;
    }

    @Nonnull
    public final ParticleRenderType getRenderType() {
        return useTranslucentPipeline() ? ParticleQueueRenderType.TRANSLUCENT_QUEUE : ParticleQueueRenderType.OPAQUE_QUEUE;
    }

    @Override
    public boolean isAlive() {
        if (!removed || getParticleAmount() != 0) return true;
        return super.isAlive();
    }

    @Override
    public int getLightColor(BlockPos pos) {
        return getLightColor(pos, 0);
    }

    @Override
    public int getLightColor(BlockPos pos, int lastLight) {
        if (parallelLightPhase) {
            // worker thread: read-only on the level. Record the position (on hit AND miss, so the
            // wanted-set tracks live particles) and fall back to the caller's last value on a miss;
            // rebuildLightCache() fills it on the game thread — correct next tick (1-tick latency).
            lightQueryQueue.add(pos);
            var cached = lightCache.get(pos);
            return cached != null ? cached : lastLight;
        }
        return lightCache.computeIfAbsent(pos, this::computeLightColor);
    }

    /** Game-thread only: the actual level/light-engine query. */
    private int computeLightColor(BlockPos pos) {
        var level = getLevel();
        if (level != null && (level.isLoaded(pos) || level instanceof DummyWorld)) {
            return LevelRenderer.getLightColor(level, pos);
        }
        return 0;
    }

    /**
     * Whether the light cache resets at tick begin (default). Emitters that update particles on
     * worker threads must return false — a begin-clear would guarantee a miss on every parallel
     * read — and call {@link #rebuildLightCache()} at tick end instead.
     */
    protected boolean clearsLightCacheOnTickBegin() {
        return true;
    }

    /** Toggled by the owning emitter around its worker-thread particle update phase. */
    protected void setParallelLightPhase(boolean value) {
        this.parallelLightPhase = value;
    }

    /**
     * Game-thread only: recompute every position requested this tick and evict the rest, so light
     * stays fresh (≤1 tick stale) and the cache stays bounded to positions actually in use.
     */
    protected void rebuildLightCache() {
        lightCache.clear();
        for (var pos : lightQueryQueue) {
            lightCache.put(pos, computeLightColor(pos));
        }
        lightQueryQueue.clear();
    }

    @Override
    @Nonnull
    public AABB getRenderBoundingBox(float partialTicks) {
        var cullBox = getCullBox(partialTicks);
        return cullBox == null ? AABB.INFINITE : cullBox;
    }

    public int getAge() {
        return (int) ageF;
    }

    /** Fractional simulation age (ticks), used by dt-aware emission cadence. */
    public float getAgeF() {
        return ageF;
    }

    public void setAge(int age) {
        this.ageF = age;
        this.age = age;
    }

    public boolean isLooping() {
        return false;
    }

    /**
     * Configured start delay in ticks before this emitter begins. Used (with {@link #getLifetime()})
     * to compute a sensible default timeline clip length. Defaults to 0; emitter types with a start
     * delay override this.
     */
    public int getStartDelay() {
        return 0;
    }

    public void setRGBAColor(Vector4f color) {
        this.rCol = color.x;
        this.gCol = color.y;
        this.bCol = color.z;
        this.alpha = color.w;
    }

    public Vector4f getRGBAColor() {
        return new Vector4f(rCol, gCol, bCol, alpha);
    }
}
