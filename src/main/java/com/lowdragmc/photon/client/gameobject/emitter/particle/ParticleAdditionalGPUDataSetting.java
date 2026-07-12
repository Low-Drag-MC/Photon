package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomData;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.apache.logging.log4j.util.TriConsumer;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tile-particle bindings for the {@link PhotonGpuChannels} registry (kinds TILE / TILE_MODEL).
 */
public class ParticleAdditionalGPUDataSetting extends AdditionalGPUDataSetting {

    private static final Map<String, TriConsumer<TileParticle, FloatBuffer, Float>> UPLOADERS = Map.ofEntries(
            Map.entry("addition_gpu_data.random",
                    (particle, buffer, partialTick) -> buffer.put(particle.getMemRandom("instance_random"))),
            Map.entry("addition_gpu_data.t",
                    (particle, buffer, partialTick) -> buffer.put(particle.getT(partialTick))),
            Map.entry("addition_gpu_data.age",
                    (particle, buffer, partialTick) -> buffer.put(particle.getAge())),
            Map.entry("addition_gpu_data.lifetime",
                    (particle, buffer, partialTick) -> buffer.put((float) particle.getLifetime())),
            Map.entry("addition_gpu_data.position", (particle, buffer, partialTick) -> {
                var pos = particle.getLocalPos(partialTick);
                buffer.put(pos.x).put(pos.y).put(pos.z);
            }),
            Map.entry("addition_gpu_data.velocity", (particle, buffer, partialTick) -> {
                var velocity = particle.getRealVelocity();
                buffer.put(velocity.x).put(velocity.y).put(velocity.z);
            }),
            Map.entry("addition_gpu_data.isCollided",
                    (particle, buffer, partialTick) -> buffer.put(particle.isCollided() ? 1f : 0f)),
            Map.entry("addition_gpu_data.emitter_t",
                    (particle, buffer, partialTick) -> buffer.put(particle.getEmitter().getT(partialTick))),
            Map.entry("addition_gpu_data.emitter_age",
                    (particle, buffer, partialTick) -> buffer.put((float) particle.getEmitter().getAge())),
            Map.entry("addition_gpu_data.emitter_position", (particle, buffer, partialTick) -> {
                var position = particle.getEmitter().transform().position();
                buffer.put(position.x).put(position.y).put(position.z);
            }),
            Map.entry("addition_gpu_data.emitter_velocity", (particle, buffer, partialTick) -> {
                var velocity = particle.getEmitter().getVelocity();
                buffer.put(velocity.x).put(velocity.y).put(velocity.z);
            })
    );

    private final ParticleConfig config;
    @Persisted
    private final Set<String> additionalData = new HashSet<>();
    /** User custom-data streams; persisted manually by {@link AdditionalGPUDataSetting} via NBT. */
    private final List<CustomData> customData = new ArrayList<>();

    public ParticleAdditionalGPUDataSetting(ParticleConfig particleConfig) {
        super();
        this.config = particleConfig;
    }

    @Override
    public PhotonGpuChannels.Kind kind() {
        return config.renderer.getRenderMode() == ParticleRendererSetting.Mode.Model
                ? PhotonGpuChannels.Kind.TILE_MODEL
                : PhotonGpuChannels.Kind.TILE;
    }

    @Override
    protected Set<String> enabledChannelIds() {
        return additionalData;
    }

    @Override
    protected List<CustomData> customDataList() {
        return customData;
    }

    @Override
    protected boolean supportsCustomData() {
        return true;
    }

    @Override
    protected void uploadChannel(PhotonGpuChannels.Channel channel, IParticle particle, FloatBuffer target, float partialTicks) {
        var uploader = UPLOADERS.get(channel.id());
        if (uploader != null) {
            uploader.accept((TileParticle) particle, target, partialTicks);
        }
    }

    @Override
    protected void onChannelsChanged() {
        config.particleRenderType.clearInstance();
    }

    @Override
    protected void onConfiguratorUpdate() {
        super.onConfiguratorUpdate();
        config.particleRenderType.clearInstance();
    }

    @Override
    public void afterDeserialize() {
        super.afterDeserialize();
        config.particleRenderType.clearInstance();
    }
}
