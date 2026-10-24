package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.collision.ContactMemory;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomSpace;
import com.lowdragmc.photon.client.gameobject.emitter.data.ValueSpace;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRuntime;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import org.joml.*;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.lang.Math;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tile particle is the common particle that can be rendered in the game.
 */
@ParametersAreNonnullByDefault
public class TileParticle implements IParticle {
    public static final Direction[] MODEL_SIDES = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN, null};
    /**
     * Basic data
     */
    protected float localX, localY, localZ; // position in simulation space (see IParticleEmitter#getSimToWorld)
    protected float localXo, localYo, localZo;
    /** The frame this particle was born into; {@code null} == identity. @see SpawnFrame */
    @Nullable
    protected SpawnFrame spawnFrame;
    protected float rotationX = 180, rotationY = 180, rotationZ = 180; // rotation
    protected float rotationXo = 180, rotationYo = 180, rotationZo = 180;
    protected float sizeX = 1, sizeY = 1, sizeZ = 1; // size
    protected float sizeXo = 1, sizeYo = 1, sizeZo = 1;
    protected float r = 1, g = 1, b = 1, a = 1; // color
    protected float ro = 1, go = 1, bo = 1, ao = 1;
    protected float velocityX, velocityY, velocityZ; // velocity in simulation space
    protected int light = -1;
    protected AABB boundingBox = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
    /**
     * Physics
     */
    @Setter @Getter
    protected boolean collided;
    /**
     * Life cycle
     */
    @Setter @Getter
    protected float delay; // fractional ticks: consumed by dt per step, not one whole tick per substep
    @Setter @Getter
    protected float age;
    @Setter @Getter
    protected int lifetime;
    @Setter @Getter
    protected boolean isRemoved;

    // runtime
    @Getter
    protected float t;
    protected Vector3f initialSize;
    protected Vector3f initialRotation;
    protected Vector4f initialColor;
    protected boolean isFirstCollision;
    /** Contact state carried between collision steps. */
    protected int contactAxes;
    protected long contactTarget = -1;
    /** The triggers this particle is inside, by contact identity. */
    @Nullable
    protected long[] triggers;
    protected int triggerCount;
    /**
     * What it is stuck to, as a contact kind (-1: nothing), and where its collision box came to rest: in the world
     * on a block or fluid, from the centre of an entity, in the frame of a collider. For blocks, the surface normal
     * and the box's half extents, to probe for the surface when blocks change.
     */
    protected byte stuckKind = -1;
    protected long stuckKey;
    protected int stuckVersion;
    protected float stuckX, stuckY, stuckZ, stuckNx, stuckNy, stuckNz, stuckHx, stuckHy, stuckHz;
    protected int bounces, impacts;
    /** Targets this particle already reported, for per-particle de-duplication; game thread. */
    @Nullable
    private ContactMemory reportedTargets;
    /** Emission order within the emitter; orders deferred spawns deterministically. */
    protected int serial;
    private int spawnSequence;
    @Getter
    protected ParticleConfig config;
    /** The owning emitter's per-instance runtime layer (timeline overrides + moved value behaviour). */
    @Getter
    protected ParticleRuntime runtime;
    @Getter
    protected IParticleEmitter emitter;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    @Getter
    public RandomSource randomSource;
    @Getter
    protected int particleBatchIndex;
    @Getter
    protected int particleBatchCount = 1;

    public TileParticle(IParticleEmitter emitter, ParticleConfig config) {
        this.emitter = emitter;
        this.config = config;
        this.runtime = emitter instanceof ParticleEmitter pe ? pe.runtime() : new ParticleRuntime(config);
        this.randomSource = RandomSource.create(emitter.getRandomSource().nextLong());
        setup();
    }

    public void setup() {
        if (emitter instanceof ParticleEmitter particleEmitter) {
            particleBatchIndex = particleEmitter.nextParticleBatchIndex();
            particleBatchCount = particleEmitter.getParticleBatchCount();
            serial = particleEmitter.nextParticleSerial();
        } else {
            particleBatchIndex = 0;
            particleBatchCount = 1;
        }
        var emitterT = emitter.getT();
        // start values come from the runtime layer (timeline override if set, else authored config)
        setDelay(runtime.startDelay.get().get(randomSource, emitterT).intValue());
        if (runtime.lifetimeByEmitterSpeed.isEnable()) {
            setLifetime(runtime.lifetimeByEmitterSpeed.getLifetime(this, emitter,
                    runtime.startLifetime.get().get(randomSource, emitterT).intValue()));
        } else {
            setLifetime(runtime.startLifetime.get().get(randomSource, emitterT).intValue());
        }

        runtime.shape.setupParticle(this, emitter);
        // the shape wrote emitter-local position/velocity; for non-Local simulation spaces,
        // re-express them in simulation space using the emitter's spawn-time pose
        if (config.getSimulationSpace() != ParticleConfig.Space.Local) {
            var emitterToWorld = emitter.transform().localToWorldMatrix();
            var worldToSim = emitter.getWorldToSim();
            var pos = new Vector3f(localX, localY, localZ).mulPosition(emitterToWorld).mulPosition(worldToSim);
            setSimPos(pos, true);
            var vel = worldToSim.transformDirection(emitterToWorld.transformDirection(new Vector3f(velocityX, velocityY, velocityZ)));
            setInternalVelocity(vel);
            // the frame we are born into, shared with every particle emitted at this emitter pose
            this.spawnFrame = emitter instanceof ParticleEmitter particleEmitter
                    ? particleEmitter.currentSpawnFrame()
                    : new SpawnFrame(emitterToWorld, emitter.transform().worldToLocalMatrix(),
                            worldToSim, emitter.getSimToWorld());
        }
        runtime.shape.snapToGround(this, emitter);
        // no ground to stand on, and it should not live without: the emitter drops it
        if (isRemoved) return;
        if (runtime.inheritVelocity.isEnable() && runtime.inheritVelocity.getMode() == InheritVelocitySetting.Mode.INITIAL) {
            addInternalVelocity(getSpaceTransformInverse().transformDirection(runtime.inheritVelocity.getVelocity(emitter)));
        }
        mulInternalVelocity(runtime.startSpeed.get().get(randomSource, emitterT).floatValue());
        this.initialSize = runtime.startSize.get().get(randomSource, emitterT);
        // non-Local particles are not rendered through the emitter matrix, so bake the emitter's
        // spawn-time scale (relative to the simulation space) into the size instead
        if (config.getSimulationSpace() != ParticleConfig.Space.Local) {
            this.initialSize.mul(emitter.transform().scale().div(emitter.getSimSpaceScale()));
        }
        this.initialRotation = runtime.startRotation.get().get(randomSource, emitterT).mul(Mth.TWO_PI / 360);
        var color = runtime.startColor.get().get(randomSource, emitterT).intValue();
        this.initialColor = new Vector4f(ColorUtils.red(color), ColorUtils.green(color), ColorUtils.blue(color), ColorUtils.alpha(color));
        setSize(initialSize);
        setRotation(initialRotation);
        setColor(initialColor);
        update(1f);
        // update() skips the pure visual recomputes during a timeline seek, on the promise that the
        // seek's last two full ticks redo them. A particle with a START DELAY never reaches them:
        // updateTick returns early for the whole delay, so the frame it first becomes visible on --
        // the one right after the delay hits 0 -- still carries the raw birth values, with every
        // over-lifetime curve unapplied. Redo them here, where it is correct for every path.
        if (PhotonParticleManager.isFastSimulation()) {
            updateColor();
            updateSize();
            updateRotation();
            updateLight();
        }
        updateOrigin();

        if (runtime.trails.isEnable() && emitter instanceof ParticleEmitter particleEmitter) {
            runtime.trails.setup(particleEmitter, this);
        }
    }

    @Override
    public PhotonFXRenderPass getRenderType() {
        return config.particleRenderType;
    }

    @Override
    public float getT(float partialTicks) {
        if (getLifetime() <= 0) {
            return Mth.clamp(t, 0, 1); // infinite particle: hold current t instead of dividing by zero
        }
        return Mth.clamp(t + partialTicks / getLifetime(), 0, 1);
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

    public void setSimPos(float x, float y, float z, boolean setOrigin) {
        this.localX = x;
        this.localY = y;
        this.localZ = z;
        if (setOrigin) {
            this.localXo = x;
            this.localYo = y;
            this.localZo = z;
        }
    }

    public void setSimPos(Vector3f realPos, boolean origin) {
        setSimPos(realPos.x, realPos.y, realPos.z, origin);
    }

    public void setInternalVelocity(Vector3f vec) {
        this.velocityX = vec.x;
        this.velocityY = vec.y;
        this.velocityZ = vec.z;
    }

    public void mulInternalVelocity(float mul) {
        this.velocityX *= mul;
        this.velocityY *= mul;
        this.velocityZ *= mul;
    }

    public void addInternalVelocity(Vector3f vec) {
        this.velocityX += vec.x;
        this.velocityY += vec.y;
        this.velocityZ += vec.z;
    }

    public void setRotation(Vector3f rotation) {
        this.rotationX = rotation.x;
        this.rotationY = rotation.y;
        this.rotationZ = rotation.z;
    }

    public void setSize(Vector3f size) {
        this.sizeX = size.x;
        this.sizeY = size.y;
        this.sizeZ = size.z;
        boundingBox = new AABB(-sizeX / 2, -sizeY / 2, -sizeZ / 2, sizeX / 2, sizeY / 2, sizeZ / 2);
    }

    public void mulSize(float size) {
        this.sizeX *= size;
        this.sizeY *= size;
        this.sizeZ *= size;
        boundingBox = new AABB(-sizeX / 2, -sizeY / 2, -sizeZ / 2, sizeX / 2, sizeY / 2, sizeZ / 2);
    }

    public void setColor(Vector4f color) {
        this.r = color.x();
        this.g = color.y();
        this.b = color.z();
        this.a = color.w();
    }

    public void setARGBColor(int color) {
        this.a = ColorUtils.alpha(color);
        this.r = ColorUtils.red(color);
        this.g = ColorUtils.green(color);
        this.b = ColorUtils.blue(color);
    }

    public Vector3f getRealRotation(float partialTicks) {
        var rotation = new Vector3f(
                Mth.lerp(partialTicks, rotationXo, rotationX),
                Mth.lerp(partialTicks, rotationYo, rotationY),
                Mth.lerp(partialTicks, rotationZo, rotationZ));
        return rotation;
    }

    public Vector3f getRealSize(float partialTicks) {
        return new Vector3f(
                Mth.lerp(partialTicks, sizeXo, sizeX),
                Mth.lerp(partialTicks, sizeYo, sizeY),
                Mth.lerp(partialTicks, sizeZo, sizeZ));
    }

    public Vector3f getSimPos() {
        return getSimPos(0);
    }

    /**
     * This particle's offset from the emitter that spawned it, still in simulation-space axes — what
     * "emitter-relative" means for every simulation space alike. Identical to {@link #getSimPos()} in
     * Local space. @see SpawnFrame
     */
    public Vector3f getEmitterRelativePos() {
        return getEmitterRelativePos(0);
    }

    /** @see #getEmitterRelativePos() */
    public Vector3f getEmitterRelativePos(float partialTicks) {
        var pos = getSimPos(partialTicks);
        return spawnFrame == null ? pos
                : pos.sub(spawnFrame.originX(), spawnFrame.originY(), spawnFrame.originZ());
    }

    /** Rotate a direction from the spawn frame's axes into simulation space (in place). */
    public Vector3f emitterDirToSim(Vector3f direction) {
        return spawnFrame == null ? direction : spawnFrame.emitterDirToSim(direction);
    }

    /** Rotate a direction from simulation space into the spawn frame's axes (in place). */
    public Vector3f simDirToEmitter(Vector3f direction) {
        return spawnFrame == null ? direction : spawnFrame.simDirToEmitter(direction);
    }

    /** Rotate a direction from world axes into simulation space (in place). Live, not frozen — world
     *  axes are absolute, so only the CURRENT simulation space matters. Skipped entirely in World
     *  simulation space, where the two are the same axes and the matrix is the identity. */
    public Vector3f worldDirToSim(Vector3f direction) {
        return isWorldSimulationSpace() ? direction : getSpaceTransformInverse().transformDirection(direction);
    }

    /** Rotate a direction from simulation space into world axes (in place). @see #worldDirToSim */
    public Vector3f simDirToWorld(Vector3f direction) {
        return isWorldSimulationSpace() ? direction : getSpaceTransform().transformDirection(direction);
    }

    private boolean isWorldSimulationSpace() {
        return config.getSimulationSpace() == ParticleConfig.Space.World;
    }

    public Vector3f getSimPos(float partialTicks) {
        var pos = getSimPosWithoutNoise(partialTicks);

        if (runtime.noise.isEnable()) {
            pos.add(runtime.noise.getPosition(this, partialTicks));
        }

        return pos;
    }

    public Vector3f getSimPosWithoutNoise() {
        return getSimPosWithoutNoise(0);
    }

    public Vector3f getSimPosWithoutNoise(float partialTicks) {
        if (isRemoved) {
            return new Vector3f(localX, localY, localZ);
        }
        return new Vector3f(Mth.lerp(partialTicks, this.localXo, this.localX),
                Mth.lerp(partialTicks, this.localYo, this.localY),
                Mth.lerp(partialTicks, this.localZo, this.localZ));
    }

    public Vector3f getWorldPos() {
        return getWorldPos(0);
    }

    /**
     * from simulation space to world space (read-only matrix)
     */
    public Matrix4f getSpaceTransform() {
        return emitter.getSimToWorld();
    }

    /**
     * from world space to simulation space (read-only matrix)
     */
    public Matrix4f getSpaceTransformInverse() {
        return emitter.getWorldToSim();
    }

    public Vector3f getSpaceScale() {
        return emitter.getSimSpaceScale();
    }

    public Quaternionf getSpaceRotation() {
        return emitter.getSimSpaceRotation();
    }

    public Vector3f getWorldPos(float partialTicks) {
        var localPosition = getSimPos(partialTicks);
        return new Vector3f(localPosition).mulPosition(getSpaceTransform());
    }

    public Vector3f getWorldUp(float partialTicks) {
        return getSpaceRotation().transform(new Vector3f(0, 1, 0));
    }

    public Vector3f getWorldForward(float partialTicks) {
        return getSpaceRotation().transform(new Vector3f(0, 0, -1));
    }

    public Vector3f getWorldRight(float partialTicks) {
        return getSpaceRotation().transform(new Vector3f(1, 0, 0));
    }

    public AABB getRealBoundingBox(float partialTicks) {
        var pos = getWorldPos(partialTicks);
        return boundingBox.move(pos.x, pos.y, pos.z);
    }

    public Vector4f getRealColor(float partialTicks) {
        var emitterColor = emitter.getRGBAColor();
        var a = Mth.lerp(partialTicks, this.ao, this.a);
        var r = Mth.lerp(partialTicks, this.ro, this.r);
        var g = Mth.lerp(partialTicks, this.go, this.g);
        var b = Mth.lerp(partialTicks, this.bo, this.b);
        return emitterColor.mul(r, g, b, a);
    }

    public Vector4f getRealUVs(float partialTicks) {
        if (runtime.uvAnimation.isEnable()) {
            return runtime.uvAnimation.getUVs(this, partialTicks);
        } else {
            return new Vector4f(0, 0, 1, 1);
        }
    }

    public int getRealLight(float partialTicks) {
        if (runtime.lights.isEnable()) {
            return runtime.lights.getLight(this, partialTicks);
        }
        return light;
    }

    public int getLightColor() {
        var pos = getWorldPos();
        var blockPos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        return emitter.getLightColor(blockPos);
    }

    /**
     * should always be called per tick
     */
    public void updateTick(float dt) {
        if (delay > 0) {
            delay -= dt;
            return;
        }

        if (this.age == 0 && runtime.subEmitters.isEnable()) {
            runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Birth);
        }

        // death: snap t to 1 and apply the final visual state (no motion) so over-lifetime curves
        // reach their endpoints on the frames the dead particle still renders before removal.
        // lifetime <= 0 intentionally never age-dies (infinite particles; they die via collision
        // or emitter removal).
        if (this.age >= this.lifetime && lifetime > 0) {
            this.age += dt;
            this.t = 1;
            this.updateColor();
            this.updateSize();
            this.updateRotation();
            this.updateLight();
            setRemoved(true);
            if (runtime.subEmitters.isEnable()) {
                runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Death);
            }
            return;
        }
        this.age += dt;
        if (lifetime > 0) {
            // recompute BEFORE update so curves sample this step's t (was one tick late), and clamp:
            // fractional dt can push age past lifetime here, which previously produced t > 1
            t = Math.min(age / lifetime, 1f);
        }

        // update data
        update(dt);

        if (runtime.subEmitters.isEnable()) {
            runtime.subEmitters.triggerTickEvent(this, dt);
        }
    }

    @Override
    public void syncOrigin() {
        updateOrigin();
    }

    protected void updateOrigin() {
        this.localXo = this.localX;
        this.localYo = this.localY;
        this.localZo = this.localZ;
        this.rotationXo = this.rotationX;
        this.rotationYo = this.rotationY;
        this.rotationZo = this.rotationZ;
        this.sizeXo = this.sizeX;
        this.sizeYo = this.sizeY;
        this.sizeZo = this.sizeZ;
        this.ro = this.r;
        this.go = this.g;
        this.bo = this.b;
        this.ao = this.a;
    }

    protected void update(float dt) {
        updateChanges(dt);
    }

    protected void updateChanges(float dt) {
        this.updatePositionAndInternalVelocity(dt); // NEVER skipped: position/velocity accumulate
        if (PhotonParticleManager.isFastSimulation()) {
            // seek replay of a tick that will never be rendered: color/rotation/light are pure
            // per-tick recomputes from initial values + curves (the final full ticks restore them
            // exactly). Size is NOT pure downstream when (a) collision reads the size that setSize
            // updates, or (b) trails bake getRealSize into persisted tail lifetimes.
            var sizeAffectsSimulation = (runtime.physics.isEnable() && runtime.physics.hasCollision())
                    || runtime.trails.isEnable();
            if (sizeAffectsSimulation) {
                this.updateSize();
            }
            return;
        }
        this.updateColor();
        this.updateSize();
        this.updateRotation();
        this.updateLight();
    }

    protected void updatePositionAndInternalVelocity(float dt) {
        if (runtime.physics.isEnable() && runtime.physics.hasCollision()
                && emitter instanceof ParticleEmitter particleEmitter) {
            var scene = particleEmitter.collisionScene();
            if (scene != null) {
                ParticlePhysics.step(this, particleEmitter, scene, dt);
                return;
            }
        }
        var velocity = getRealVelocity();           // per-tick velocity (rate)
        var moveX = velocity.x * dt;                // displacement this step
        var moveY = velocity.y * dt;
        var moveZ = velocity.z * dt;
        if (moveX != 0.0 || moveY != 0.0 || moveZ != 0.0) {
            var moveLocal = getSpaceTransformInverse().transformDirection(new Vector3f(moveX, moveY, moveZ));
            setSimPos(localX + moveLocal.x, localY + moveLocal.y, localZ + moveLocal.z, false);
        }
        applyForces(dt, false, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Folds this step's forces into the stored velocity: force fields, gravity, friction, and contact friction
     * along a surface the step {@code touched} (normal {@code n}, moving at {@code s}).
     */
    void applyForces(float dt, boolean touched, double nx, double ny, double nz, double sx, double sy, double sz) {
        // external force fields: fold into the stored velocity once per tick (direction/gravity/vortex
        // integrate as force * dt, drag damps). Deliberately not part of getRealVelocity(), which must
        // stay side-effect-free and is called several times per tick/frame.
        if (runtime.externalForces.isEnable() && emitter instanceof ParticleEmitter particleEmitter) {
            var fields = particleEmitter.getActiveForceFields();
            if (!fields.isEmpty()) {
                var multiplier = runtime.externalForces.getMultiplier(this);
                if (multiplier != 0) {
                    var worldPos = getWorldPos();
                    var worldVelocity = getSpaceTransform().transformDirection(new Vector3f(velocityX, velocityY, velocityZ));
                    var size = Math.max(sizeX, Math.max(sizeY, sizeZ));
                    for (var field : fields) {
                        field.apply(worldPos, worldVelocity, size, dt, this, multiplier);
                    }
                    setInternalVelocity(getSpaceTransformInverse().transformDirection(worldVelocity));
                }
            }
        }

        if (!runtime.physics.isEnable()) return;
        var gravity = runtime.physics.getGravity(this);
        if (gravity != 0) {
            this.addInternalVelocity(getSpaceTransformInverse().transformDirection(new Vector3f(0, -gravity * 0.04f * dt, 0)));
        }

        var friction = (float) Math.pow(runtime.physics.getFriction(this), dt);
        this.velocityX *= friction;
        this.velocityY *= friction;
        this.velocityZ *= friction;

        if (touched) {
            var keep = (float) Math.pow(runtime.physics.getCollidedFriction(this), dt);
            if (keep != 1) {
                // only the slide along the surface slows, relative to the surface (which may be moving)
                var world = getSpaceTransform().transformDirection(new Vector3f(velocityX, velocityY, velocityZ));
                double rx = world.x - sx, ry = world.y - sy, rz = world.z - sz;
                double along = rx * nx + ry * ny + rz * nz;
                world.set((float) (sx + along * nx + (rx - along * nx) * keep),
                        (float) (sy + along * ny + (ry - along * ny) * keep),
                        (float) (sz + along * nz + (rz - along * nz) * keep));
                setInternalVelocity(getSpaceTransformInverse().transformDirection(world));
            }
        }
    }

    /** The current position plus this tick's noise offset, in world space: where a collision step starts. */
    Vector3f collisionCenter(Vector3f dest) {
        return collisionNoise(dest).add(localX, localY, localZ).mulPosition(getSpaceTransform());
    }

    /** This tick's noise offset, in simulation space. */
    Vector3f collisionNoise(Vector3f dest) {
        return runtime.noise.isEnable() ? dest.set(runtime.noise.getPosition(this, 0)) : dest.zero();
    }

    public ContactMemory reportedTargets() {
        if (reportedTargets == null) reportedTargets = new ContactMemory();
        return reportedTargets;
    }

    /** The stored velocity alone, in simulation space: without velocity over lifetime or forces. */
    public Vector3f getStoredVelocity() {
        return new Vector3f(velocityX, velocityY, velocityZ);
    }

    /** Ordering key for this particle's next deferred sub-emitter spawn (see {@link #serial}). */
    public long nextSpawnOrder() {
        return ((long) serial << 20) | (spawnSequence++ & 0xFFFFF);
    }

    /**
     * Velocity in simulation space: the stored velocity plus the (non-persistent, re-evaluated
     * per call) velocity-over-lifetime addition.
     */
    public Vector3f getInternalVelocity() {
        var velocity = new Vector3f(velocityX, velocityY, velocityZ);
        if (runtime.velocityOverLifetime.isEnable()) {
            var velocityAddition = runtime.velocityOverLifetime.getVelocityAddition(this);
            velocity.add(velocityAddition);
        }
        return velocity;
    }

    /**
     * The total world-space velocity. Composition order:
     * <ol>
     *     <li>{@code simToWorld * (stored velocity + velocityOverLifetime addition)}</li>
     *     <li>{@code + forceOverLifetime} (Local: the spawn frame's axes, World: as-is, Custom: the picked transform's axes)</li>
     *     <li>{@code + inheritVelocity} (CURRENT mode)</li>
     *     <li>{@code * velocityOverLifetime speed modifier}</li>
     * </ol>
     * Must stay side-effect-free and cheap; it is called several times per tick/frame.
     */
    public Vector3f getRealVelocity() {
        var velocity = getSpaceTransform().transformDirection(getInternalVelocity());
        if (runtime.forceOverLifetime.isEnable()) {
            var force = runtime.forceOverLifetime.getForce(this);
            var forceSpace = runtime.forceOverLifetime.getSimulationSpace();
            if (forceSpace == ValueSpace.Local) {
                // the SPAWN frame's axes, not the emitter's live matrix — same rule as
                // velocityOverLifetime, so the two "Local" dropdowns cannot mean different things.
                // Reading it live would swing the force on particles the emitter already left behind.
                simDirToWorld(emitterDirToSim(force));
            } else if (forceSpace == ValueSpace.Custom) {
                CustomSpace.dirToWorld(runtime.forceOverLifetime.getCustomSpace(), emitter.getScene(), force);
            }
            velocity.add(force);
        }
        if (runtime.inheritVelocity.isEnable() && runtime.inheritVelocity.getMode() == InheritVelocitySetting.Mode.CURRENT) {
            velocity.add(runtime.inheritVelocity.getVelocity(emitter));
        }
        if (runtime.velocityOverLifetime.isEnable()) {
            var velocityMultiplier = runtime.velocityOverLifetime.getVelocityMultiplier(this);
            velocity.mul(velocityMultiplier);
        }
        return velocity;
    }

    protected void updateSize() {
        if (runtime.sizeBySpeed.isEnable() || runtime.sizeOverLifetime.isEnable() || runtime.noise.isEnable()) {
            var size = new Vector3f(initialSize);
            var mul = new Vector3f(1, 1, 1);

            if (runtime.noise.isEnable()) {
                size.add(runtime.noise.getSize(this, 0));
            }

            if (runtime.sizeBySpeed.isEnable()) {
                mul.mul(runtime.sizeBySpeed.getSize(this));
            }
            if (runtime.sizeOverLifetime.isEnable()) {
                mul.mul(runtime.sizeOverLifetime.getSize(this, 0));
            }

            setSize(size.mul(mul));
        }
    }

    protected void updateRotation() {
        if (runtime.rotationOverLifetime.isEnable() || runtime.rotationBySpeed.isEnable() || runtime.noise.isEnable()) {
            var rotation = new Vector3f(initialRotation);

            if (runtime.rotationOverLifetime.isEnable() && runtime.rotationOverLifetime.isLocal()) {
                rotation.add(runtime.rotationOverLifetime.getRotation(this, 0));
            }

            if (runtime.rotationBySpeed.isEnable()) {
                rotation.add(runtime.rotationBySpeed.getRotation(this));
            }

            if (runtime.noise.isEnable()) {
                rotation.add(runtime.noise.getRotation(this, 0));
            }

            setRotation(rotation);
        }
    }

    protected void updateColor() {
        if (runtime.colorOverLifetime.isEnable() || runtime.colorBySpeed.isEnable()) {
            var color = new Vector4f(initialColor);

            if (runtime.colorOverLifetime.isEnable()) {
                color.mul(runtime.colorOverLifetime.getColor(this, 0));
            }

            if (runtime.colorBySpeed.isEnable()) {
                color.mul(runtime.colorBySpeed.getColor(this));
            }

            setColor(color);
        }
    }

    protected void updateLight() {
        if (runtime.lights.isEnable()) return;
        var pos = getWorldPos();
        var blockPos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        // fallback keeps the last light on a (parallel-phase) cache miss; corrected next tick
        light = emitter.getLightColor(blockPos, Math.max(light, 0));
    }

}
