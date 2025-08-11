package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.SubEmittersSetting;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
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
    protected float localX, localY, localZ; // local position
    protected float localXo, localYo, localZo;
    protected float rotationX = 180, rotationY = 180, rotationZ = 180; // rotation
    protected float rotationXo = 180, rotationYo = 180, rotationZo = 180;
    protected float sizeX = 1, sizeY = 1, sizeZ = 1; // size
    protected float sizeXo = 1, sizeYo = 1, sizeZo = 1;
    protected float r = 1, g = 1, b = 1, a = 1; // color
    protected float ro = 1, go = 1, bo = 1, ao = 1;
    protected float velocityX, velocityY, velocityZ; // velocity
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
    protected int age;
    @Setter @Getter
    protected int lifetime;
    @Setter @Getter
    protected boolean isRemoved;

    // runtime
    @Getter
    protected float t;
    protected Matrix4f initialTransform;
    protected Matrix4f initialTransformInverse;
    @Setter
    protected Vector3f initialScale;
    protected Vector3f initialSize;
    protected Vector3f initialRotation;
    protected Vector4f initialColor;
    protected boolean isFirstCollision;
    @Getter
    protected ParticleConfig config;
    @Getter
    protected IParticleEmitter emitter;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    @Getter
    public RandomSource randomSource;

    public TileParticle(IParticleEmitter emitter, ParticleConfig config) {
        this.emitter = emitter;
        this.config = config;
        this.randomSource = RandomSource.create(emitter.getRandomSource().nextLong());
        setup();
    }

    public void setup() {
        this.initialTransform = emitter.transform().localToWorldMatrix();
        this.initialTransformInverse = emitter.transform().worldToLocalMatrix();
        this.initialScale = emitter.transform().scale();
        var emitterT = emitter.getT();

        setDelay(config.getStartDelay().get(randomSource, emitterT).intValue());
        if (config.lifetimeByEmitterSpeed.isEnable()) {
            setLifetime(config.lifetimeByEmitterSpeed.getLifetime(this, emitter,
                    config.getStartLifetime().get(randomSource, emitterT).intValue()));
        } else {
            setLifetime(config.getStartLifetime().get(randomSource, emitterT).intValue());
        }

        config.shape.setupParticle(this, emitter);
        if (config.inheritVelocity.isEnable() && config.inheritVelocity.getMode() == InheritVelocitySetting.Mode.INITIAL) {
            addInternalVelocity(getSpaceTransformInverse().transformDirection(config.inheritVelocity.getVelocity(emitter)));
        }
        mulInternalVelocity(config.getStartSpeed().get(randomSource, emitterT).floatValue());
        this.initialSize = config.getStartSize().get(randomSource, emitterT);
        this.initialRotation = config.getStartRotation().get(randomSource, emitterT).mul(Mth.TWO_PI / 360);
        var color = config.getStartColor().get(randomSource, emitterT).intValue();
        this.initialColor = new Vector4f(ColorUtils.red(color), ColorUtils.green(color), ColorUtils.blue(color), ColorUtils.alpha(color));
        setSize(initialSize);
        setRotation(initialRotation);
        setColor(initialColor);
        update();
        updateOrigin();

        if (config.trails.isEnable() && emitter instanceof ParticleEmitter particleEmitter) {
            config.trails.setup(particleEmitter, this);
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

        if (config.noise.isEnable()) {
            pos.add(config.noise.getPosition(this, partialTicks));
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
     * from local to world
     */
    public Matrix4f getSpaceTransform() {
        return config.getSimulationSpace() == ParticleConfig.Space.Local ?
                emitter.transform().localToWorldMatrix() :
                initialTransform;
    }

    /**
     * from world to local
u     */
    public Matrix4f getSpaceTransformInverse() {
        return config.getSimulationSpace() == ParticleConfig.Space.Local ?
                emitter.transform().worldToLocalMatrix() :
                initialTransformInverse;
    }

    public Vector3f getSpaceScale() {
        return config.getSimulationSpace() == ParticleConfig.Space.Local ?
                emitter.transform().scale() :
                initialScale;
    }

    public Quaternionf getSpaceRotation() {
        return config.getSimulationSpace() == ParticleConfig.Space.Local ?
                emitter.transform().rotation() :
                new Quaternionf();
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
        if (config.uvAnimation.isEnable()) {
            return config.uvAnimation.getUVs(this, partialTicks);
        } else {
            return new Vector4f(0, 0, 1, 1);
        }
    }

    public int getRealLight(float partialTicks) {
        if (config.lights.isEnable()) {
            return config.lights.getLight(this, partialTicks);
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
    public void updateTick() {
        if (delay > 0) {
            delay--;
            return;
        }

        // update origin data
        updateOrigin();

        if (this.age == 0 && config.subEmitters.isEnable()) {
            config.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Birth);
        }

        // update life cycle
        if (this.age++ >= this.lifetime && lifetime > 0) {
            setRemoved(true);
            if (config.subEmitters.isEnable()) {
                config.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Death);
            }
            return;
        }

        // update data
        update();

        if (config.subEmitters.isEnable()) {
            config.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Tick);
        }

        if (lifetime > 0) {
            t = 1.0f * age / lifetime;
        }
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

    protected void update() {
        updateChanges();
    }

    protected void updateChanges() {
        this.updatePositionAndInternalVelocity();
        this.updateColor();
        this.updateSize();
        this.updateRotation();
        this.updateLight();
    }

    protected void updatePositionAndInternalVelocity() {
        var velocity = getRealVelocity();
        var moveX = velocity.x;
        var moveY = velocity.y;
        var moveZ = velocity.z;

        var level = emitter.getLevel();
        if (config.physics.isEnable() && config.physics.isHasCollision() && level != null &&
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

        // update internal velocity
        if (!config.physics.isEnable()) return;
        if (config.physics.isHasCollision() && !this.collided) {
            var bounceChance = config.physics.getBounceChance(this);
            var bounceRate = config.physics.getBounceRate(this);
            var bounceSpreadRate = config.physics.getBounceSpreadRate(this);
            if (Math.abs(velocity.x) / Math.abs(moveX) > 1.001) {
                updateCollisionBounce(bounceChance, velocity, bounceRate, bounceSpreadRate, Direction.Axis.X);
            } else if (Math.abs(velocity.y) / Math.abs(moveY) > 1.001) {
                updateCollisionBounce(bounceChance, velocity, bounceRate, bounceSpreadRate, Direction.Axis.Y);
            } else if (Math.abs(velocity.z) / Math.abs(moveZ) > 1.001) {
                updateCollisionBounce(bounceChance, velocity, bounceRate, bounceSpreadRate, Direction.Axis.Z);
            }
        }

        var gravity = config.physics.getGravity(this);
        if (gravity != 0) {
            this.addInternalVelocity(getSpaceTransformInverse().transformDirection(new Vector3f(0, -gravity * 0.04f, 0)));
        }

        var friction = config.physics.getFriction(this);
        this.velocityX *= friction;
        this.velocityY *= friction;
        this.velocityZ *= friction;

        if (this.collided && friction != 1.0) {
            this.velocityX *= 0.7F;
            this.velocityY *= 0.7F;
            this.velocityZ *= 0.7F;
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
        if (config.physics.isEnable() && config.physics.isRemovedWhenCollided()) {
            this.setRemoved(true);
            if (config.subEmitters.isEnable()) {
                config.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Death);
            }
        }
        if (config.subEmitters.isEnable()) {
            config.subEmitters.triggerEvent(this, SubEmittersSetting.Event.Collision);
            if (!isFirstCollision) {
                isFirstCollision = true;
                config.subEmitters.triggerEvent(this, SubEmittersSetting.Event.FirstCollision);
            }
        }
    }

    /**
     * It's the internal velocity, which is the local velocity
     */
    public Vector3f getInternalVelocity() {
        var velocity = new Vector3f(velocityX, velocityY, velocityZ);
        if (config.velocityOverLifetime.isEnable()) {
            var velocityAddition = config.velocityOverLifetime.getVelocityAddition(this);
            velocity.add(velocityAddition);
        }
        if (config.forceOverLifetime.isEnable() && config.forceOverLifetime.getSimulationSpace() == ParticleConfig.Space.Local) {
            velocity.add(config.forceOverLifetime.getForce(this));
        }
        return velocity;
    }

    /**
     * It's the total velocity, which is the world velocity
     */
    public Vector3f getRealVelocity() {
        var velocity = getSpaceTransform().transformDirection(getInternalVelocity());
        if (config.forceOverLifetime.isEnable() && config.forceOverLifetime.getSimulationSpace() == ParticleConfig.Space.World) {
            velocity.add(config.forceOverLifetime.getForce(this));
        }
        if (config.inheritVelocity.isEnable() && config.inheritVelocity.getMode() == InheritVelocitySetting.Mode.CURRENT) {
            velocity.add(config.inheritVelocity.getVelocity(emitter));
        }
        if (config.velocityOverLifetime.isEnable()) {
            var velocityMultiplier = config.velocityOverLifetime.getVelocityMultiplier(this);
            velocity.mul(velocityMultiplier);
        }
        return velocity;
    }

    protected void updateSize() {
        if (config.sizeBySpeed.isEnable() || config.sizeOverLifetime.isEnable() || config.noise.isEnable()) {
            var size = new Vector3f(initialSize);
            var mul = new Vector3f(1, 1, 1);

            if (config.noise.isEnable()) {
                size.add(config.noise.getSize(this, 0));
            }

            if (config.sizeBySpeed.isEnable()) {
                mul.mul(config.sizeBySpeed.getSize(this));
            }
            if (config.sizeOverLifetime.isEnable()) {
                mul.mul(config.sizeOverLifetime.getSize(this, 0));
            }

            setSize(size.mul(mul));
        }
    }

    protected void updateRotation() {
        if (config.rotationOverLifetime.isEnable() || config.rotationBySpeed.isEnable() || config.noise.isEnable()) {
            var rotation = new Vector3f(initialRotation);

            if (config.rotationOverLifetime.isEnable()) {
                rotation.add(config.rotationOverLifetime.getRotation(this, 0));
            }

            if (config.rotationBySpeed.isEnable()) {
                rotation.add(config.rotationBySpeed.getRotation(this));
            }

            if (config.noise.isEnable()) {
                rotation.add(config.noise.getRotation(this, 0));
            }

            setRotation(rotation);
        }
    }

    protected void updateColor() {
        if (config.colorOverLifetime.isEnable() || config.colorBySpeed.isEnable()) {
            var color = new Vector4f(initialColor);

            if (config.colorOverLifetime.isEnable()) {
                color.mul(config.colorOverLifetime.getColor(this, 0));
            }

            if (config.colorBySpeed.isEnable()) {
                color.mul(config.colorBySpeed.getColor(this));
            }

            setColor(color);
        }
    }

    protected void updateLight() {
        if (config.lights.isEnable()) return;
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
            var quaternion = renderMode.quaternion.apply(this, camera, partialTicks);
            if (!Vector3fHelper.isZero(rotation)) {
                quaternion = new Quaternionf(quaternion).rotateXYZ(rotation.x, rotation.y, rotation.z);
            }
            var rawVertexes = new Vector3f[]{
                    new Vector3f(1.0F, -1.0F, 0.0F),
                    new Vector3f(1.0F, 1.0F, 0.0F),
                    new Vector3f(-1.0F, 1.0F, 0.0F),
                    new Vector3f(-1.0F, -1.0F, 0.0F),
            };
            var normal = new Vector3f(0, 0, 1);
            var spaceScale = getSpaceScale();
            for (var i = 0; i < 4; ++i) {
                var vertex = rawVertexes[i];
                vertex.mul(size.x, size.y, size.z);
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
