package com.lowdragmc.photon.client.emitter.beam;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.emitter.ParticleQueueRenderType;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.fx.IEffect;
import com.lowdragmc.photon.client.particle.BeamParticle;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.core.mixins.accessor.BlendModeAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "beam", group = "emitter")
public class BeamEmitter extends BeamParticle implements IParticleEmitter {
    public static int VERSION = 1;

    @Setter
    @Getter
    @Persisted
    protected String name = "beam emitter";
    @Getter
    @Persisted(subPersisted = true)
    protected final BeamConfig config;
    protected final PhotonParticleRenderType renderType;

    // runtime
    @Getter
    protected final Map<PhotonParticleRenderType, Queue<LParticle>> particles;
    @Getter @Setter
    protected boolean visible = true;
    @Nullable
    @Getter @Setter
    protected IEffect effect;

    public BeamEmitter() {
        this(new BeamConfig());
        config.material.setMaterial(new TextureMaterial(new ResourceLocation("photon:textures/particle/laser.png")));
    }

    public BeamEmitter(BeamConfig config) {
        super(null, new Vector3f(0, 0, 0), new Vector3f(3, 0, 0));
        this.config = config;
        this.renderType = new RenderType(config);
        this.particles = Map.of(renderType, new ArrayDeque<>(1));
        init();
    }

    @Override
    public IParticleEmitter copy(boolean deep) {
        if (deep) {
            return IParticleEmitter.super.copy();
        }
        return new BeamEmitter(config);
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
        IParticleEmitter.super.deserializeNBT(tag);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        ConfiguratorParser.createConfigurators(father, new HashMap<>(), config.getClass(), config);
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////
    public void init() {
        particles.get(renderType).clear();
        particles.get(renderType).add(this);
        super.setDelay(config.startDelay);
        super.setDynamicLight((p, partialTicks) -> {
            if (usingBloom()) {
                return LightTexture.FULL_BRIGHT;
            }
            if (config.lights.isEnable()) {
                return config.lights.getLight(p, partialTicks);
            }
            return p.getLight(partialTicks);
        });
        super.setDynamicColor((p, partialTicks) -> {
            int color = config.color.get(p.getT(partialTicks), () -> p.getMemRandom("color")).intValue();
            return new Vector4f(ColorUtils.red(color), ColorUtils.green(color), ColorUtils.blue(color), ColorUtils.alpha(color));
        });
        super.setDynamicWidth((p, partialTicks) -> config.width.get(p.getT(partialTicks), () -> p.getMemRandom("width")).floatValue());
        super.setDynamicEmit((p, partialTicks) -> config.emitRate.get(p.getT(partialTicks), () -> p.getMemRandom("emit")).floatValue());
    }

    @Override
    public int getLifetime() {
        return this.config.duration;
    }

    @Override
    protected void updateOrigin() {
        super.updateOrigin();
        setLifetime(config.duration);
    }

    @Override
    public void resetParticle() {
        super.resetParticle();
        init();
    }

    @Override
    public void tick() {
        // effect first
        if (effect != null && effect.updateEmitter(this)) {
            return;
        }

        config.renderer.setupQuaternion(this, this);

        if (!config.end.equals(this.end)) {
            this.end = config.end;
        }

        if (delay > 0) {
            delay--;
            return;
        }

        updateOrigin();

        if (this.age >= config.duration && !config.isLooping()) {
            this.remove();
        }

        update();

        this.age++;
        t = (this.age % config.duration) * 1f / config.duration;
    }

    @Override
    public void render(@NotNull VertexConsumer pBuffer, Camera pRenderInfo, float pPartialTicks) {
        if (ParticleQueueRenderType.INSTANCE.isRenderingQueue()) {
            super.render(pBuffer, pRenderInfo, pPartialTicks);
        } else if (delay <= 0 && isVisible() &&
                PhotonParticleRenderType.checkLayer(config.renderer.getLayer())  &&
                PhotonParticleRenderType.checkFrustum(getCullBox(pPartialTicks))) {
            ParticleQueueRenderType.INSTANCE.pipeQueue(renderType, particles.get(renderType), pRenderInfo, pPartialTicks);
        }
    }

    @Override
    @Nonnull
    public final PhotonParticleRenderType getRenderType() {
        return ParticleQueueRenderType.INSTANCE;
    }

    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    @Override
    public boolean emitParticle(LParticle particle) {
        return false;
    }

    @Override
    @Nonnull
    public AABB getCullBox(float partialTicks) {
        return new AABB(new Vec3(getPos(partialTicks)), new Vec3(end)).inflate(getWidth(partialTicks));
    }

    @Override
    public boolean usingBloom() {
        return config.renderer.isBloomEffect();
    }

    private static class RenderType extends PhotonParticleRenderType {
        protected final BeamConfig config;
        private BlendMode lastBlend = null;


        public RenderType(BeamConfig config) {
            this.config = config;
        }

        @Override
        public void prepareStatus() {
            if (config.renderer.isBloomEffect()) {
                beginBloom();
            }
            config.material.pre();
            config.material.getMaterial().begin(false);
            if (RenderSystem.getShader() instanceof ShaderInstanceAccessor shader) {
                lastBlend = BlendModeAccessor.getLastApplied();
                BlendModeAccessor.setLastApplied(shader.getBlend());
            }
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        }

        @Override
        public void begin(@Nonnull BufferBuilder bufferBuilder) {
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void releaseStatus() {
            config.material.getMaterial().end(false);
            config.material.post();
            if (lastBlend != null) {
                lastBlend.apply();
                lastBlend = null;
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
            if (obj instanceof RenderType type) {
                return type.config.equals(config);
            }
            return super.equals(obj);
        }
    }
}
