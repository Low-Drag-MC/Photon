package com.lowdragmc.photon.client.gameobject.particle.aratrail;

import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailConfig;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailRuntime;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import it.unimi.dsi.fastutil.floats.Float2ObjectFunction;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.*;

import javax.annotation.Nullable;
import java.lang.Math;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;


public class AraTrailParticle implements IParticle {
    public final static float EPSILON = 0.00001f;

    public final IParticleEmitter emitter;
    public final AraTrailConfig config;
    /** The owning emitter's per-instance runtime layer (timeline overrides + moved value behaviour). */
    public final AraTrailRuntime runtime;

    // runtime
    private float initialThickness = 1;
    private Vector4f initialColor = new Vector4f(1);
    private Vector3f initialVelocity = new Vector3f();
    @Getter
    private final RandomSource randomSource;
    @Getter
    private final ElasticArray<Point> points = new ElasticArray<>(Point.class);
    @Getter
    private final ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    @Getter @Setter
    protected boolean isRemoved;
    @Getter @Setter
    protected boolean dieWhenAllTailsRemoved = true;

    @Setter
    private Vector3f velocity = new Vector3f();
    @Setter
    private Vector3f prevPosition = new Vector3f();
    @Getter @Setter
    private float accumTime = 0;

    // for trails setting
    @Getter @Setter @Nullable
    private Runnable onUpdate;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldPositionSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldForwardSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldUpSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector3f> worldRightSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Vector4f> colorMultiplierSupplier;
    @Getter @Setter @Nullable
    private Supplier<Float> lifetimeSupplier;
    @Getter @Setter @Nullable
    private Float2ObjectFunction<Float> thicknessMultiplierSupplier;

    private Vector3f worldPosition = new Vector3f();
    private Vector3f worldForward = new Vector3f();
    private Vector3f worldUp = new Vector3f();
    private Vector3f worldRight = new Vector3f();
    @Getter
    private Vector4f colorMultiplier = new Vector4f(1, 1, 1, 1);
    @Getter
    private float thicknessMultiplier = 1;
    @Getter
    private float lifeTime = 1;

    public AraTrailParticle(IParticleEmitter emitter, AraTrailConfig config) {
        this.emitter = emitter;
        this.config = config;
        this.runtime = emitter instanceof AraTrailEmitter e ? e.runtime() : new AraTrailRuntime(config);
        this.randomSource = RandomSource.create(emitter.getRandomSource().nextLong());
        setup();
    }

    @Override
    public PhotonFXRenderPass getRenderType() {
        return config.particleRenderType;
    }

    @Override
    public boolean isAlive() {
        if (isRemoved) {
            return dieWhenAllTailsRemoved && points.size() > 1;
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

    public Vector3f getVelocity() {
        return new Vector3f(velocity);
    }

    public Vector3f getPrevPosition() {
        return new Vector3f(prevPosition);
    }

    private float getFixedDeltaTime() {
//        return timescale == Timescale.Unscaled ? Time.getFixedUnscaledDeltaTime() : Time.getFixedDeltaTime();
        return 0.05f; // 1 / 20f;
    }

    public Matrix4f getWorldToTrail() {
        if (config.space == null) return new Matrix4f(); // null = World (identity); guards a bad/legacy config
        return switch (config.space) {
            case World -> new Matrix4f(); // identity matrix
            case Local -> getTransform().worldToLocalMatrix();
            case Custom -> {
                var transform = config.customSpace.getTransform(emitter.getScene());
                yield transform != null ? transform.worldToLocalMatrix() : new Matrix4f();
            }
        };
    }

    public Transform getTransform() {
        return emitter.transform();
    }

    /** Refresh the head pose / multipliers from the emitter or the TrailsSetting suppliers.
     *  Called by the tick sim (partialTicks 1) and by the renderer before each mesh build. */
    public void updateDynamicData(float partialTicks) {
        var transform = getTransform();
        this.worldPosition = worldPositionSupplier == null ? transform.position() : worldPositionSupplier.get(partialTicks);
        this.worldForward = worldForwardSupplier == null ? transform.forward() : worldForwardSupplier.get(partialTicks);
        this.worldUp = worldUpSupplier == null ? transform.up() : worldUpSupplier.get(partialTicks);
        this.worldRight = worldRightSupplier == null ? transform.right() : worldRightSupplier.get(partialTicks);
        this.colorMultiplier = colorMultiplierSupplier == null ? new Vector4f(1) : colorMultiplierSupplier.get(partialTicks);
        this.thicknessMultiplier = thicknessMultiplierSupplier == null ? 1 : thicknessMultiplierSupplier.get(partialTicks);
        this.lifeTime = lifetimeSupplier == null ? runtime.time.get() : lifetimeSupplier.get();
    }
    
    public Vector3f getWorldPosition() {
        return new Vector3f(worldPosition);
    }
    
    public Vector3f getWorldForward() {
        return new Vector3f(worldForward);
    }
    
    public Vector3f getWorldUp() {
        return new Vector3f(worldUp);
    }
    
    public Vector3f getWorldRight() {
        return new Vector3f(worldRight);
    }

    private void setup() {
        // initialize previous position, for correct velocity estimation in the first frame:
        warmup();
        prevPosition = getWorldPosition();
        velocity = new Vector3f();
        initialVelocity = config.initialVelocity;
        initialThickness = runtime.initialThickness.get();
        initialColor = new Vector4f(
                ColorUtils.red(config.initialColor),
                ColorUtils.green(config.initialColor),
                ColorUtils.blue(config.initialColor),
                ColorUtils.alpha(config.initialColor)
        );
    }

    /**
     * Removes all points in the trail, effectively removing any rendered trail segments.
     */
    public void clear() {
        points.clear();
    }

    private void updateVelocity(float deltaTime) {
        if (deltaTime > 0) {
            Vector3f deltaPosition = getWorldPosition().sub(prevPosition);
            Vector3f currentVelocity = deltaPosition.div(deltaTime);
            velocity = velocity.lerp(currentVelocity, runtime.physics.velocitySmoothing.get());
        }

        prevPosition = getWorldPosition();
    }

    /**
     * Advances the whole trail simulation in the deterministic fixed-tick path (so {@code simulateTo}
     * reproduces it exactly — seek == play — unlike the old render-driven emission). Runs once per tick,
     * aligned to MC's 20 TPS like every other particle; {@code dt} is in ticks and already carries the
     * speed-track {@code timeScale}. Samples the emitter's current-tick pose ({@code partialTicks} 1) and
     * mirrors {@link #warmup()}'s step order.
     */
    @Override
    public void updateTick(float dt) {
        if (dt <= 0) return;
        if (onUpdate != null) onUpdate.run();
        float timeStep = dt / 20f;      // 1/20 s per tick, already timeScale-scaled via dt
        updateDynamicData(1);           // current-tick emitter pose (no head lag)
        if (!isRemoved) {
            updateVelocity(timeStep);
            if (runtime.physics.isEnable()) physicsStep(timeStep);
            emissionStep(timeStep);
            snapLastPointToTransform();
        } else if (runtime.physics.isEnable()) {
            physicsStep(timeStep);
        }
        updatePointsLifecycle(timeStep);
    }

    private void emissionStep(float time) {
        // Acumulate the amount of time passed:
        accumTime += time;

        // If enough time has passed since the last emission (>= timeInterval), consider emitting new points.
        if (accumTime >= runtime.timeInterval.get()) {
            if (config.emit) {
                var position = getWorldToTrail().transformPosition(getWorldPosition());

                // If there's less than 2 points, or if the last 2 points are too far apart, spawn a new one:
                if (points.isEmpty() || (
                        position.distance(points.getLast().position) >= runtime.minDistance.get()
                )) {
                    emitPoint(position);
                    accumTime = 0;
                }
            }
        }
    }

    private void warmup() {
        if (!runtime.physics.isEnable()) return;
        float simulatedTime = runtime.physics.warmup.get();
        var fixedDeltaTime = getFixedDeltaTime();
        updateDynamicData(0);
        while (simulatedTime > fixedDeltaTime) {
            physicsStep(fixedDeltaTime);
            emissionStep(fixedDeltaTime);
            snapLastPointToTransform();
            updatePointsLifecycle(fixedDeltaTime);
            simulatedTime -= fixedDeltaTime;
        }
    }

    private void physicsStep(float timestep) {
        float velocity_scale = (float) Math.pow(1 - Mth.clamp(runtime.physics.damping.get(), 0, 1), timestep);

        for (Point point : points) {
            // apply gravity and external forces:
            point.velocity.add(new Vector3f(runtime.physics.gravity.get()).mul(timestep));
            point.velocity.mul(velocity_scale);

            // integrate velocity:
            point.position.add(new Vector3f(point.velocity).mul(timestep));
        }
    }


    public void emitPoint(Vector3f position) {
        emitPoint(position, false);
    }
    /**
     * Spawns a new point in the trail.
     */
    public void emitPoint(Vector3f position, boolean skipLast) {
        // Adjust the current end of the trail, if any:
//        if (adjustEnd && points.size() > 1) {
//            Point lastPoint = points[points.Count - 1];
//            lastPoint.position = (position + points[points.Count - 2].position) * 0.5f;
//            points[points.Count - 1] = lastPoint;
//        }

        float texcoord = 0;

        // if there's a previous point in the trail, use its texcoord to calculate ours.
        if (!points.isEmpty())
            texcoord = points.getLast().texcoord + position.distance(points.getLast().position);

        var worldToTrail = getWorldToTrail();
        var nrm = worldToTrail.transformDirection(getWorldForward());
        var tgt = worldToTrail.transformDirection(getWorldRight());

        var point = new Point(position, new Vector3f(velocity).mul(runtime.physics.inertia.get()).add(initialVelocity),
                tgt, nrm, initialColor, initialThickness, texcoord, lifeTime);
        if (skipLast && points.size() > 1) {
            points.add(points.size() - 1, point);
        } else {
            points.add(point);
        }
    }

    /**
     * Makes sure the first point is always at the transform's center, and that its orientation matches it.
     */
    private void snapLastPointToTransform() {
        // Last point always coincides with transform:
        if (!points.isEmpty()) {
            Point lastPoint = points.getLast();

            // if we are not emitting, the last point is a discontinuity.
            if (!config.emit)
                lastPoint.discontinuous = true;

            // if the point is not discontinuous, move and orient it according to the transform.
            if (!lastPoint.discontinuous)
            {
                var worldToTrail = getWorldToTrail();
                lastPoint.position = worldToTrail.transformPosition(getWorldPosition());
                lastPoint.normal = worldToTrail.transformDirection(getWorldForward());
                lastPoint.tangent = worldToTrail.transformDirection(getWorldRight());

                // if there's a previous point in the trail, use its texcoord to calculate ours.
                if (points.size() > 1) {
                    lastPoint.texcoord = points.get(points.size() - 2).texcoord +
                            lastPoint.position.distance(points.get(points.size() - 2).position);
                }
            }

            points.set(points.size() - 1, lastPoint);
        }
    }

    /**
     * Updated trail lifetime and removes dead points.
     */
    private void updatePointsLifecycle(float deltaTime) {
        for (int i = points.size() - 1; i >= 0; --i)
        {

            var point = points.get(i);
            point.life -= deltaTime;

            if (point.life <= 0)
            {

                // Unsmoothed trails: wait until the next (newer) point is also dead, so the render-time tail
                // retract can shrink this segment to zero (f reaches 1 only when next.life hits 0) before
                // removal. Removing at life<=0 would pop a whole segment when the emission interval > 1 tick.
                if (config.smoothness <= 1)
                {
                    if (points.get(Math.min(i + 1, points.size() - 1)).life <= 0)
                        points.removeAt(i);
                }
                // Smoothed trails however, should wait until the next 2 points are dead too. This ensures spline continuity.
                else
                {
                    if (points.get(Math.min(i + 1, points.size() - 1)).life <= 0 &&
                            points.get(Math.min(i + 2, points.size() - 1)).life <= 0)
                        points.removeAt(i);
                }

            }
        }
    }

    /**
     * Spatial frame, consisting of a point an three axis. This is used to implement the parallel transport method
     * along the curve defined by the trail points. Using this instead of a Frenet-esque method avoids flipped frames
     * at points where the curvature changes.
     */
    public static class CurveFrame {
        public Vector3f position;
        public Vector3f normal;
        public Vector3f bitangent;
        public Vector3f tangent;

        public CurveFrame(Vector3f position, Vector3f normal, Vector3f bitangent, Vector3f tangent) {
            this.position = position;
            this.normal = normal;
            this.bitangent = bitangent;
            this.tangent = tangent;
        }

        public Vector3f transport(Vector3f newTangent, Vector3f newPosition) {
            // double-reflection rotation-minimizing frame transport:
            Vector3f v1 = new Vector3f(newPosition).sub(position);
            float c1 = v1.dot(v1);

            Vector3f rL = new Vector3f(normal).sub(
                    new Vector3f(v1).mul(2 / (c1 + EPSILON) * v1.dot(normal))
            );
            Vector3f tL = new Vector3f(tangent).sub(
                    new Vector3f(v1).mul(2 / (c1 + EPSILON) * v1.dot(tangent))
            );

            Vector3f v2 = new Vector3f(newTangent).sub(tL);
            float c2 = v2.dot(v2);

            Vector3f r1 = new Vector3f(rL).sub(
                    new Vector3f(v2).mul(2 / (c2 + EPSILON) * v2.dot(rL))
            );
            Vector3f s1 = new Vector3f(newTangent).cross(r1);

            normal = r1;
            bitangent = s1;
            tangent = newTangent;
            position = newPosition;

            return normal;
        }
    }

    /**
     * Holds information for each point in a trail: position, velocity and remaining lifetime. Points
     * can be added or subtracted, and interpolated using Catmull-Rom spline interpolation.
     */
    public static class Point {
        public Vector3f position;
        public Vector3f velocity;
        public Vector3f tangent;
        public Vector3f normal;
        public Vector4f color;
        public float thickness;
        public float life;
        public float texcoord;
        public boolean discontinuous;

        public Point(Vector3f position, Vector3f velocity, Vector3f tangent, Vector3f normal, Vector4f color, float thickness, float texcoord, float lifetime) {
            this.position = position;
            this.velocity = velocity;
            this.tangent = tangent;
            this.normal = normal;
            this.color = color;
            this.thickness = thickness;
            this.life = lifetime;
            this.texcoord = texcoord;
            this.discontinuous = false;
        }

        public static float catmullRom(float p0, float p1, float p2, float p3, float t) {
            float t2 = t * t;
            return 0.5f * ((2 * p1) +
                    (-p0 + p2) * t +
                    (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
                    (-p0 + 3 * p1 - 3 * p2 + p3) * t2 * t);
        }

        public static Point add(Point p1, Point p2) {
            return new Point(
                    new Vector3f(p1.position).add(p2.position),
                    new Vector3f(p1.velocity).add(p2.velocity),
                    new Vector3f(p1.tangent).add(p2.tangent),
                    new Vector3f(p1.normal).add(p2.normal),
                    new Vector4f(p1.color).add(p2.color),
                    p1.thickness + p2.thickness,
                    p1.texcoord + p2.texcoord,
                    p1.life + p2.life
            );
        }

        public static Point subtract(Point p1, Point p2) {
            return new Point(
                    new Vector3f(p1.position).sub(p2.position),
                    new Vector3f(p1.velocity).sub(p2.velocity),
                    new Vector3f(p1.tangent).sub(p2.tangent),
                    new Vector3f(p1.normal).sub(p2.normal),
                    new Vector4f(p1.color).sub(p2.color),
                    p1.thickness - p2.thickness,
                    p1.texcoord - p2.texcoord,
                    p1.life - p2.life
            );
        }

        public Point copy() {
            return new Point(
                    new Vector3f(position),
                    new Vector3f(velocity),
                    new Vector3f(tangent),
                    new Vector3f(normal),
                    new Vector4f(color),
                    thickness,
                    texcoord,
                    life
            );
        }

    }
}
