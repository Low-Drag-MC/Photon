package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.google.common.collect.Queues;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.ParticleQueueRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;

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
@LDLRegisterClient(name = "particle", group = "emitter")
public class ParticleEmitter extends Emitter {
    public static int VERSION = 2;

    @Persisted(subPersisted = true)
    public final ParticleConfig config;

    // runtime
    @Getter
    protected final Map<PhotonParticleRenderType, Queue<IParticle>> particles = new LinkedHashMap<>();
    public final Queue<IParticle> waitToAdded = Queues.newArrayDeque();

    public ParticleEmitter() {
        this(new ParticleConfig());
    }

    public ParticleEmitter(ParticleConfig config) {
        this.config = config;
    }

    @Override
    public IParticleEmitter copy(boolean deep) {
        var copied = deep ? (IParticleEmitter) super.copy(true) : new ParticleEmitter(config);
        copied.setName(name);
        return copied;
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = super.serializeNBT();
        tag.putInt("_version", VERSION);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        var version = tag.contains("_version") ? tag.getInt("_version") : 0;
        // legacy version
        if (version < 1) {
            var configTag = tag;
            tag = new CompoundTag();
            tag.put("config", configTag);
            tag.putString("name", configTag.getString("name"));
        }
        super.deserializeNBT(tag);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        ConfiguratorParser.createConfigurators(father, new HashMap<>(), config.getClass(), config);
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////
    protected TileParticle createNewParticle() {
        return new TileParticle(this, config, getRandomSource());
    }

    @Override
    public void update() {
        // emit new particle
        var available = config.maxParticles - getParticleAmount();
        if (!removed && getParticleAmount() < config.maxParticles) {
            available = Math.min(config.emission.getEmissionCount(this.age, t, getRandomSource()), available);
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
                queue.parallelStream().forEach(IParticle::tick);
            } else {
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
        }

        super.update();
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
    }

    @Override
    public void render(@Nonnull VertexConsumer buffer, Camera camera, float pPartialTicks) {
        super.render(buffer, camera, pPartialTicks);
        if (!ParticleQueueRenderType.INSTANCE.isRenderingQueue() && delay <= 0 && isVisible() &&
                PhotonParticleRenderType.checkLayer(config.renderer.getLayer()) &&
                (!config.renderer.getCull().isEnable() ||
                        PhotonParticleRenderType.checkFrustum(config.renderer.getCull().getCullAABB(this, pPartialTicks)))) {
            for(var entry : this.particles.entrySet()) {
                var type = entry.getKey();
                if (type == ParticleRenderType.NO_RENDER) continue;
                var queue = entry.getValue();
                if (type == ParticleQueueRenderType.INSTANCE) {
                    // TODO sub emitters
                    for (var emitter : queue) {
                        emitter.render(buffer, camera, pPartialTicks);
                    }
                } else if (!queue.isEmpty()) {
                    ParticleQueueRenderType.INSTANCE.pipeQueue(type, queue, camera, pPartialTicks);
                }
            }
        }
    }


    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    @Override
    public int getParticleAmount() {
        var sum = 0;
        for (var entry : getParticles().entrySet()) {
            if (entry.getKey() == ParticleQueueRenderType.INSTANCE) {
                for (var particle : entry.getValue()) {
                    sum += ((IParticleEmitter) particle).getParticleAmount();
                }
            }
            sum += entry.getValue().size();
        }
        return sum + waitToAdded.size();
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
}
