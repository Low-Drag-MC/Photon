package com.lowdragmc.photon.client.particle;

import com.lowdragmc.lowdraglib.utils.DummyWorld;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import com.mojang.math.Vector4f;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2022/05/30
 * @implNote LParticle
 */
@Environment(EnvType.CLIENT)
@ParametersAreNonnullByDefault
public abstract class
LParticle extends Particle {
    private static final double MAXIMUM_COLLISION_VELOCITY_SQUARED = Mth.square(100.0);
    /**
     * Quad Size of Particles.
     */
    protected Vector3 quadSize = new Vector3(1, 1, 1);
    /**
     * Can particle move by speed.
     */
    @Setter @Getter
    protected boolean moveless;
    /**
     * Delay time before tick and rendering
     */
    @Setter @Getter
    protected int delay;
    /**
     * Lighting map value, -1: get light from world.
     */
    @Setter @Getter
    protected int light = -1;
    /**
     * Should we do cull check
     */
    @Setter @Getter
    protected boolean cull = true;
    /**
     * Rotation of yaw, pitch
     */
    @Setter @Getter
    protected float yaw, pitch;
    /**
     * possibility of bounce when it has physics.
     */
    @Setter @Getter
    protected float bounceChance = 1;
    /**
     * bounce rate of speed when collision happens.
     */
    @Setter @Getter
    protected float bounceRate = 1;
    /**
     * addition speed for other two axis gaussian noise when collision happens.
     */
    @Setter @Getter
    protected float bounceSpreadRate = 0;
    @Setter @Getter
    protected Supplier<Quaternion> quaternionSupplier = () -> null;
    @Nullable
    private Level realLevel;
    @Setter
    @Nullable
    protected Consumer<LParticle> onUpdate;
    @Setter
    @Nullable
    protected Function<LParticle, Vector3> velocityAddition = null;
    @Setter
    @Nullable
    protected Function<LParticle, Float> velocityMultiplier = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector4f> dynamicColor = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector3> dynamicSize = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector3> rotationAddition = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector3> positionAddition = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector4f> dynamicUVs = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Integer> dynamicLight = null;
    @Setter
    @Nullable
    protected Consumer<LParticle> onBirth = null;
    @Setter
    @Nullable
    protected Consumer<LParticle> onCollision = null;
    @Setter
    @Nullable
    protected Consumer<LParticle> onDeath = null;
    // runtime
    @Getter
    protected float t;
    @Getter
    @Nullable
    protected IParticleEmitter emitter;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();

    protected LParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        this.realLevel = level;
        this.hasPhysics = false;
        this.friction = 1;
    }

    protected LParticle(ClientLevel level, double x, double y, double z, double sX, double sY, double sZ) {
        super(level, x, y, z, sX, sY, sZ);
        this.realLevel = level;
        this.hasPhysics = false;
        this.friction = 1;
    }

    public RandomSource getRandomSource() {
        return random;
    }

    @Nullable
    public Level getLevel() {
        return realLevel == null ? super.level : realLevel;
    }

    public void setLevel(@Nullable Level level) {
        this.realLevel = level;
    }

    public void setPhysics(boolean hasPhysics) {
        this.hasPhysics = hasPhysics;
    }

    public void setFullLight() {
        setLight(0xf000f0);
    }

    @Nonnull
    @Deprecated
    public LParticle scale(float pScale) {
        this.quadSize = new Vector3(pScale, pScale, pScale);
        this.setSize(pScale, pScale);
        return this;
    }

    public void setQuadSize(Vector3 size) {
        this.quadSize = size;
        this.setSize((float) size.x, (float) size.y);
    }

    public void setPos(double pX, double pY, double pZ, boolean setOrigin) {
        this.x = pX;
        this.y = pY;
        this.z = pZ;
        if (setOrigin) {
            this.xo = x;
            this.yo = y;
            this.zo = z;
        }
        float f = this.bbWidth / 2.0F;
        float f1 = this.bbHeight;
        this.setBoundingBox(new AABB(pX - (double)f, pY, pZ - (double)f, pX + (double)f, pY + (double)f1, pZ + (double)f));
    }

    public void setGravity(float gravity) {
        this.gravity = gravity;
    }

    public void setImmortal() {
        setLifetime(-1);
    }

    public void setFriction(float friction) {
        this.friction = friction;
    }

    public void setColor(int color) {
        this.setColor((float) FastColor.ARGB32.red(color) / 255, (float)FastColor.ARGB32.green(color) / 255, (float)FastColor.ARGB32.blue(color) / 255);
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    public void setARGBColor(int color) {
        this.setColor((float) FastColor.ARGB32.red(color) / 255, (float)FastColor.ARGB32.green(color) / 255, (float)FastColor.ARGB32.blue(color) / 255);
        setAlpha((float) FastColor.ARGB32.alpha(color) / 255);
    }

    public void setSize(float size) {
        scale(size);
    }

    public void setRoll(float roll) {
        this.roll = roll;
    }

    @Override
    public void tick() {
        if (delay > 0) {
            delay--;
            return;
        }

        if (onBirth != null && this.age == 0) {
            onBirth.accept(this);
        }

        updateOrigin();

        if (this.age++ >= getLifetime() && getLifetime() > 0) {
            this.remove();
            if (onDeath != null) {
                onDeath.accept(this);
            }
        }
        update();

        if (getLifetime() > 0) {
            t = 1.0f * age / getLifetime();
        }
    }

    protected void updateOrigin() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
    }

    protected void update() {
        updateChanges();
        if (onUpdate != null) {
            onUpdate.accept(this);
        }
    }

    protected void updateChanges() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (!moveless) {
            var velocity = getVelocity();
            this.move(velocity.x, velocity.y, velocity.z);

            this.yd -= 0.04D * this.gravity;
            if (this.speedUpWhenYMotionIsBlocked && this.y == this.yo) {
                this.xd *= 1.1D;
                this.zd *= 1.1D;
            }
            this.xd *= this.friction;
            this.yd *= this.friction;
            this.zd *= this.friction;
            if (this.onGround && this.friction != 1.0) {
                this.xd *= 0.7F;
                this.zd *= 0.7F;
                this.yd *= 0.7F;
            }
        }
    }

    @Override
    public void move(double x, double y, double z) {
        double moveX = x;
        double moveY = y;
        double moveZ = z;
        if (this.hasPhysics && getLevel() != null && (x != 0.0 || y != 0.0 || z != 0.0) && x * x + y * y + z * z < MAXIMUM_COLLISION_VELOCITY_SQUARED) {
            Vec3 vec3 = Entity.collideBoundingBox(null, new Vec3(x, y, z), this.getBoundingBox(), getLevel(), List.of());
            x = vec3.x;
            y = vec3.y;
            z = vec3.z;
        }
        if (x != 0.0 || y != 0.0 || z != 0.0) {
            this.setBoundingBox(this.getBoundingBox().move(x, y, z));
            this.setLocationFromBoundingbox();
        }
        if (!this.onGround && this.hasPhysics) {
            if (Math.abs(moveY) >= 1.0E-5 && Math.abs(y) < 1.0E-5) {
                if (bounceChance < 1 && bounceChance < random.nextFloat()) {
                    this.onGround = true;
                } else {
                    this.yd = -this.yd * bounceRate;
                    if (bounceSpreadRate > 0) {
                        this.xd += bounceSpreadRate * random.nextGaussian();
                        this.zd += bounceSpreadRate * random.nextGaussian();
                    }
                }
                if (onCollision != null) {
                    onCollision.accept(this);
                }
            } else if (Math.abs(moveX) >= 1.0E-5 && Math.abs(x) < 1.0E-5) {
                if (bounceChance < 1 && bounceChance < random.nextFloat()) {
                    this.onGround = true;
                } else {
                    this.xd = -this.xd * bounceRate;
                    if (bounceSpreadRate > 0) {
                        this.yd += bounceSpreadRate * random.nextGaussian();
                        this.zd += bounceSpreadRate * random.nextGaussian();
                    }
                }
                if (onCollision != null) {
                    onCollision.accept(this);
                }
            } else if (Math.abs(moveZ) >= 1.0E-5 && Math.abs(z) < 1.0E-5) {
                if (bounceChance < 1 && bounceChance < random.nextFloat()) {
                    this.onGround = true;
                } else {
                    this.zd = -this.zd * bounceRate;
                    if (bounceSpreadRate > 0) {
                        this.xd += bounceSpreadRate * random.nextGaussian();
                        this.yd += bounceSpreadRate * random.nextGaussian();
                    }
                }
                if (onCollision != null) {
                    onCollision.accept(this);
                }
            }
        }
    }

    protected int getLightColor(float partialTick) {
        BlockPos blockPos = new BlockPos(this.x, this.y, this.z);
        var level = getLevel();
        if (level != null && (level.hasChunkAt(blockPos) || level instanceof DummyWorld)) {
            return LevelRenderer.getLightColor(level, blockPos);
        }
        return 0;
    }

    public void render(@Nonnull VertexConsumer pBuffer, Camera pRenderInfo, float pPartialTicks) {
        if (delay <= 0 && (this.emitter == null || this.emitter.isVisible())) {
            renderInternal(pBuffer, pRenderInfo, pPartialTicks);
        }
    }

    public void renderInternal(@Nonnull VertexConsumer buffer, Camera camera, float partialTicks) {
        Vec3 vec3 = camera.getPosition();

        var pos = getPos(partialTicks);
        float x = (float)(pos.x - vec3.x());
        float y = (float)(pos.y - vec3.y());
        float z = (float)(pos.z - vec3.z());

        float a = getAlpha(partialTicks);
        float r = getRed(partialTicks);
        float g = getGreen(partialTicks);
        float b = getBlue(partialTicks);
        if (dynamicColor != null){
            var color = dynamicColor.apply(this, partialTicks);
            a *= color.w();
            r *= color.x();
            g *= color.y();
            b *= color.z();
        }

        Vector3 size, rotation = getRotation(partialTicks);
        if (dynamicSize != null) {
            size = dynamicSize.apply(this, partialTicks);
        } else {
            size = this.getQuadSize(partialTicks);
        }
        if (this.rotationAddition != null) {
            rotation = rotation.add(this.rotationAddition.apply(this, partialTicks));
        }

        Quaternion quaternion = this.getQuaternionSupplier().get();
        if (quaternion == null) {
            quaternion = camera.rotation();
        }
        if (!rotation.isZero()) {
            quaternion = new Quaternion(quaternion);
            if (rotation.y != 0) {
                quaternion.mul(Vector3f.XP.rotation((float) rotation.y));
            }
            if (rotation.z != 0) {
                quaternion.mul(Vector3f.YP.rotation((float) rotation.z));
            }
            quaternion.mul(Vector3f.ZP.rotation((float) rotation.x));
        }

        Vector3f[] rawVertexes = new Vector3f[]{new Vector3f(-1.0F, -1.0F, 0.0F), new Vector3f(-1.0F, 1.0F, 0.0F), new Vector3f(1.0F, 1.0F, 0.0F), new Vector3f(1.0F, -1.0F, 0.0F)};

        for(int i = 0; i < 4; ++i) {
            Vector3f vertex = rawVertexes[i];
            vertex.transform(quaternion);
            vertex.mul((float) size.x, (float) size.y, (float) size.z);
            vertex.add(x, y, z);
        }

        float u0, u1, v0, v1;
        if (dynamicUVs != null) {
            var uvs = dynamicUVs.apply(this, partialTicks);
            u0 = uvs.x();
            v0 = uvs.y();
            u1 = uvs.z();
            v1 = uvs.w();
        } else {
            u0 = this.getU0(partialTicks);
            u1 = this.getU1(partialTicks);
            v0 = this.getV0(partialTicks);
            v1 = this.getV1(partialTicks);
        }

        int light = dynamicLight == null ? this.getLight(partialTicks) : dynamicLight.apply(this, partialTicks);

        buffer.vertex(rawVertexes[0].x(), rawVertexes[0].y(), rawVertexes[0].z()).uv(u1, v1).color(r, g, b, a).uv2(light).endVertex();
        buffer.vertex(rawVertexes[1].x(), rawVertexes[1].y(), rawVertexes[1].z()).uv(u1, v0).color(r, g, b, a).uv2(light).endVertex();
        buffer.vertex(rawVertexes[2].x(), rawVertexes[2].y(), rawVertexes[2].z()).uv(u0, v0).color(r, g, b, a).uv2(light).endVertex();
        buffer.vertex(rawVertexes[3].x(), rawVertexes[3].y(), rawVertexes[3].z()).uv(u0, v1).color(r, g, b, a).uv2(light).endVertex();
    }

    public boolean shouldCull() {
        return cull;
    }

    public float getAlpha(float partialTicks) {
        return this.alpha;
    }

    public float getRed(float partialTicks) {
        return this.rCol;
    }

    public float getGreen(float partialTicks) {
        return this.gCol;
    }

    public float getBlue(float partialTicks) {
        return this.bCol;
    }

    public Vector3 getQuadSize(float partialTicks) {
        return this.quadSize;
    }

    protected float getRoll(float partialTicks) {
        return Mth.lerp(partialTicks, this.oRoll, this.roll);
    }

    public float getGravity() {
        return gravity;
    }

    public float getRoll() {
        return roll;
    }

    public int getLight(float pPartialTick) {
        if (light >= 0) return light;
        if (getLevel() == null) return 0xf000f0;
        return getLightColor(pPartialTick);
    }

    public float getU0(float pPartialTicks) {
        return 0;
    }

    public float getU1(float pPartialTicks) {
        return 1;
    }

    public float getV0(float pPartialTicks) {
        return 0;
    }

    public float getV1(float pPartialTicks) {
        return 1;
    }

    public int getAge() {
        return age;
    }

    public Vector3 getVelocity() {
        var speed = new Vector3(this.xd, this.yd, this.zd);
        if (velocityAddition != null) {
            speed.add(velocityAddition.apply(this));
        }
        if (velocityMultiplier != null) {
            speed.multiply(velocityMultiplier.apply(this));
        }
        return speed;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void prepareForEmitting(@Nullable IParticleEmitter emitter) {
        updateOrigin();
        this.emitter = emitter;
    }

    public void resetAge() {
        this.age = 0;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public void setSpeed(Vector3 vec) {
        super.setParticleSpeed(vec.x, vec.y, vec.z);
    }

    public void setSpeed(float mul) {
        this.xd *= mul;
        this.yd *= mul;
        this.zd *= mul;
    }

    public Vector3 getPos() {
        return getPos(0);
    }

    public Vector3 getPos(float partialTicks) {
        var pos = new Vector3((float)Mth.lerp(partialTicks, this.xo, this.x),
                (float)Mth.lerp(partialTicks, this.yo, this.y),
                (float)Mth.lerp(partialTicks, this.zo, this.z));
        if (positionAddition != null) {
            var addition = positionAddition.apply(this, partialTicks);
            pos.add(addition);
        }
        return pos;
    }

    public Vector3 getRotation(float partialTicks) {
        var rotation = new Vector3(getRoll(partialTicks), getPitch(), getYaw());
        if (rotationAddition != null) {
            var addition = rotationAddition.apply(this, partialTicks);
            rotation.add(addition);
        }
        return rotation;
    }

    public float getT(float partialTicks) {
        return t + partialTicks / getLifetime();
    }

    public void setPos(Vector3 realPos, boolean origin) {
        setPos(realPos.x, realPos.y, realPos.z, origin);
    }

    public float getMemRandom(Object object) {
        return getMemRandom(object, RandomSource::nextFloat);
    }

    public float getMemRandom(Object object, Function<RandomSource, Float> randomFunc) {
        var value = memRandom.get(object);
        if (value == null) return memRandom.computeIfAbsent(object, o -> randomFunc.apply(random));
        return value;
    }

    public void setRotation(Vector3 rotation) {
        setRoll((float) rotation.x);
        setPitch((float) rotation.y);
        setYaw((float) rotation.z);
    }

    public ClientLevel getClientLevel() {
        return level;
    }

    public Vector4f getColor(float partialTicks) {
        float a = getAlpha(partialTicks);
        float r = getRed(partialTicks);
        float g = getGreen(partialTicks);
        float b = getBlue(partialTicks);
        if (dynamicColor != null){
            var color = dynamicColor.apply(this, partialTicks);
            a *= color.w();
            r *= color.x();
            g *= color.y();
            b *= color.z();
        }
        return new Vector4f(r, g, b, a);
    }

    public void resetParticle() {
        resetAge();
        this.memRandom.clear();
        this.removed = false;
        this.onGround = false;
        this.emitter = null;
        this.t = 0;
    }

    @Override
    @Nonnull
    public abstract PhotonParticleRenderType getRenderType();

    public static class Basic extends LParticle {
        @Getter
        final PhotonParticleRenderType renderType;

        public Basic(ClientLevel level, double x, double y, double z, PhotonParticleRenderType renderType) {
            super(level, x, y, z);
            this.renderType = renderType;
        }

        public Basic(ClientLevel level, double x, double y, double z, double sX, double sY, double sZ, PhotonParticleRenderType renderType) {
            super(level, x, y, z, sX, sY, sZ);
            this.renderType = renderType;
        }

    }
}
