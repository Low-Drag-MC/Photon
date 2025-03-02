package com.lowdragmc.photon.client.gameobject.emitter.beam;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.ParticleQueueRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.PhotonParticleRenderType;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "beam", group = "emitter")
public class BeamEmitter extends Emitter {
    public static int VERSION = 2;

    @Getter
    @Persisted(subPersisted = true)
    protected final BeamConfig config;

    // runtime
    protected BeamParticle beamParticle;

    public BeamEmitter() {
        this(new BeamConfig());
        config.material.setMaterial(new TextureMaterial(new ResourceLocation("photon:textures/particle/laser.png")));
    }

    public BeamEmitter(BeamConfig config) {
        this.config = config;
        init();
    }

    public void init() {
        beamParticle = new BeamParticle(this, config, getRandomSource());
    }

    @Override
    public IParticleEmitter copy(boolean deep) {
        IParticleEmitter copied = deep ? (IParticleEmitter) super.copy(true) : new BeamEmitter(config);
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

    @Override
    public int getParticleAmount() {
        return beamParticle.isAlive() ? 1 : 0;
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
    public boolean isLooping() {
        return config.isLooping();
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////

    @Override
    protected void update() {
        if (beamParticle.isAlive()) {
            beamParticle.tick();
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
            ParticleQueueRenderType.INSTANCE.pipeQueue(beamParticle.getRenderType(), Collections.singleton(beamParticle), camera, pPartialTicks);
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
    public void remove(boolean force) {
        super.remove(force);
        beamParticle.setRemoved(true);
    }
}
