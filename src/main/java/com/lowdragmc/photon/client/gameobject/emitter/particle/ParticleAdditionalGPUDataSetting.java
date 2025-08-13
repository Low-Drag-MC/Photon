package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.util.TriConsumer;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.List;

public class ParticleAdditionalGPUDataSetting extends AdditionalGPUDataSetting {
    public record ParticleDataProvider(String name,
                                       Component tooltips,
                                       int size,
                                       TriConsumer<TileParticle, FloatBuffer, Float> uploader)
            implements AdditionalGPUDataSetting.DataProvider {
        @Override
        public int getSize() {
            return size;
        }

        @Override
        public void upload(IParticle particle, FloatBuffer buffer, float partialTicks) {
            uploader.accept((TileParticle) particle, buffer, partialTicks);
        }
    }
    private static final List<ParticleDataProvider> SUPPORTED_DATA_PROVIDERS = List.of(
            new ParticleDataProvider("addition_gpu_data.t",
                    Component.translatable("addition_gpu_data.t.tips")
                    .append(Component.translatable("addition_gpu_data.type.float")),
                    1, (particle, buffer, partialTick) -> buffer.put(particle.getT(partialTick))),
            new ParticleDataProvider("addition_gpu_data.age",
                    Component.translatable("addition_gpu_data.type.int"),
                    1, (particle, buffer, partialTick) -> buffer.put(Float.intBitsToFloat(particle.getAge()))),
            new ParticleDataProvider("addition_gpu_data.lifetime", Component.translatable("addition_gpu_data.type.int"),
                    1, (particle, buffer, partialTick) -> buffer.put(Float.intBitsToFloat(particle.getLifetime()))),
            new ParticleDataProvider("addition_gpu_data.position", Component.translatable("addition_gpu_data.type.vec3"),
                    3, (particle, buffer, partialTick) -> {
                var pos = particle.getLocalPos(partialTick);
                buffer.put(pos.x).put(pos.y).put(pos.z);
            }),
            new ParticleDataProvider("addition_gpu_data.velocity", Component.translatable("addition_gpu_data.type.vec3"),
                    3, (particle, buffer, partialTick) -> {
                var velocity = particle.getRealVelocity();
                buffer.put(velocity.x).put(velocity.y).put(velocity.z);
            }),
            new ParticleDataProvider("addition_gpu_data.isCollided", Component.translatable("addition_gpu_data.isCollided.tips")
                    .append(Component.translatable("addition_gpu_data.type.int")),
                    1, (particle, buffer, partialTick) -> buffer.put(Float.intBitsToFloat(particle.isCollided() ? 1 : 0))),
            new ParticleDataProvider("addition_gpu_data.emitter_t", Component.translatable("addition_gpu_data.type.float"),
                    1, (particle, buffer, partialTick) -> buffer.put(particle.getEmitter().getT(partialTick))),
            new ParticleDataProvider("addition_gpu_data.emitter_age", Component.translatable("addition_gpu_data.type.int"),
                    1, (particle, buffer, partialTick) -> buffer.put(Float.intBitsToFloat(particle.getEmitter().getAge()))),
            new ParticleDataProvider("addition_gpu_data.emitter_position", Component.translatable("addition_gpu_data.type.vec3"),
                    3, (particle, buffer, partialTick) -> {
                var position = particle.getEmitter().transform().position();
                buffer.put(position.x).put(position.y).put(position.z);
            }),
            new ParticleDataProvider("addition_gpu_data.emitter_velocity", Component.translatable("addition_gpu_data.type.vec3"),
                    3, (particle, buffer, partialTick) -> {
                var velocity = particle.getEmitter().getVelocity();
                buffer.put(velocity.x).put(velocity.y).put(velocity.z);
            })
    );

    private final ParticleConfig config;
    @Persisted
    private final Set<String> additionalData = new HashSet<>();
    // runtime
    private final List<ParticleDataProvider> dataProviders = new ArrayList<>();

    public ParticleAdditionalGPUDataSetting(ParticleConfig particleConfig) {
        super();
        this.config = particleConfig;
    }

    @Override
    public Collection<? extends DataProvider> getDataProviders() {
        return dataProviders;
    }

    @Override
    protected void onConfiguratorUpdate() {
        super.onConfiguratorUpdate();
        config.particleRenderType.clearInstance();
    }

    @Override
    public void afterDeserialize() {
        super.afterDeserialize();
        reloadDataProviders();
    }

    private void reloadDataProviders() {
        dataProviders.clear();
        for (var dataProvider : SUPPORTED_DATA_PROVIDERS) {
            if (additionalData.contains(dataProvider.name())) {
                dataProviders.add(dataProvider);
            }
        }
        config.particleRenderType.clearInstance();
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        for (var provider : SUPPORTED_DATA_PROVIDERS) {
            var configurator = new Configurator(provider.name).setTips(provider.tooltips);
            configurator.inlineContainer.addChild(new Toggle()
                    .setText("")
                    .setValue(additionalData.contains(provider.name), false)
                    .setOnToggleChanged(isOn -> {
                        if (isOn) additionalData.add(provider.name);
                        else additionalData.remove(provider.name);
                        reloadDataProviders();
                    })
                    .addEventListener(UIEvents.TICK, e -> {
                        var toggle = (Toggle) e.currentElement;
                        if (toggle.getValue() != additionalData.contains(provider.name)) {
                            toggle.setValue(additionalData.contains(provider.name), false);
                        }
                    })
            );
            father.addConfigurator(configurator);
        }
    }
}
