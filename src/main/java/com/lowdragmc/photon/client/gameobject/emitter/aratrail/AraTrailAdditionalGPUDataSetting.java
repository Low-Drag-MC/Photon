package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomData;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.aratrail.AraTrailParticle;
import org.apache.logging.log4j.util.TriConsumer;
import org.jetbrains.annotations.Nullable;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AraTrail bindings for the {@link PhotonGpuChannels} registry (kind ARA_TRAIL). Per-point
 * channels (point_t = head 1 / tail 0 along the arc length, point_life = normalized segment age)
 * carry the value at the segment's curr and next points, staged per instance by the renderer via
 * {@link #setSegmentValues} right before {@link #uploadDataRecord}.
 */
public class AraTrailAdditionalGPUDataSetting extends AdditionalGPUDataSetting {

    private static final Map<String, TriConsumer<AraTrailParticle, FloatBuffer, Float>> UPLOADERS = Map.ofEntries(
            Map.entry("addition_gpu_data.random",
                    (particle, buffer, partialTick) -> buffer.put(particle.getMemRandom("instance_random"))),
            Map.entry("addition_gpu_data.t",
                    (particle, buffer, partialTick) -> buffer.put(particle.getT(partialTick))),
            Map.entry("addition_gpu_data.emitter_t",
                    (particle, buffer, partialTick) -> buffer.put(particle.emitter.getT(partialTick))),
            Map.entry("addition_gpu_data.emitter_age",
                    (particle, buffer, partialTick) -> buffer.put((float) particle.emitter.getAge())),
            Map.entry("addition_gpu_data.emitter_position", (particle, buffer, partialTick) -> {
                var position = particle.emitter.transform().position();
                buffer.put(position.x).put(position.y).put(position.z);
            }),
            Map.entry("addition_gpu_data.emitter_velocity", (particle, buffer, partialTick) -> {
                var velocity = particle.emitter.getVelocity();
                buffer.put(velocity.x).put(velocity.y).put(velocity.z);
            })
    );

    private final AraTrailConfig config;
    @Persisted
    private final Set<String> additionalData = new HashSet<>();
    /** User custom-data streams; persisted by base {@link AdditionalGPUDataSetting} via NBT. */
    private final List<CustomData> customData = new ArrayList<>();

    // per-segment staging (render thread only), set by the renderer before each uploadData
    private float pointTCurr, pointTNext, pointLifeCurr, pointLifeNext;

    public AraTrailAdditionalGPUDataSetting(AraTrailConfig config) {
        super();
        this.config = config;
    }

    /** Stages the per-point channel values of the segment about to be uploaded. */
    public void setSegmentValues(float pointTCurr, float pointTNext, float pointLifeCurr, float pointLifeNext) {
        this.pointTCurr = pointTCurr;
        this.pointTNext = pointTNext;
        this.pointLifeCurr = pointLifeCurr;
        this.pointLifeNext = pointLifeNext;
    }

    @Override
    public PhotonGpuChannels.Kind kind() {
        return PhotonGpuChannels.Kind.ARA_TRAIL;
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
    protected Set<CustomData.TSource> availableTSources() {
        return EnumSet.of(CustomData.TSource.EMITTER, CustomData.TSource.SELF, CustomData.TSource.LENGTH);
    }

    @Nullable
    @Override
    protected CustomData.ChannelResolver customResolver(IParticle particle, int streamIndex) {
        if (particle instanceof AraTrailParticle araParticle) {
            var cd = araParticle.runtime.customData;
            if (cd.hasOverride(streamIndex)) {
                return cd.resolverFor(streamIndex);
            }
        }
        return null;
    }

    @Override
    protected float customSampleT(IParticle particle, CustomData.TSource source, float partialTicks) {
        // custom-data is per-segment (= per instance); SELF/LENGTH read the segment values staged by
        // setSegmentValues (curr endpoint = the segment's representative t)
        return switch (source) {
            case EMITTER -> particle instanceof AraTrailParticle ap ? ap.emitter.getT(partialTicks) : particle.getT(partialTicks);
            case SELF -> pointLifeCurr;
            case LENGTH -> pointTCurr;
        };
    }

    @Override
    protected void uploadChannel(PhotonGpuChannels.Channel channel, IParticle particle, FloatBuffer target, float partialTicks) {
        switch (channel.id()) {
            case "addition_gpu_data.point_t" -> target.put(pointTCurr).put(pointTNext);
            case "addition_gpu_data.point_life" -> target.put(pointLifeCurr).put(pointLifeNext);
            default -> {
                var uploader = UPLOADERS.get(channel.id());
                if (uploader != null) {
                    uploader.accept((AraTrailParticle) particle, target, partialTicks);
                }
            }
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
