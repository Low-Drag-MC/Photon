package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.ForceOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRuntime;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.*;
import org.lwjgl.system.MemoryStack;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.lang.Math;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tile particle is the common particle that can be rendered in the game.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class TileParticle implements IParticle {
    public static final Direction[] MODEL_SIDES = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN, null};
    private static final double MAXIMUM_COLLISION_VELOCITY_SQUARED = Mth.square(100.0);
    /**
     * Basic data
     */
    protected float localX, localY, localZ; // position in simulation space (see IParticleEmitter#getSimToWorld)
    protected float localXo, localYo, localZo;
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
    protected int delay;
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
            setLocalPos(pos, true);
            var vel = worldToSim.transformDirection(emitterToWorld.transformDirection(new Vector3f(velocityX, velocityY, velocityZ)));
            setInternalVelocity(vel);
        }
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

    public void setLocalPos(float x, float y, float z, boolean setOrigin) {
        this.localX = x;
        this.localY = y;
        this.localZ = z;
        if (setOrigin) {
            this.localXo = x;
            this.localYo = y;
            this.localZo = z;
        }
    }

    public void setLocalPos(Vector3f realPos, boolean origin) {
        setLocalPos(realPos.x, realPos.y, realPos.z, origin);
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

    public Vector3f getLocalPos() {
        return getLocalPos(0);
    }

    public Vector3f getLocalPos(float partialTicks) {
        var pos = getLocalPoseWithoutNoise(partialTicks);

        if (runtime.noise.isEnable()) {
            pos.add(runtime.noise.getPosition(this, partialTicks));
        }

        return pos;
    }

    public Vector3f getLocalPoseWithoutNoise() {
        return getLocalPoseWithoutNoise(0);
    }

    public Vector3f getLocalPoseWithoutNoise(float partialTicks) {
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
        var localPosition = getLocalPos(partialTicks);
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
            delay--;
            return;
        }

        if (this.age == 0 && runtime.subEmitters.isEnable()) {
            runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Birth);
        }

        // update life cycle
        if (this.age >= this.lifetime && lifetime > 0) {
            this.age += dt;
            setRemoved(true);
            if (runtime.subEmitters.isEnable()) {
                runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Death);
            }
            return;
        }
        this.age += dt;

        // update data
        update(dt);

        if (runtime.subEmitters.isEnable()) {
            runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Tick);
        }

        if (lifetime > 0) {
            t = age / lifetime;
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
        this.updatePositionAndInternalVelocity(dt);
        this.updateColor();
        this.updateSize();
        this.updateRotation();
        this.updateLight();
    }

    protected void updatePositionAndInternalVelocity(float dt) {
        var velocity = getRealVelocity();           // per-tick velocity (rate)
        var desiredX = velocity.x * dt;             // displacement this step
        var desiredY = velocity.y * dt;
        var desiredZ = velocity.z * dt;
        var moveX = desiredX;
        var moveY = desiredY;
        var moveZ = desiredZ;

        var level = emitter.getLevel();
        if (runtime.physics.isEnable() && runtime.physics.hasCollision() && level != null &&
                (moveX != 0.0 || moveY != 0.0 || moveZ != 0.0) && moveX * moveX + moveY * moveY + moveZ * moveZ < MAXIMUM_COLLISION_VELOCITY_SQUARED) {
            var vec3 = Entity.collideBoundingBox(null, new Vec3(moveX, moveY, moveZ), getRealBoundingBox(0), level, List.of());
            moveX = (float) vec3.x;
            moveY = (float) vec3.y;
            moveZ = (float) vec3.z;
        }

        // update bounding box and position
        if (moveX != 0.0 || moveY != 0.0 || moveZ != 0.0) {
            var moveLocal = getSpaceTransformInverse().transformDirection(new Vector3f(moveX, moveY, moveZ));
            setLocalPos(localX + moveLocal.x, localY + moveLocal.y, localZ + moveLocal.z, false);
        }

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

        // update internal velocity
        if (!runtime.physics.isEnable()) return;
        // detect collision by comparing the desired displacement vs the collided one (dt-independent)
        if (runtime.physics.hasCollision() && !this.collided) {
            var bounceChance = runtime.physics.getBounceChance(this);
            var bounceRate = runtime.physics.getBounceRate(this);
            var bounceSpreadRate = runtime.physics.getBounceSpreadRate(this);
            if (Math.abs(desiredX) / Math.abs(moveX) > 1.001) {
                updateCollisionBounce(bounceChance, velocity, bounceRate, bounceSpreadRate, Direction.Axis.X);
            } else if (Math.abs(desiredY) / Math.abs(moveY) > 1.001) {
                updateCollisionBounce(bounceChance, velocity, bounceRate, bounceSpreadRate, Direction.Axis.Y);
            } else if (Math.abs(desiredZ) / Math.abs(moveZ) > 1.001) {
                updateCollisionBounce(bounceChance, velocity, bounceRate, bounceSpreadRate, Direction.Axis.Z);
            }
        }

        var gravity = runtime.physics.getGravity(this);
        if (gravity != 0) {
            this.addInternalVelocity(getSpaceTransformInverse().transformDirection(new Vector3f(0, -gravity * 0.04f * dt, 0)));
        }

        var friction = (float) Math.pow(runtime.physics.getFriction(this), dt);
        this.velocityX *= friction;
        this.velocityY *= friction;
        this.velocityZ *= friction;

        if (this.collided) {
            var collidedFriction = (float) Math.pow(runtime.physics.getCollidedFriction(this), dt);
            this.velocityX *= collidedFriction;
            this.velocityY *= collidedFriction;
            this.velocityZ *= collidedFriction;
        }
    }

    private void updateCollisionBounce(float bounceChance, Vector3f velocity, float bounceRate, float bounceSpreadRate, Direction.Axis axis) {
        if (bounceChance < 1 && bounceChance < randomSource.nextFloat()) {
            this.collided = true;
        } else {
            var newVelocity = getSpaceTransformInverse().transformDirection(new Vector3f(
                    axis == Direction.Axis.X ? -velocity.x * bounceRate :
                            (velocity.x + (bounceSpreadRate > 0 ?
                                    (float) (bounceSpreadRate * randomSource.nextGaussian()) : 0)),
                    axis == Direction.Axis.Y ? -velocity.y * bounceRate :
                            (velocity.y + (bounceSpreadRate > 0 ?
                                    (float) (bounceSpreadRate * randomSource.nextGaussian()) : 0)),
                    axis == Direction.Axis.Z ? -velocity.z * bounceRate :
                            (velocity.z + (bounceSpreadRate > 0 ?
                                    (float) (bounceSpreadRate * randomSource.nextGaussian()) : 0))
            ));
            setInternalVelocity(newVelocity);
        }
        if (runtime.physics.isEnable() && runtime.physics.isRemovedWhenCollided()) {
            this.setRemoved(true);
            if (runtime.subEmitters.isEnable()) {
                runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Death);
            }
        }
        if (runtime.subEmitters.isEnable()) {
            runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Collision);
            if (!isFirstCollision) {
                isFirstCollision = true;
                runtime.subEmitters.triggerEvent(this, SubEmittersSetting.Event.FirstCollision);
            }
        }
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
     *     <li>{@code + forceOverLifetime} (Local: rotated by the live emitter matrix, World: as-is)</li>
     *     <li>{@code + inheritVelocity} (CURRENT mode)</li>
     *     <li>{@code * velocityOverLifetime speed modifier}</li>
     * </ol>
     * Must stay side-effect-free and cheap; it is called several times per tick/frame.
     */
    public Vector3f getRealVelocity() {
        var velocity = getSpaceTransform().transformDirection(getInternalVelocity());
        if (runtime.forceOverLifetime.isEnable()) {
            var force = runtime.forceOverLifetime.getForce(this);
            if (runtime.forceOverLifetime.getSimulationSpace() == ForceOverLifetimeSetting.ForceSpace.Local) {
                emitter.transform().localToWorldMatrix().transformDirection(force);
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

            if (runtime.rotationOverLifetime.isEnable()) {
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
        light = getLightColor();
    }

    public void render(@Nonnull VertexConsumer pBuffer, Camera pRenderInfo, float pPartialTicks) {
        if (delay <= 0) {
            renderInternal(pBuffer, pRenderInfo, pPartialTicks);
        }
    }

    public void renderInternal(@Nonnull VertexConsumer buffer, Camera camera, float partialTicks) {
        var vec3 = camera.getPosition();

        var localPos = getLocalPos(partialTicks).mulPosition(getSpaceTransform());
        var x = (float) (localPos.x - vec3.x);
        var y = (float) (localPos.y - vec3.y);
        var z = (float) (localPos.z - vec3.z);

        var color = getRealColor(partialTicks);
        var r = color.x();
        var g = color.y();
        var b = color.z();
        var a = color.w();

        var light = getRealLight(partialTicks);

        var rotation = getRealRotation(partialTicks);
        var renderMode = config.renderer.getRenderMode();

        var size = getRealSize(partialTicks);

        if (renderMode == ParticleRendererSetting.Mode.Model) {
            var transform = new Matrix4f().translate(x, y ,z)
                    .rotate(new Quaternionf().rotateXYZ(rotation.x, rotation.y, rotation.z).mul(getSpaceRotation()))
                    .scale(size.mul(getSpaceScale()))
                    .translate(-0.5f, -0.5f, -0.5f);
            // draw 3d model
            var model = config.renderer.getModel();
            for (var side : MODEL_SIDES) {
                var brightness = (side != null && config.renderer.isShade()) ? switch (side) {
                    case DOWN, UP:
                        yield 0.9F;
                    case NORTH:
                    case SOUTH:
                        yield 0.8F;
                    case WEST:
                    case EAST:
                        yield 0.6F;
                } : 1f;
                var quads = model.renderModel(null, null, null, side, randomSource, ModelData.EMPTY, null);
                for (var quad : quads) {
                    putBulkData(transform, buffer, quad, brightness, r, g, b, a, light);
                }
            }
        } else {
            Quaternionf quaternion;
            float finalSizeX = size.x;
            float finalSizeY = size.y;
            float finalSizeZ = size.z;
            var spaceScale = getSpaceScale();

            if (renderMode == ParticleRendererSetting.Mode.StretchedBillboard) {
                Vector3f vel = getRealVelocity();
                float speed = vel.length();

                Vector3f right = new Vector3f();
                if (speed > 1e-5f) {
                    right.set(vel).div(speed);
                } else {
                    right.set(1, 0, 0);
                }

                Vector3f dirToCam = new Vector3f((float)(vec3.x - localPos.x), (float)(vec3.y - localPos.y), (float)(vec3.z - localPos.z));
                if (dirToCam.lengthSquared() > 1e-5f) {
                    dirToCam.normalize();
                } else {
                    dirToCam.set(0, 0, 1);
                }

                Vector3f up = new Vector3f();
                dirToCam.cross(right, up);

                if (up.lengthSquared() < 1e-5f) {
                    if (Math.abs(right.y) > 0.99f) {
                        up.set(0, 0, 1).cross(right).normalize();
                    } else {
                        up.set(0, 1, 0).cross(right).normalize();
                    }
                } else {
                    up.normalize();
                }

                Vector3f forward = new Vector3f();
                right.cross(up, forward).normalize();

                Matrix3f mat = new Matrix3f(
                        right.x,   right.y,   right.z,
                        up.x,      up.y,      up.z,
                        forward.x, forward.y, forward.z
                );
                quaternion = new Quaternionf().setFromNormalized(mat);

                float stretch = config.renderer.getLengthScale() + speed * config.renderer.getVelocityScale();
                finalSizeX *= stretch;

                float offsetAmount = (finalSizeX - size.x) * spaceScale.x;
                x -= right.x * offsetAmount;
                y -= right.y * offsetAmount;
                z -= right.z * offsetAmount;

            } else {
                quaternion = renderMode.quaternion.apply(this, camera, partialTicks);
                if (!Vector3fHelper.isZero(rotation)) {
                    quaternion = new Quaternionf(quaternion).rotateXYZ(rotation.x, rotation.y, rotation.z);
                }
            }

            var rawVertexes = new Vector3f[]{
                    new Vector3f(1.0F, -1.0F, 0.0F),
                    new Vector3f(1.0F, 1.0F, 0.0F),
                    new Vector3f(-1.0F, 1.0F, 0.0F),
                    new Vector3f(-1.0F, -1.0F, 0.0F),
            };
            var normal = new Vector3f(0, 0, 1);

            for (var i = 0; i < 4; ++i) {
                var vertex = rawVertexes[i];
                vertex.mul(finalSizeX, finalSizeY, finalSizeZ);
                vertex = quaternion.transform(vertex);
                vertex.mul(spaceScale);
                vertex.add(x, y, z);
            }

            normal = quaternion.transform(normal);

            var uvs = getRealUVs(partialTicks);
            var u0 = uvs.x();
            var v0 = uvs.y();
            var u1 = uvs.z();
            var v1 = uvs.w();

            buffer.addVertex(rawVertexes[0].x(), rawVertexes[0].y(), rawVertexes[0].z()).setUv(u1, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
            buffer.addVertex(rawVertexes[1].x(), rawVertexes[1].y(), rawVertexes[1].z()).setUv(u1, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
            buffer.addVertex(rawVertexes[2].x(), rawVertexes[2].y(), rawVertexes[2].z()).setUv(u0, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
            buffer.addVertex(rawVertexes[3].x(), rawVertexes[3].y(), rawVertexes[3].z()).setUv(u0, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        }
    }

    public void putBulkData(Matrix4f transform, VertexConsumer buffer, BakedQuad quad, float brightness, float red, float green, float blue, float alpha, int light) {
        int[] vertices = quad.getVertices();
        int points = vertices.length / 8;

        try (MemoryStack memoryStack = MemoryStack.stackPush()) {
            var byteBuffer = memoryStack.malloc(DefaultVertexFormat.BLOCK.getVertexSize());
            var intBuffer = byteBuffer.asIntBuffer();

            var u0 = quad.getSprite().getU0();
            var v0 = quad.getSprite().getV0();
            var u1 = quad.getSprite().getU1();
            var v1 = quad.getSprite().getV1();
            var uw = u1 - u0;
            var vh = v1 - v0;
            var pivotPoint = config.renderer.getModelPivot();

            for (int k = 0; k < points; ++k) {
                intBuffer.clear();
                intBuffer.put(vertices, k * 8, 8);
                var x = byteBuffer.getFloat(0) + pivotPoint.x; // 0
                var y = byteBuffer.getFloat(4) + pivotPoint.y; // 1
                var z = byteBuffer.getFloat(8) + pivotPoint.z; // 2
                var u = byteBuffer.getFloat(16); // 4 u
                var v = byteBuffer.getFloat(20); // 5 v
                var normalData = byteBuffer.getInt(IQuadTransformer.NORMAL * 4);
                float nX = ((byte) normalData      ) / 127.0f;
                float nY = ((byte)(normalData>>8 )) / 127.0f;
                float nZ = ((byte)(normalData>>16)) / 127.0f;
                if (!config.renderer.isUseBlockUV()) {
                    u =  (u - u0) / uw;
                    v =  (v - v0) / vh;
                }

                var pos = transform.transform(new Vector4f(x, y, z, 1.0F));
                var normalMat = transform.normal(new Matrix3f());
                var normal = new Vector3f(nX, nY, nZ).mul(normalMat).normalize();

                buffer.addVertex(pos.x, pos.y, pos.z);
                buffer.setColor(red * brightness, green * brightness, blue * brightness, alpha);
                buffer.setUv(u, v);
                buffer.setLight(light);
                buffer.setNormal(normal.x, normal.y, normal.z);
            }
        }

    }

}
