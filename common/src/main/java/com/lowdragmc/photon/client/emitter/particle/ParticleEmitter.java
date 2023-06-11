package com.lowdragmc.photon.client.emitter.particle;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.gui.editor.runtime.PersistedParser;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.emitter.ParticleQueueRenderType;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.mojang.math.Vector4f;
import net.minecraft.nbt.CompoundTag;
import com.lowdragmc.photon.client.fx.IFXEffect;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.core.mixins.accessor.BlendModeAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/**
 * @author KilaBash
 * @date 2023/5/25
 * @implNote ParticleEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegister(name = "particle", group = "emitter")
public class ParticleEmitter extends LParticle implements IParticleEmitter {
    public static int VERSION = 1;

    @Setter
    @Getter
    @Persisted
    protected String name = "particle emitter";
    @Getter
    @Persisted(subPersisted = true)
    protected final ParticleConfig config;
    protected final PhotonParticleRenderType renderType;

    // runtime
    @Getter
    protected final Map<ParticleRenderType, Queue<LParticle>> particles = new HashMap<>();
    @Getter @Setter
    protected boolean visible = true;
    @Nullable
    @Getter @Setter
    protected IFXEffect fXEffect;

    public ParticleEmitter() {
        this(new ParticleConfig());
    }

    public ParticleEmitter(ParticleConfig config) {
        super(null, 0, 0, 0);
        setCull(false);
        this.config = config;
        this.renderType = new RenderType(config);
    }

    @Override
    public IParticleEmitter copy(boolean deep) {
        if (deep) {
            return IParticleEmitter.super.copy();
        }
        return new ParticleEmitter(config);
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = IParticleEmitter.super.serializeNBT();
        tag.putInt("_version", VERSION);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        var version = tag.contains("_version") ? tag.getInt("_version") : 0;
        if (version == 0) { // legacy version
            name = tag.getString("name");
            PersistedParser.deserializeNBT(tag, new HashMap<>(), config.getClass(), config);
            return;
        }
        IParticleEmitter.super.deserializeNBT(tag);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        ConfiguratorParser.createConfigurators(father, new HashMap<>(), config.getClass(), config);
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////
    protected LParticle createNewParticle() {
        var randomSource= getRandomSource();
        var particle = new Basic(level, this.x, this.y, this.z, renderType);
        particle.setLevel(getLevel());
        // start value
        particle.setDelay(config.startDelay.get(randomSource, t).intValue());
        if (config.lifetimeByEmitterSpeed.isEnable()) {
            particle.setLifetime(config.lifetimeByEmitterSpeed.getLifetime(particle, this, config.startLifetime.get(randomSource, t).intValue()));
        } else {
            particle.setLifetime(config.startLifetime.get(randomSource, t).intValue());
        }
        config.shape.setupParticle(particle);
        particle.setSpeed(config.startSpeed.get(randomSource, t).floatValue());
        var sizeScale = config.startSize.get(randomSource, t).floatValue();
        var startedSize = new Vector3(sizeScale, sizeScale, sizeScale);
        particle.setSize(sizeScale);
        var rotation = config.startRotation.get(randomSource, t).multiply(Mth.TWO_PI / 360);
        particle.setRoll((float) rotation.x);
        particle.setPitch((float) rotation.y);
        particle.setYaw((float) rotation.z);
        particle.setARGBColor(config.startColor.get(randomSource, t).intValue());
        config.renderer.setupQuaternion(particle);
        if (config.physics.isEnable()) {
            particle.setPhysics(config.physics.isHasCollision());
            particle.setGravity(config.physics.getGravity().get(randomSource, 0).floatValue());
            particle.setFriction(config.physics.getFriction().get(randomSource, 0).floatValue());
        }
        // particle logic
        final var emitterVelocity = getVelocity();

        if (config.velocityOverLifetime.isEnable() || config.inheritVelocity.isEnable()) {
            particle.setVelocityAddition(p -> {
                var addition = new Vector3(0, 0, 0);
                if (config.velocityOverLifetime.isEnable()) {
                    addition.add(config.velocityOverLifetime.getVelocityAddition(p, this));
                }
                if (config.inheritVelocity.isEnable()) {
                    addition.add(config.inheritVelocity.getVelocityAddition(p, this, emitterVelocity));
                }
                return addition;
            });
        }

        if (config.velocityOverLifetime.isEnable()) {
            particle.setVelocityMultiplier(p -> {
                float multiplier = 1;
                if (config.velocityOverLifetime.isEnable()) {
                    multiplier *= config.velocityOverLifetime.getVelocityMultiplier(p);
                }
                return multiplier;
            });
        }

        if (config.forceOverLifetime.isEnable() || config.sizeOverLifetime.isEnable() || config.sizeBySpeed.isEnable() || config.physics.isEnable() || config.noise.isEnable()) {
            particle.setOnUpdate(p -> {
                if (config.forceOverLifetime.isEnable()) {
                    p.setSpeed(p.getVelocity().add(config.forceOverLifetime.getForce(p)));
                }
                if (config.sizeOverLifetime.isEnable()) {
                    p.setQuadSize(config.sizeOverLifetime.getSize(startedSize, p, 0));
                }
                if (config.sizeBySpeed.isEnable()) {
                    p.setQuadSize(config.sizeBySpeed.getSize(startedSize, p));
                }
                if (config.physics.isEnable()) {
                    p.setFriction(config.physics.getFrictionModifier(p));
                    p.setGravity(config.physics.getGravityModifier(p));
                }
                if (config.noise.isEnable()) {
                    p.setPos(p.getPos(1).add(config.noise.getPosition(p, 0)), false);
                }

            });
        }


        if (config.colorOverLifetime.isEnable() || config.colorBySpeed.isEnable()) {
            particle.setDynamicColor((p, partialTicks) -> {
                float a = 1f;
                float r = 1f;
                float g = 1f;
                float b = 1f;
                if (config.colorOverLifetime.isEnable()) {
                    int color = config.colorOverLifetime.getColor(p, partialTicks);
                    a *= ColorUtils.alpha(color);
                    r *= ColorUtils.red(color);
                    g *= ColorUtils.green(color);
                    b *= ColorUtils.blue(color);
                }
                if (config.colorBySpeed.isEnable()) {
                    int color = config.colorBySpeed.getColor(p);
                    a *= ColorUtils.alpha(color);
                    r *= ColorUtils.red(color);
                    g *= ColorUtils.green(color);
                    b *= ColorUtils.blue(color);
                }
                return new Vector4f(r, g, b, a);
            });
        }

        if (config.sizeOverLifetime.isEnable() || config.noise.isEnable()) {
            particle.setDynamicSize((p, partialTicks) -> {
                var size = p.getQuadSize(partialTicks);
                if (config.sizeOverLifetime.isEnable()) {
                    size = config.sizeOverLifetime.getSize(startedSize, p, partialTicks);
                }
                if (config.noise.isEnable()) {
                    size = new Vector3(size).add(config.noise.getSize(p, partialTicks));
                }
                return size;
            });
        }

        if (config.rotationOverLifetime.isEnable() || config.rotationBySpeed.isEnable() || config.noise.isEnable()) {
            particle.setRotationAddition((p, partialTicks) -> {
                var addition = new Vector3(0, 0, 0);
                if (config.rotationOverLifetime.isEnable()) {
                    addition.add(config.rotationOverLifetime.getRotation(p, partialTicks));
                }
                if (config.rotationBySpeed.isEnable()) {
                    addition.add(config.rotationBySpeed.getRotation(p));
                }
                if (config.noise.isEnable()) {
                    addition.add(config.noise.getRotation(p, partialTicks));
                }
                return addition;
            });
        }

        if (config.noise.isEnable()) {
            particle.setPositionAddition((p, partialTicks) -> {
                if (config.noise.isEnable()) {
                    return config.noise.getPosition(p, partialTicks);
                }
                return new Vector3(0 ,0, 0);
            });
        }

        if (config.uvAnimation.isEnable()) {
            particle.setDynamicUVs((p, partialTicks) -> {
                if (config.uvAnimation.isEnable()) {
                    return config.uvAnimation.getUVs(p, partialTicks);
                }
                return new Vector4f(p.getU0(partialTicks), p.getV0(partialTicks), p.getU1(partialTicks), p.getV1(partialTicks));
            });
        }

        particle.setDynamicLight((p, partialTicks) -> {
            if (usingBloom()) {
                return LightTexture.FULL_BRIGHT;
            }
            if (config.lights.isEnable()) {
                return config.lights.getLight(p, partialTicks);
            }
            return p.getLight();
        });

        if (config.trails.isEnable()) {
            config.trails.setup(this, particle);
        }

        return particle;
    }

    @Override
    public void tick() {
        // effect first
        if (fXEffect != null && fXEffect.updateEmitter(this)) {
            return;
        }

        // delay
        if (delay > 0) {
            delay--;
            return;
        }

        // emit new particle
        if (!isRemoved() && getParticleAmount() < config.maxParticles) {
            var number = config.emission.getEmissionCount(this.age, t, getRandomSource());
            for (int i = 0; i < number; i++) {
                if (!emitParticle(createNewParticle())) {
                    break;
                }
            }
        }

        // particles life cycle
        for (var queue : particles.values()) {
            var iter = queue.iterator();
            while (iter.hasNext()) {
                var particle = iter.next();
                if (!particle.isAlive()) {
                    iter.remove();
                } else {
                    particle.tick();
                }
            }
        }

        if (this.age >= config.duration && !config.isLooping()) {
            this.remove();
        }

        this.age++;
        t = (this.age % config.duration) * 1f / config.duration;

    }

    @Override
    public int getLifetime() {
        return config.duration;
    }

    @Override
    public boolean isAlive() {
        return !removed || getParticleAmount() != 0;
    }

    @Override
    protected void updateOrigin() {
        super.updateOrigin();
        setLifetime(config.duration);
    }

    @Override
    public void resetParticle() {
        super.resetParticle();
        this.particles.clear();
    }

    @Override
    public void render(@NotNull VertexConsumer buffer, Camera camera, float pPartialTicks) {
        if (!ParticleQueueRenderType.INSTANCE.isRenderingQueue() && delay <= 0 && isVisible() && PhotonParticleRenderType.checkLayer(config.renderer.getLayer())) {
            for(var entry : this.particles.entrySet()) {
                var type = entry.getKey();
                if (type == ParticleRenderType.NO_RENDER) continue;
                var queue = entry.getValue();
                if (!queue.isEmpty()) {
                    ParticleQueueRenderType.INSTANCE.pipeQueue(type, queue, camera, pPartialTicks);
                }
            }
        }
    }

    @Override
    @Nonnull
    public final ParticleRenderType getRenderType() {
        return ParticleQueueRenderType.INSTANCE;
    }

    private static class RenderType extends PhotonParticleRenderType {
        protected final ParticleConfig config;
        public RenderType(ParticleConfig config) {
            this.config = config;
        }

        @Override
        public void begin(@Nonnull BufferBuilder bufferBuilder, @Nonnull TextureManager textureManager) {
            if (config.renderer.isBloomEffect()) {
                beginBloom();
            }
            config.material.pre();
            config.material.getMaterial().begin(bufferBuilder, textureManager, false);
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(@Nonnull Tesselator tesselator) {
            BlendMode lastBlend = null;
            if (RenderSystem.getShader() instanceof ShaderInstanceAccessor shader) {
                lastBlend = BlendModeAccessor.getLastApplied();
                BlendModeAccessor.setLastApplied(shader.getBlend());
            }
            tesselator.end();
            config.material.getMaterial().end(tesselator, false);
            config.material.post();
            if (lastBlend != null) {
                lastBlend.apply();
            }
            if (config.renderer.isBloomEffect()) {
                endBloom();
            }
        }

        @Override
        public int hashCode() {
            return config.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof RenderType renderType) {
                return renderType.config.equals(config);
            }
            return super.equals(obj);
        }
    }


    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    public boolean emitParticle(LParticle particle) {
        particle.prepareForEmitting(this);
        particles.computeIfAbsent(particle.getRenderType(), type -> new LinkedList<>()).add(particle);
        return getParticleAmount() <= config.maxParticles;
    }

    @Override
    public boolean usingBloom() {
        return config.renderer.isBloomEffect();
    }
}
