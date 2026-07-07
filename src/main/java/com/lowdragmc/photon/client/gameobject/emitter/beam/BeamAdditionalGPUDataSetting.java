package com.lowdragmc.photon.client.gameobject.emitter.beam;

import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.apache.logging.log4j.util.TriConsumer;

import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Beam bindings for the {@link PhotonGpuChannels} registry (kind BEAM). The derived channels
 * (beam_direction / beam_length) are computed in the shader from the base attributes and never
 * appear here.
 */
public class BeamAdditionalGPUDataSetting extends AdditionalGPUDataSetting {

    private static final Map<String, TriConsumer<BeamParticle, FloatBuffer, Float>> UPLOADERS = Map.ofEntries(
            Map.entry("addition_gpu_data.random",
                    (particle, buffer, partialTick) -> buffer.put(particle.getMemRandom("instance_random"))),
            Map.entry("addition_gpu_data.t",
                    (particle, buffer, partialTick) -> buffer.put(particle.getT(partialTick))),
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

    private final BeamConfig config;
    @Persisted
    private final Set<String> additionalData = new HashSet<>();

    public BeamAdditionalGPUDataSetting(BeamConfig config) {
        super();
        this.config = config;
    }

    @Override
    public PhotonGpuChannels.Kind kind() {
        return PhotonGpuChannels.Kind.BEAM;
    }

    @Override
    protected Set<String> enabledChannelIds() {
        return additionalData;
    }

    @Override
    protected void uploadChannel(PhotonGpuChannels.Channel channel, IParticle particle, FloatBuffer target, float partialTicks) {
        var uploader = UPLOADERS.get(channel.id());
        if (uploader != null) {
            uploader.accept((BeamParticle) particle, target, partialTicks);
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
