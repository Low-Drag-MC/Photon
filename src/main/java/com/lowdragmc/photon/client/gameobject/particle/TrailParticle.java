package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.trail.TrailConfig;
import com.lowdragmc.photon.client.gameobject.emitter.trail.TrailEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.trail.TrailRuntime;
import it.unimi.dsi.fastutil.floats.Float2ObjectFunction;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2022/05/30
 * @implNote TrailParticle
 */
@OnlyIn(Dist.CLIENT)
public class TrailParticle implements IParticle {
    public enum UVMode {
        Stretch,
        Tile
    }
    /**
     * Basic data
     */
    protected float r = 1, g = 1, b = 1, a = 1; // color
    protected float ro = 1, go = 1, bo = 1, ao = 1;
    protected int light = -1;
    @Setter @Getter
    protected boolean dieWhenAllTailsRemoved = true;

    /**
     * Life cycle
     */
    @Setter @Getter
    protected float delay; // fractional ticks: consumed by dt per step, not one whole tick per substep
    @Setter @Getter
    protected boolean isRemoved;
    @Getter
    @Setter
    protected Runnable onUpdate;
    @Getter
    @Setter
    protected Float2ObjectFunction<Vector3f> headPositionSupplier;
    @Getter
    @Setter
    protected Supplier<Float> lifetimeSupplier;
    @Getter
    @Setter
    protected Supplier<Float> widthMultiplier;
    @Getter
    @Setter
    protected Float2ObjectFunction<Vector4f> colorMultiplier;
    //runtime
    @Getter
    protected TailArray rawTails = new TailArray();
    @Getter
    protected TailArray tails = new TailArray();
    protected TrailConfig config;
    /** The owning emitter's per-instance runtime layer (timeline overrides + moved value behaviour). */
    @Getter
    protected TrailRuntime runtime;
    @Getter
    protected IParticleEmitter emitter;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    @Getter
    public RandomSource randomSource;

    public TrailParticle(IParticleEmitter emitter, TrailConfig config) {
        this.emitter = emitter;
        this.config = config;
        this.runtime = emitter instanceof TrailEmitter e ? e.runtime() : new TrailRuntime(config);
        this.randomSource = RandomSource.create(emitter.getRandomSource().nextLong());
        this.headPositionSupplier = (t) -> emitter.transform().position();
        this.setup();
    }

    public void setup() {
        this.setDelay(runtime.startDelay.get());
        this.lifetimeSupplier = () -> (float) runtime.time.get();
        update(1f);
        updateOrigin();
        tails.clear();
        rawTails.clear();
    }

    @Override
    public PhotonFXRenderPass getRenderType() {
        return config.particleRenderType;
    }

    @Override
    public boolean isAlive() {
        if (isRemoved) {
            return dieWhenAllTailsRemoved && !tails.isEmpty();
        }
        return true;
    }

    @Override
    public float getT() {
        return emitter.getT();
    }

    @Override
    public float getT(float partialTicks) {
        return emitter.getT(partialTicks);
    }

    @Override
    public float getMemRandom(Object object) {
        return getMemRandom(object, RandomSource::nextFloat);
    }

    @Override
    public float getMemRandom(Object object, Function<RandomSource, Float> randomFunc) {
        var value = memRandom.get(object);
        if (value == null) return memRandom.computeIfAbsent(object, o -> randomFunc.apply(randomSource));
        return value;
    }

    public Vector4f getRealColor(float partialTicks) {
        var emitterColor = emitter.getRGBAColor();
        var a = Mth.lerp(partialTicks, this.ao, this.a);
        var r = Mth.lerp(partialTicks, this.ro, this.r);
        var g = Mth.lerp(partialTicks, this.go, this.g);
        var b = Mth.lerp(partialTicks, this.bo, this.b);
        if (colorMultiplier != null) {
            var color = colorMultiplier.get(partialTicks);
            r *= color.x();
            g *= color.y();
            b *= color.z();
            a *= color.w();
        }
        return emitterColor.mul(r, g, b, a);
    }

    public int getRealLight(float partialTicks) {
        if (runtime.lights.isEnable()) {
            return runtime.lights.getLight(this, partialTicks);
        }
        return light;
    }

    public int getLightColor() {
        var pos = getHeadPosition();
        var blockPos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        return emitter.getLightColor(blockPos);
    }

    public Vector3f getHeadPosition() {
        return getHeadPosition(0);
    }

    public Vector3f getHeadPosition(float partialTicks) {
        return headPositionSupplier.get(partialTicks);
    }

    @Override
    public void updateTick(float dt) {
        if (delay > 0) {
            delay -= dt;
            return;
        }

        update(dt);
    }

    @Override
    public void syncOrigin() {
        updateOrigin();
    }

    protected void updateOrigin() {
        this.ro = this.r;
        this.go = this.g;
        this.bo = this.b;
        this.ao = this.a;
    }

    protected void update(float dt) {
        updateChanges(dt);
        if (onUpdate != null) {
            onUpdate.run();
        }
    }

    protected void updateChanges(float dt) {
        updateTails(dt);
        updateRawTailsProperties();
        if (config.isSmoothInterpolation()) {
            this.tails = generateSmoothPath(rawTails, this.tails, runtime.minVertexDistance.get());
        } else {
            this.tails = rawTails;
        }
        this.updateLight();
    }

    protected void updateTails(float dt) {

        for (int i = 0; i < rawTails.size(); i++) {
            rawTails.lifeTime[i] -= dt;
        }

        TailArray newRawTails = new TailArray();
        for (int i = 0; i < rawTails.size(); i++) {
            if (rawTails.lifeTime[i] >= 0) {
                rawTails.copyTailTo(newRawTails, i);
            }
        }
        rawTails = newRawTails;

        if (!isRemoved()) {
            var headPos = getHeadPosition();
            var shouldAdd = true;
            if (!rawTails.isEmpty()) {
                Vector3f last = rawTails.getPosition(rawTails.size() - 1);
                var minVertexDistance = runtime.minVertexDistance.get();
                if (headPos.distanceSquared(last) < minVertexDistance * minVertexDistance) {
                    shouldAdd = false;
                }
            }
            if (shouldAdd) {
                Tail newTail = new Tail(headPos, lifetimeSupplier.get(), new Vector4f(1, 1, 1, 1), 0.2f);
                rawTails.add(newTail);
            } else if (rawTails.size() == 1) {
                rawTails.posX[0] = headPos.x;
                rawTails.posY[0] = headPos.y;
                rawTails.posZ[0] = headPos.z;
                rawTails.lifeTime[0] = lifetimeSupplier.get();
            }
        }
    }

    public TailArray generateSmoothPath(TailArray vertices, TailArray lastSmooth, float distance) {
        if (vertices.size() <= 2) {
            return vertices;
        }

        TailArray smoothPath = new TailArray();
        float minDistanceSq = Math.max(distance, 0.05f) * Math.max(distance, 0.05f);
        Vector3f prevDir = null;

        for (int i = 0; i < vertices.size() - 1; i++) {
            var curr = vertices.getPosition(i);
            var next = vertices.getPosition(i + 1);
            var dir = new Vector3f(next).sub(curr).normalize();
            smoothPath.add(vertices.copyAsTail(i));

            if (prevDir != null && dir.dot(prevDir) > 0.99f) {
                // skip interpolation if angle is small
                prevDir = dir;
                continue;
            }

            float distSq = curr.distanceSquared(next);
            if (distSq > minDistanceSq) {
                int steps = (int) (distSq / minDistanceSq);
                for (int j = 1; j < steps; j++) {
                    float t = j / (float) steps;
                    Vector3f interpPos = catmullRomInterpolate(
                            vertices.getPosition(Math.max(i - 1, 0)),
                            curr, next,
                            vertices.getPosition(Math.min(i + 2, vertices.size() - 1)), t);
                    // build interpolated tail
                    smoothPath.add(new Tail(
                            interpPos,
                            Mth.lerp(t, vertices.lifeTime[i], vertices.lifeTime[i + 1]),
                            new Vector4f(vertices.getColor(i)).lerp(vertices.getColor(i + 1), t),
                            Mth.lerp(t, vertices.width[i], vertices.width[i + 1])
                    ));
                }
            }
            prevDir = dir;
        }
        smoothPath.add(vertices.copyAsTail(vertices.size() - 1));
        return smoothPath;
    }

    public static Vector3f catmullRomInterpolate(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        Vector3f result = new Vector3f();
        result.x = 0.5f * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
        result.y = 0.5f * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
        result.z = 0.5f * (2 * p1.z + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3);
        return result;
    }

    protected void updateRawTailsProperties() {
        if (rawTails.size() > 1) {
            for (int i = 0; i < rawTails.size(); i++) {
                updateRawTrailProperties(i, rawTails.size());
            }
        } else if (rawTails.size() == 1) {
            updateRawTrailProperties(0, 2);
        }
    }

    protected void updateRawTrailProperties(int tailIndex, int tailsSize) {
        float t = ((float) tailIndex) / (tailsSize - 1);

        int colorInt = runtime.colorOverTrail.get().get(t, () -> getMemRandom("trails-colorOverTrail")).intValue();
        rawTails.colorR[tailIndex] = ColorUtils.red(colorInt);
        rawTails.colorG[tailIndex] = ColorUtils.green(colorInt);
        rawTails.colorB[tailIndex] = ColorUtils.blue(colorInt);
        rawTails.colorA[tailIndex] = ColorUtils.alpha(colorInt);

        float widthValue = runtime.widthOverTrail.get().get(t, () -> getMemRandom("trails-widthOverTrail")).floatValue();
        if (widthMultiplier != null) {
            widthValue *= widthMultiplier.get();
        }
        rawTails.width[tailIndex] = widthValue;
    }

    protected void updateLight() {
        if (runtime.lights.isEnable()) return;
        var pos = getHeadPosition();
        var blockPos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        // fallback keeps the last light on a (parallel-phase) cache miss; corrected next tick
        light = emitter.getLightColor(blockPos, Math.max(light, 0));
    }

    public Vector4f getUVs(int tailIndex, int size, float partialTicks) {
        float u0, u1, v0, v1;
        var uvMode = config.getUvMode();
        if (uvMode == UVMode.Stretch) {
            u0 = tailIndex / (size - 1f);
            u1 = (tailIndex + 1f) / (size - 1f);
            v0 = 0;
            v1 = 1;

            if (runtime.uvAnimation.isEnable()) {
                var uvs = runtime.uvAnimation.getUVs(this, partialTicks);
                var x = uvs.x;
                var y = uvs.y;
                var w = uvs.z - uvs.x;
                var h = uvs.w - uvs.y;
                u0 = x + w * u0;
                v0 = y + h * v0;
                u1 = x + w * u1;
                v1 = y + h * v1;
            }
        } else {
            if (runtime.uvAnimation.isEnable()) {
                var uvs = runtime.uvAnimation.getUVs(this, partialTicks);
                u0 = uvs.x();
                v0 = uvs.y();
                u1 = uvs.z();
                v1 = uvs.w();
            } else {
                u0 = 0;
                v0 = 0;
                u1 = 1;
                v1 = 1;
            }
        }
        return new Vector4f(u0, v0, u1, v1);
    }

    public class TailArray {
        private int size = 0;
        private int capacity = 16;

        private float[] posX = new float[capacity];
        private float[] posY = new float[capacity];
        private float[] posZ = new float[capacity];

        private float[] colorR = new float[capacity];
        private float[] colorG = new float[capacity];
        private float[] colorB = new float[capacity];
        private float[] colorA = new float[capacity];

        private float[] width = new float[capacity];
        private float[] lifeTime = new float[capacity];

        public void clear() {
            size = 0;
        }

        public int size() {
            return size;
        }

        public boolean isEmpty() {
            return size == 0;
        }

        public void add(Tail tail) {
            ensureCapacity();
            posX[size] = tail.position.x;
            posY[size] = tail.position.y;
            posZ[size] = tail.position.z;

            colorR[size] = tail.color.x;
            colorG[size] = tail.color.y;
            colorB[size] = tail.color.z;
            colorA[size] = tail.color.w;

            width[size] = tail.width;
            lifeTime[size] = tail.lifeTime;
            size++;
        }

        public void removeLast() {
            if (size > 0) {
                size--;
            }
        }

        public Vector3f getPosition(int index) {
            return new Vector3f(posX[index], posY[index], posZ[index]);
        }

        public Vector4f getColor(int index) {
            return new Vector4f(colorR[index], colorG[index], colorB[index], colorA[index]);
        }

        public float getWidth(int index) {
            return width[index];
        }

        public float getLifeTime(int index) {
            return lifeTime[index];
        }

        private void ensureCapacity() {
            if (size >= capacity) {
                int newCapacity = capacity * 2;
                posX = Arrays.copyOf(posX, newCapacity);
                posY = Arrays.copyOf(posY, newCapacity);
                posZ = Arrays.copyOf(posZ, newCapacity);

                colorR = Arrays.copyOf(colorR, newCapacity);
                colorG = Arrays.copyOf(colorG, newCapacity);
                colorB = Arrays.copyOf(colorB, newCapacity);
                colorA = Arrays.copyOf(colorA, newCapacity);

                width = Arrays.copyOf(width, newCapacity);
                lifeTime = Arrays.copyOf(lifeTime, newCapacity);

                capacity = newCapacity;
            }
        }

        private void copyTailTo(TailArray dest, int index) {
            dest.ensureCapacity();
            dest.posX[dest.size()] = posX[index];
            dest.posY[dest.size()] = posY[index];
            dest.posZ[dest.size()] = posZ[index];

            dest.colorR[dest.size()] = colorR[index];
            dest.colorG[dest.size()] = colorG[index];
            dest.colorB[dest.size()] = colorB[index];
            dest.colorA[dest.size()] = colorA[index];

            dest.width[dest.size()] = width[index];
            dest.lifeTime[dest.size()] = lifeTime[index];
            dest.size++;
        }

        private Tail copyAsTail(int index) {
            Vector3f pos = getPosition(index);
            return new Tail(pos, lifeTime[index], getColor(index), getWidth(index));
        }

    }

    public static class Tail {
        public final float lifeTime;
        public final Vector3f position;
        public final Vector4f color;
        public final float width;

        public Tail(Vector3f position, float lifeTime, Vector4f color, float width) {
            this.position = position;
            this.lifeTime = lifeTime;
            this.color = color;
            this.width = width;
        }
    }
}
