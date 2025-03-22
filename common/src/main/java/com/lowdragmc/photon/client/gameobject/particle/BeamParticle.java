package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * @author KilaBash
 * @date 2022/06/15
 * @implNote BeamParticle
 */
@Environment(EnvType.CLIENT)
public class BeamParticle implements IParticle {
    /**
     * Basic data
     */
    protected float r = 1, g = 1, b = 1, a = 1; // color
    protected float ro = 1, go = 1, bo = 1, ao = 1;
    protected int light = -1;
    @Setter @Getter
    protected float emit;
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

    @Getter
    protected float t;
    protected BeamConfig config;
    @Getter
    protected IParticleEmitter emitter;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    @Getter
    public RandomSource randomSource;

    public BeamParticle(IParticleEmitter emitter, BeamConfig config, RandomSource randomSource) {
        this.emitter = emitter;
        this.config = config;
        this.randomSource = randomSource;
        this.setup();
    }

    public void setup() {
        this.setLifetime(-1);
        update();
        updateOrigin();
    }

    @Override
    public void tick() {
        if (delay > 0) {
            delay--;
            return;
        }

        updateOrigin();

        if (this.age++ >= this.lifetime && lifetime > 0) {
            setRemoved(true);
        }

        update();

        if (lifetime > 0) {
            t = 1.0f * age / this.lifetime;
        }
    }

    protected void updateOrigin() {
        this.ro = this.r;
        this.go = this.g;
        this.bo = this.b;
        this.ao = this.a;
    }

    protected void update() {
        this.updateChanges();
    }

    protected void updateChanges() {
        this.updateColor();
        this.updateLight();
    }

    protected void updateColor() {
        var color = config.getColor().get(getT(), () -> getMemRandom("color")).intValue();
        r = ColorUtils.red(color);
        g = ColorUtils.green(color);
        b = ColorUtils.blue(color);
        a = ColorUtils.alpha(color);
    }

    protected void updateLight() {
        if (config.lights.isEnable() || config.renderer.isBloomEffect()) return;
        light = getLightColor();
    }

    public int getRealLight(float partialTicks) {
        if (config.renderer.isBloomEffect()) {
            return LightTexture.FULL_BRIGHT;
        }
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

    public Vector3f getWorldPos() {
        return emitter.transform().position();
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

    protected float getRealWidth(float pPartialTicks) {
        return config.getWidth().get(getT(pPartialTicks), () -> getMemRandom("width")).floatValue();
    }

    protected float getRealEmit(float pPartialTicks) {
        return config.getEmitRate().get(getT(pPartialTicks), () -> getMemRandom("emit")).floatValue();
    }

    public void render(@Nonnull VertexConsumer pBuffer, @Nonnull Camera camera, float partialTicks) {
        var cameraPos = camera.getPosition().toVector3f();
        var from = getWorldPos();
        var end = new Vector3f(from).add(emitter.transform().localToWorldMatrix().transformDirection(config.getEnd(), new Vector3f()));

        var offset = - getRealEmit(partialTicks) * (getAge() + partialTicks);
        var uvs = getRealUVs(partialTicks);
        var u0 = uvs.x + offset;
        var u1 = uvs.z + offset;
        var v0 = uvs.y;
        var v1 = uvs.w;
        var beamHeight = getRealWidth(partialTicks);
        var light = getRealLight(partialTicks);

        var color = getRealColor(partialTicks);
        var r = color.x;
        var g = color.y;
        var b = color.z;
        var a = color.w;

        var direction = new Vector3f(end).sub(from);

        var toO = new Vector3f(from).sub(cameraPos);
        Vector3f n = new Vector3f(toO).cross(direction).normalize().mul(beamHeight);

        var p0 = new Vector3f(from).add(n).sub(cameraPos);
        var p1 = new Vector3f(from).add(n.mul(-1)).sub(cameraPos);
        var p3 = new Vector3f(end).add(n).sub(cameraPos);
        var p4 = new Vector3f(end).add(n.mul(-1)).sub(cameraPos);

        pBuffer.vertex(p1.x, p1.y, p1.z).uv(u0, v0).color(r, g, b, a).uv2(light).endVertex();
        pBuffer.vertex(p0.x, p0.y, p0.z).uv(u0, v1).color(r, g, b, a).uv2(light).endVertex();
        pBuffer.vertex(p4.x, p4.y, p4.z).uv(u1, v1).color(r, g, b, a).uv2(light).endVertex();
        pBuffer.vertex(p3.x, p3.y, p3.z).uv(u1, v0).color(r, g, b, a).uv2(light).endVertex();
    }

    @Override
    public PhotonParticleRenderType getRenderType() {
        return config.particleRenderType;
    }

    @Override
    public float getT(float partialTicks) {
        return t + partialTicks / getLifetime();
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

}
