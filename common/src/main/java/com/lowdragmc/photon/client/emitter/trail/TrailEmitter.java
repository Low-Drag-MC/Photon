package com.lowdragmc.photon.client.emitter.trail;

import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.gui.editor.runtime.PersistedParser;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.emitter.ParticleQueueRenderType;
import com.lowdragmc.photon.client.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.fx.IEffect;
import com.mojang.math.Vector4f;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import com.lowdragmc.photon.client.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.fx.IFXEffect;
import com.lowdragmc.photon.client.particle.LParticle;
import com.lowdragmc.photon.client.particle.TrailParticle;
import com.lowdragmc.photon.core.mixins.accessor.BlendModeAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author KilaBash
 * @date 2023/6/6
 * @implNote TrailEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "trail", group = "emitter")
public class TrailEmitter extends TrailParticle implements IParticleEmitter {
    public static int VERSION = 1;

    @Setter
    @Getter
    @Persisted
    protected String name = "trail emitter";
    @Getter
    @Persisted(subPersisted = true)
    protected final TrailConfig config;
    protected final PhotonParticleRenderType renderType;

    // runtime
    @Getter
    protected final Map<PhotonParticleRenderType, Queue<LParticle>> particles;
    @Getter @Setter
    protected boolean visible = true;
    @Nullable
    @Getter @Setter
    protected IEffect effect;
    protected LinkedList<AtomicInteger> tailsTime = new LinkedList<>();

    public TrailEmitter() {
        this(new TrailConfig());
        config.material.setMaterial(new CustomShaderMaterial());
    }

    public TrailEmitter(TrailConfig config) {
        super(null, 0, 0, 0);
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
        return new TrailEmitter(config);
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

    public void init() {
        particles.get(renderType).clear();
        particles.get(renderType).add(this);
        super.setLifetime(-1);
        config.renderer.setupQuaternion(this, this);
        super.setUvMode(config.uvMode);
        super.setMinimumVertexDistance(config.minVertexDistance);
        super.setOnRemoveTails(t -> {
            var iterT = tailsTime.iterator();
            var iter = tails.iterator();
            while (iter.hasNext() && iterT.hasNext()) {
                var tailTime = iterT.next();
                iter.next();
                if (tailTime.getAndAdd(1) > config.time) {
                    iterT.remove();
                    iter.remove();
                }
            }
            return true;
        });
        super.setDieWhenRemoved(false);
        super.setDynamicTailColor((t, tail, partialTicks) -> {
            int color = config.colorOverTrail.get(tail / (t.getTails().size() - 1f), () -> t.getMemRandom("trails-colorOverTrail")).intValue();
            return new Vector4f(ColorUtils.red(color), ColorUtils.green(color), ColorUtils.blue(color), ColorUtils.alpha(color));
        });
        super.setDynamicTailWidth((t, tail, partialTicks) -> 0.2f * config.widthOverTrail.get(tail / (t.getTails().size() - 1f), () -> t.getMemRandom("trails-widthOverTrail")).floatValue());
        super.setDynamicLight((t, partialTicks) -> {
            if (usingBloom()) {
                return LightTexture.FULL_BRIGHT;
            }
            if (config.lights.isEnable()) {
                return config.lights.getLight(t, partialTicks);
            }
            return t.getLight(partialTicks);
        });
    }

    @Override
    protected void update() {
        // effect first
        if (effect != null && effect.updateEmitter(this)) {
            return;
        }
        if (effect == null) {
            config.renderer.setupQuaternion(this, this);
            setUvMode(config.uvMode);
        }
        super.update();
    }

    @Override
    protected void addNewTail(Vector3 tail) {
        super.addNewTail(tail);
        tailsTime.addLast(new AtomicInteger(0));
    }

    @Override
    @ConfigSetter(field = "minVertexDistance")
    public void setMinimumVertexDistance(float minimumVertexDistance) {
        super.setMinimumVertexDistance(minimumVertexDistance);
        config.minVertexDistance = minimumVertexDistance;
    }

    @Override
    public float getT(float partialTicks) {
        return 1;
    }

    @Override
    public int getAge() {
        return 0;
    }

    @Override
    public void resetParticle() {
        super.resetParticle();
        this.tailsTime.clear();
    }

    @Override
    public void render(@NotNull VertexConsumer pBuffer, Camera pRenderInfo, float pPartialTicks) {
        if (ParticleQueueRenderType.INSTANCE.isRenderingQueue()) {
            super.render(pBuffer, pRenderInfo, pPartialTicks);
        } else if (delay <= 0 && isVisible() &&
                PhotonParticleRenderType.checkLayer(config.renderer.getLayer())  &&
                (!config.renderer.getCull().isEnable() ||
                        PhotonParticleRenderType.checkFrustum(config.renderer.getCull().getCullAABB(this, pPartialTicks)))) {
            ParticleQueueRenderType.INSTANCE.pipeQueue(renderType, particles.get(renderType), pRenderInfo, pPartialTicks);
        }
    }

    @Override
    @Nonnull
    public PhotonParticleRenderType getRenderType() {
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
    @Nullable
    public AABB getCullBox(float partialTicks) {
        return config.renderer.getCull().isEnable() ? config.renderer.getCull().getCullAABB(this, partialTicks) : null;
    }

    @Override
    public boolean usingBloom() {
        return config.renderer.isBloomEffect();
    }

    @Override
    public void remove(boolean force) {
        remove();
        if (force) {
            dieWhenRemoved = true;
        }
    }

    private static class RenderType extends PhotonParticleRenderType {
        protected final TrailConfig config;
        private BlendMode lastBlend = null;


        public RenderType(TrailConfig config) {
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
            bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.PARTICLE);
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
        public boolean isParallel() {
            return config.isParallelRendering();
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
