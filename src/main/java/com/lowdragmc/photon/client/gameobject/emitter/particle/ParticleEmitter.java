package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.google.common.collect.Queues;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/**
 * @author KilaBash
 * @date 2023/5/25
 * @implNote ParticleEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "particle_emitter", registry = "photon:fx_object")
public class ParticleEmitter extends Emitter {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "particle");
    public static int VERSION = 2;

    @Persisted(subPersisted = true)
    public final ParticleConfig config;

    // runtime
    protected boolean hasFirstUpdate = false;
    @Getter @Setter
    protected float accumulatedDistance = 0;
    @Getter
    protected final Map<PhotonFXRenderPass, Queue<IParticle>> particles = new LinkedHashMap<>();
    public final Queue<IParticle> waitToAdded = Queues.newArrayDeque();

    public ParticleEmitter() {
        this(new ParticleConfig());
    }

    protected ParticleEmitter(ParticleConfig config) {
        this.config = config;
    }

    @Override
    public IGuiTexture getIcon() {
        return ICON;
    }

    @Override
    public ParticleEmitter shallowCopy() {
        return new ParticleEmitter(config);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        var tag = super.serializeNBT(provider);
        tag.putInt("version", VERSION);
        return tag;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        config.buildConfigurator(father);
    }

    protected TileParticle createNewParticle() {
        return new TileParticle(this, config);
    }

    @Override
    public void update() {
        if (!hasFirstUpdate) {
            hasFirstUpdate = true;
            if (config.prewarm > 0) {
                for (int i = 0; i < config.prewarm; i++) {
                    emitParticle();
                    super.update();
                    if (removed) {
                        return;
                    }
                }
            }
        }
        emitParticle();
        super.update();
    }

    public void emitParticle() {
        // calculate distance
        accumulatedDistance += getVelocity().length();
        // emit new particle
        var available = config.maxParticles - getParticleAmount();
        if (!removed && getParticleAmount() < config.maxParticles) {
            available = Math.min(config.emission.getEmissionCount(this, getRandomSource()), available);
            for (int i = 0; i < available; i++) {
                emitParticle(createNewParticle());
            }
        }

        // particles life cycle
        if (!waitToAdded.isEmpty()) {
            for (var p : waitToAdded) {
                particles.computeIfAbsent(p.getRenderType(), type -> new ArrayDeque<>(config.maxParticles)).add(p);
            }
            waitToAdded.clear();
        }

        for (var queue : particles.values()) {
            if (config.parallelUpdate && (!config.physics.isEnable() || !config.physics.isHasCollision())) { // parallel stream for particles tick.
                queue.removeIf(p -> !p.isAlive());
                queue.parallelStream().forEach(IParticle::updateTick);
            } else {
                var iter = queue.iterator();
                while (iter.hasNext()) {
                    var particle = iter.next();
                    if (!particle.isAlive()) {
                        iter.remove();
                    } else {
                        particle.updateTick();
                    }
                }
            }
        }
    }

    @Override
    public boolean isLooping() {
        return config.isLooping();
    }

    public void emitParticle(IParticle particle) {
        waitToAdded.add(particle);
    }

    @Override
    public int getLifetime() {
        return config.duration;
    }

    @Override
    protected void updateOrigin() {
        super.updateOrigin();
        setLifetime(config.duration);
    }

    @Override
    public void reset() {
        super.reset();
        this.particles.clear();
        this.hasFirstUpdate = false;
    }

    @Override
    public boolean useTranslucentPipeline() {
        return config.renderer.getLayer() == RendererSetting.Layer.Translucent;
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {
        if (isVisible()) {
            for(var entry : this.particles.entrySet()) {
                var pass = entry.getKey();
                var queue = entry.getValue();
                if (!queue.isEmpty()) {
                    buffer.pipeQueue(pass, queue);
                }
            }
        }
    }

    @Override
    public int getParticleAmount() {
        return getParticles().values().stream().mapToInt(Collection::size).sum() + waitToAdded.size();
    }

    @Override
    @Nullable
    public AABB getCullBox(float partialTicks) {
        return config.renderer.getCull().isEnable() ? config.renderer.getCull().getCullAABB(this, partialTicks) : null;
    }

    @Override
    public void remove(boolean force) {
        super.remove(force);
        if (force) {
            particles.clear();
        }
    }

    @Override
    public void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {
        if(scene.sceneView().isShapeVisible()) {
            config.shape.drawGuideLines(bufferSource, partialTicks, this);
        }
    }
}
