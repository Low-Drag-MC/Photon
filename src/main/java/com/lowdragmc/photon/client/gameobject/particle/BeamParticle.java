package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamRuntime;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * @author KilaBash
 * @date 2022/06/15
 * @implNote BeamParticle
 */
@OnlyIn(Dist.CLIENT)
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
    protected boolean isRemoved;

    protected BeamConfig config;
    /** The owning emitter's per-instance runtime layer (timeline overrides + moved value behaviour). */
    @Getter
    protected BeamRuntime runtime;
    @Getter
    protected IParticleEmitter emitter;
    @Getter
    protected ConcurrentHashMap<Object, Float> memRandom = new ConcurrentHashMap<>();
    @Getter
    public RandomSource randomSource;

    public BeamParticle(IParticleEmitter emitter, BeamConfig config) {
        this.emitter = emitter;
        this.config = config;
        this.runtime = emitter instanceof BeamEmitter e ? e.runtime() : new BeamRuntime(config);
        this.randomSource = RandomSource.create(emitter.getRandomSource().nextLong());
        this.setup();
    }

    public void setup() {
        this.setDelay(runtime.startDelay.get());
        update();
        updateOrigin();
    }

    @Override
    public void updateTick(float dt) {
        if (delay > 0) {
            delay--;
            return;
        }

        update();
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

    protected void update() {
        this.updateChanges();
    }

    protected void updateChanges() {
        // pure per-tick recomputes: skippable during a fast seek replay (final full ticks restore them)
        if (PhotonParticleManager.isFastSimulation()) {
            return;
        }
        this.updateColor();
        this.updateLight();
    }

    protected void updateColor() {
        var color = runtime.color.get().get(getT(), () -> getMemRandom("color")).intValue();
        r = ColorUtils.red(color);
        g = ColorUtils.green(color);
        b = ColorUtils.blue(color);
        a = ColorUtils.alpha(color);
    }

    protected void updateLight() {
        if (runtime.lights.isEnable()) return;
        light = getLightColor();
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
        if (runtime.uvAnimation.isEnable()) {
            return runtime.uvAnimation.getUVs(this, partialTicks);
        } else {
            return new Vector4f(0, 0, 1, 1);
        }
    }

    public float getRealWidth(float pPartialTicks) {
        return runtime.width.get().get(getT(pPartialTicks), () -> getMemRandom("width")).floatValue();
    }

    public float getRealEmit(float pPartialTicks) {
        return runtime.emitRate.get().get(getT(pPartialTicks), () -> getMemRandom("emit")).floatValue();
    }

    public Vector3f getRealEnd(@Nonnull Camera camera, Vector3f from) {
        var end = new Vector3f(from).add(emitter.transform().localToWorldMatrix().transformDirection(config.getEnd(), new Vector3f()));
        if (config.getRaycast() == BeamConfig.RaycastMode.BLOCKS || config.getRaycast() == BeamConfig.RaycastMode.BLOCKS_AND_ENTITIES) {
            var level = camera.getEntity().level();
            var result = level.clip(
                    new ClipContext(new Vec3(from.x, from.y, from.z),
                            new Vec3(end.x, end.y, end.z),
                            config.getRaycastBlockMode(),
                            config.getRaycastFluidMode(),
                            CollisionContext.empty()));
            if (result.getType() != HitResult.Type.MISS) {
                end = result.getLocation().toVector3f();
            }
        }
        if (config.getRaycast() == BeamConfig.RaycastMode.ENTITIES || config.getRaycast() == BeamConfig.RaycastMode.BLOCKS_AND_ENTITIES) {
            var level = camera.getEntity().level();
            var size = getRealWidth(0);
            var velocity = new Vector3f(end).sub(from);
            var vec3 = Entity.collideBoundingBox(null, new Vec3(velocity),
                    AABB.ofSize(Vec3.ZERO, size, size, size), level, List.of());
            end = new Vector3f(from).add(vec3.toVector3f());
        }
        return end;
    }

    @Override
    public PhotonFXRenderPass getRenderType() {
        return config.particleRenderType;
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

}
