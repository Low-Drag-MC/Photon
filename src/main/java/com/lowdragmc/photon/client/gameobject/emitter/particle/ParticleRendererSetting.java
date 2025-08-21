package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.client.renderer.impl.IModelRenderer;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.accessors.Vector3fAccessor;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;

@Getter
@Setter
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ParticleRendererSetting extends RendererSetting implements IConfigurable, IPersistedSerializable {

    public enum Mode {
        Billboard((p, c, t) -> c.rotation()),
        Horizontal(0, -90),
        Vertical(0, 0),
        VerticalBillboard((p, c, t) -> {
            var quaternion = new Quaternionf();
            quaternion.rotateY((float) Math.toRadians(180 - c.getYRot()));
            return quaternion;
        }),
        Model((p, c, t) -> new Quaternionf());

        public final TriFunction<TileParticle, Camera, Float, Quaternionf> quaternion;

        Mode(TriFunction<TileParticle, Camera, Float, Quaternionf> quaternion) {
            this.quaternion = quaternion;
        }

        Mode(Quaternionf quaternion) {
            this.quaternion = (p, c, t) -> quaternion;
        }

        Mode(float yRot, float xRot) {
            var quaternion = new Quaternionf();
            quaternion.rotateY((float) Math.toRadians(yRot));
            quaternion.rotateX((float) Math.toRadians(xRot));
            this.quaternion = (p, c, t) -> quaternion;
        }
    }

    private final ParticleConfig config;
    @Configurable(name = "ParticleRendererSetting.renderMode", tips = "photon.emitter.config.renderer.renderMode")
    @ConfigSelector(subConfiguratorBuilder = "buildSubConfigurator")
    @EqualsAndHashCode.Include
    protected Mode renderMode = Mode.Billboard;
    @Nullable
    @EqualsAndHashCode.Include
    protected IModelRenderer model;
    @Persisted
    @EqualsAndHashCode.Include
    protected boolean shade = true;
    @Persisted
    @EqualsAndHashCode.Include
    protected boolean useBlockUV = true;
    @Persisted
    @EqualsAndHashCode.Include
    protected Vector3f modelPivot = new Vector3f();
    @Configurable(name = "ParticleRendererSetting.useGPUInstance")
    @EqualsAndHashCode.Include
    private boolean useGPUInstance = false;

    public ParticleRendererSetting(ParticleConfig config) {
        this.config = config;
    }

    public void buildSubConfigurator(Mode mode, ConfiguratorGroup group) {
        if (mode == Mode.Model) {
            getModel().buildConfigurator(group);
            group.addConfigurators(
                    new BooleanConfigurator("shade", this::isShade, this::setShade, true, true)
                            .setTips("photon.emitter.config.renderer.renderMode.model.shade"),
                    new BooleanConfigurator("useBlockUV", this::isUseBlockUV, this::setUseBlockUV, true, true)
                            .setTips("photon.emitter.config.renderer.renderMode.model.useBlockUV"),
                    new Vector3fAccessor().create("modelPivot", this::getModelPivot, this::setModelPivot,
                            true, getModelPivotField(), this)
                            .setTips("photon.emitter.config.renderer.renderMode.model.modelPivot")
                    );
        }
    }

    private Field getModelPivotField() {
        try {
            return getClass().getDeclaredField("modelPivot");
        } catch (Exception e) {
            Photon.LOGGER.error("Error getting modelPivot field", e);
            throw new RuntimeException(e);
        }
    }

    public IModelRenderer getModel() {
        if (model == null) {
            model = new IModelRenderer(ResourceLocation.parse("block/dirt"));
        }
        return model;
    }

    @ConfigSetter(field = "renderMode")
    public void setRenderMode(Mode mode) {
        this.renderMode = mode;
        config.particleRenderType.clearInstance();
    }

    public void setModel(IModelRenderer model) {
        this.model = model;
        config.particleRenderType.clearInstance();
    }

    public void setShade(boolean shade) {
        this.shade = shade;
        config.particleRenderType.clearInstance();
    }

    public void setUseBlockUV(boolean useBlockUV) {
        this.useBlockUV = useBlockUV;
        config.particleRenderType.clearInstance();
    }

    public void setModelPivot(Vector3f modelPivot) {
        this.modelPivot = modelPivot;
        config.particleRenderType.clearInstance();
    }

    @Override
    public void deserializeNBT(HolderLookup.@NotNull Provider provider, @NotNull CompoundTag tag) {
        IPersistedSerializable.super.deserializeNBT(provider, tag);
        if (renderMode == Mode.Model) {
            if (model == null) {
                model = new IModelRenderer(ResourceLocation.parse("block/dirt"));
            }
            model.deserializeNBT(provider, tag.getCompound("model"));
        }
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        var tag = IPersistedSerializable.super.serializeNBT(provider);
        if (renderMode == Mode.Model && model != null) {
            tag.put("model", getModel().serializeNBT(provider));
        }
        return tag;
    }
}
