package com.lowdragmc.photon.client.gameobject.emitter.trail;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collections;

/**
 * @author KilaBash
 * @date 2023/6/6
 * @implNote TrailEmitter
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "trail_emitter", registry = "photon:fx_object")
public class TrailEmitter extends Emitter {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "trail");

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
    }


    @Override
    public IGuiTexture getIcon() {
        return ICON;
    }

    @Override
    public TrailEmitter shallowCopy() {
        return new TrailEmitter(config);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        var tag = super.serializeNBT(provider);
        tag.putInt("_version", VERSION);
        return tag;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        config.buildConfigurator(father);
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////

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

    @Override
    public int getParticleAmount() {
        return trailParticle.isAlive() ? 1 : 0;
    }

    @Override
    protected void update() {
        if (trailParticle.isAlive()) {
            trailParticle.updateTick();
        } else {
            remove();
        }

        super.update();
    }

    @Override
    public void reset() {
        super.reset();
        trailParticle = new TrailParticle(this, config);
    }

    @Override
    public boolean useTranslucentPipeline() {
        return config.renderer.getLayer() == RendererSetting.Layer.Translucent;
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {
        if (isVisible()) {
            buffer.pipeQueue(trailParticle.getRenderType(), Collections.singleton(trailParticle));
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
        trailParticle.setRemoved(true);
        super.remove(force);
        if (force) {
            trailParticle.getTails().clear();
        }
    }
}
