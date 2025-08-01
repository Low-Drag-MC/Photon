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
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockPos, Integer> lightCache = new ConcurrentHashMap<>();

    protected Emitter() {
        this.friction = 1;
    }

    public RandomSource getRandomSource() {
        return random;
    }

    @Override
    public final void updateTick() {
        super.updateTick();
        if (!isAlive()) {
            return;
        }

        if (previousPosition != null) {
            velocity = transform.position().sub(previousPosition, new Vector3f());
        }
        previousPosition = transform.position();

        lightCache.clear();
        updateOrigin();
        update();
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

    protected void update() {
        this.age++;
        if (this.age >= getLifetime() && !isLooping()) {
            this.remove(false);
        }
        if (getLifetime() > 0) {
            t = (this.age % getLifetime()) * 1f / getLifetime();
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
        if (this.lifetime > 0 ){
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
        return lightCache.computeIfAbsent(pos, p -> {
            var level = getLevel();
            if (level != null && (level.isLoaded(p) || level instanceof DummyWorld)) {
                return LevelRenderer.getLightColor(level, p);
            }
            return 0;
        });
    }

    @Override
    @Nonnull
    public AABB getRenderBoundingBox(float partialTicks) {
        var cullBox = getCullBox(partialTicks);
        return cullBox == null ? AABB.INFINITE : cullBox;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public boolean isLooping() {
        return false;
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
