package com.lowdragmc.photon.client.gameobject.emitter.trail;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.ParticleQueueRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collections;
import java.util.HashMap;

/**
 * @author KilaBash
 * @date 2023/6/6
 * @implNote TrailEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "trail", group = "emitter")
public class TrailEmitter extends Emitter {
    public static int VERSION = 2;

    @Persisted(subPersisted = true)
    public final TrailConfig config;

    // runtime
    protected TrailParticle trailParticle;

    public TrailEmitter() {
        this(new TrailConfig());
        config.smoothInterpolation = true;
        config.minVertexDistance = 0.1f;
    }

    public TrailEmitter(TrailConfig config) {
        this.config = config;
        this.lifetime = -1;
        init();
    }

    public void init() {
        trailParticle = new TrailParticle(this, config, getRandomSource());
    }

    @Override
    public TrailEmitter shallowCopy() {
        return new TrailEmitter(config);
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

    @Override
    public int getParticleAmount() {
        return trailParticle.isAlive() ? 1 : 0;
    }

    @Override
    protected void update() {
        if (trailParticle.isAlive()) {
            trailParticle.tick();
        } else {
            remove();
        }

        super.update();
    }

    @Override
    public void reset() {
        super.reset();
        init();
    }

    @Override
    public void render(@NotNull VertexConsumer buffer, Camera camera, float pPartialTicks) {
        super.render(buffer, camera, pPartialTicks);
        if (!ParticleQueueRenderType.INSTANCE.isRenderingQueue() && delay <= 0 && isVisible() &&
                PhotonParticleRenderType.checkLayer(config.renderer.getLayer()) &&
                (!config.renderer.getCull().isEnable() ||
                        PhotonParticleRenderType.checkFrustum(config.renderer.getCull().getCullAABB(this, pPartialTicks)))) {
            ParticleQueueRenderType.INSTANCE.pipeQueue(trailParticle.getRenderType(), Collections.singleton(trailParticle), camera, pPartialTicks);
        }
    }

    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    @Override
    @Nullable
    public AABB getCullBox(float partialTicks) {
        return config.renderer.getCull().isEnable() ? config.renderer.getCull().getCullAABB(this, partialTicks) : null;
    }

    @Override
    public boolean isLooping() {
        return true;
    }

    @Override
    public void remove(boolean force) {
        trailParticle.setRemoved(true);
        super.remove(force);
        if (force) {
            trailParticle.getTails().clear();
        }
    }
}
