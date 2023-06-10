package com.lowdragmc.photon.client.particle;

import com.lowdragmc.lowdraglib.utils.DummyWorld;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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
    private static final Function<LParticle, Vector3f> ADDITION = p -> new Vector3f(0 ,0, 0);
    private static final Function<LParticle, Float> MULTIPLIER = p -> 1f;
    protected Vector3f quadSize = new Vector3f(1, 1, 1);
    @Setter @Getter
    protected boolean moveless;
    @Setter @Getter
    protected int delay;
    @Setter @Getter
    protected int light = -1;
    @Setter @Getter
    protected boolean cull = true;
    @Setter @Getter
    protected float yaw, pitch;
    @Nullable
    @Setter @Getter
    protected Quaternionf quaternion;
    @Nullable
    private Level realLevel;
    @Setter
    @Nullable
    protected Consumer<LParticle> onUpdate;
    @Setter
    protected Function<LParticle, Vector3f> velocityAddition = ADDITION;
    @Setter
    protected Function<LParticle, Float> velocityMultiplier = MULTIPLIER;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector4f> dynamicColor = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector3f> dynamicSize = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector3f> rotationAddition = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector3f> positionAddition = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Vector4f> dynamicUVs = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Integer> dynamicLight = null;
    // runtime
    @Getter
    protected float t;
    @Getter
    @Nullable
    protected IParticleEmitter emitter;
    @Getter
    protected Function<Object, Float> memRandom = Util.memoize(o -> getRandomSource().nextFloat());
    @Getter
    protected boolean stoppedByCollision;

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
        this.quadSize = new Vector3f(pScale, pScale, pScale);
        this.setSize(pScale, pScale);
        return this;
    }

    public void setQuadSize(Vector3f size) {
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

        updateOrigin();

        if (this.age++ >= this.lifetime && lifetime > 0) {
            this.remove();
        }
        update();

        if (lifetime > 0) {
            t = 1.0f * age / this.lifetime;
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
        if (!moveless) {
            var addition = velocityAddition.apply(this);
            var multiplier = velocityMultiplier.apply(this);
            this.move((this.xd + addition.x) * multiplier, (this.yd + addition.y) * multiplier, (this.zd + addition.z) * multiplier);

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
            }
        }
    }

    @Override
    public void move(double x, double y, double z) {
        if (this.stoppedByCollision) {
            return;
        }
        double d = x;
        double e = y;
        double f = z;
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
        if (Math.abs(e) >= (double)1.0E-5f && Math.abs(y) < (double)1.0E-5f) {
            this.stoppedByCollision = true;
        }
        boolean bl = this.onGround = e != y && e < 0.0;
        if (d != x) {
            this.xd = 0.0;
        }
        if (f != z) {
            this.zd = 0.0;
        }
    }

    protected int getLightColor(float partialTick) {
        BlockPos blockPos = new BlockPos((int) this.x, (int) this.y, (int) this.z);
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

        float x = (float)(Mth.lerp(partialTicks, this.xo, this.x) - vec3.x());
        float y = (float)(Mth.lerp(partialTicks, this.yo, this.y) - vec3.y());
        float z = (float)(Mth.lerp(partialTicks, this.zo, this.z) - vec3.z());
        if (positionAddition != null) {
            var addition = positionAddition.apply(this, partialTicks);
            x += addition.x;
            y += addition.y;
            z += addition.z;
        }

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

        Vector3f size, rotation = new Vector3f(getRoll(partialTicks), this.pitch, this.yaw);
        if (dynamicSize != null) {
            size = dynamicSize.apply(this, partialTicks);
        } else {
            size = this.getQuadSize(partialTicks);
        }
        if (this.rotationAddition != null) {
            rotation = rotation.add(this.rotationAddition.apply(this, partialTicks));
        }

        Quaternionf quaternion = this.quaternion;
        if (quaternion == null) {
            quaternion = camera.rotation();
        }
        if (!(rotation.x == 0 && rotation.y == 0 && rotation.z == 0)) {
            quaternion = new Quaternionf(quaternion);
            if (rotation.y != 0) {
                quaternion.rotateX(rotation.y);
            }
            if (rotation.z != 0) {
                quaternion.rotateY(rotation.z);
            }
            quaternion.rotateZ(rotation.x);
        }

        Vector3f[] rawVertexes = new Vector3f[]{new Vector3f(-1.0F, -1.0F, 0.0F), new Vector3f(-1.0F, 1.0F, 0.0F), new Vector3f(1.0F, 1.0F, 0.0F), new Vector3f(1.0F, -1.0F, 0.0F)};

        for(int i = 0; i < 4; ++i) {
            Vector3f vertex = rawVertexes[i];
            vertex = quaternion.transform(vertex);
            vertex.mul(size.x, size.y, size.z);
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

    public Vector3f getQuadSize(float partialTicks) {
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

    protected int getLight(float pPartialTick) {
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

    public Vector3f getVelocity() {
        return new Vector3f((float) this.xd, (float) this.yd, (float) this.zd);
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

    public void setSpeed(Vector3f vec) {
        super.setParticleSpeed(vec.x, vec.y, vec.z);
    }

    public void setSpeed(float mul) {
        this.xd *= mul;
        this.yd *= mul;
        this.zd *= mul;
    }

    public Vector3f getPos() {
        return getPos(0);
    }

    public Vector3f getPos(float partialTicks) {
        return new Vector3f((float)Mth.lerp(partialTicks, this.xo, this.x),
                (float)Mth.lerp(partialTicks, this.yo, this.y),
                (float)Mth.lerp(partialTicks, this.zo, this.z));
    }

    public float getT(float partialTicks) {
        return t + partialTicks / lifetime;
    }

    public void setPos(Vector3f realPos, boolean origin) {
        setPos(realPos.x, realPos.y, realPos.z, origin);
    }

    public float getMemRandom(Object object) {
        return memRandom.apply(object);
    }

    public void setRotation(Vector3f rotation) {
        setRoll(rotation.x);
        setPitch(rotation.y);
        setYaw(rotation.z);
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
        this.memRandom = Util.memoize(o -> getRandomSource().nextFloat());
        this.stoppedByCollision = false;
        this.removed = false;
        this.onGround = false;
        this.emitter = null;
        this.t = 0;
    }

    public static class Basic extends LParticle {
        @Getter
        final ParticleRenderType renderType;

        public Basic(ClientLevel level, double x, double y, double z, ParticleRenderType renderType) {
            super(level, x, y, z);
            this.renderType = renderType;
        }

        public Basic(ClientLevel level, double x, double y, double z, double sX, double sY, double sZ, ParticleRenderType renderType) {
            super(level, x, y, z, sX, sY, sZ);
            this.renderType = renderType;
        }

    }
}
