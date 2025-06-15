package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.client.renderer.impl.IModelRenderer;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.HolderLookup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Camera;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
@Getter
@Setter
public class RendererSetting {

    public enum Layer {
        Opaque,
        Translucent
    }

    @Configurable(tips = "photon.emitter.config.renderer.layer")
    protected Layer layer = Layer.Translucent;

    @Configurable(tips = "photon.emitter.config.renderer.bloomEffect")
    protected boolean bloomEffect = false;

    @Configurable(name = "cull", subConfigurable = true, tips = "photon.emitter.config.renderer.cull")
    protected final Cull cull = new Cull();

    public static class Cull extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @ConfigNumber(range = {-10000, 10000})
        protected Vector3f from = new Vector3f(-0.5f, -0.5f, -0.5f);

        @Setter
        @Getter
        @Configurable
        @ConfigNumber(range = {-10000, 10000})
        protected Vector3f to = new Vector3f(0.5f, 0.5f, 0.5f);

        public AABB getCullAABB(Emitter particle, float partialTicks) {
            var pos = particle.transform().position();
            return new AABB(from.x, from.y, from.z, to.x, to.y, to.z).move(pos.x, pos.y, pos.z);
        }
    }

    @Getter
    @Setter
    public static class Particle extends RendererSetting implements IConfigurable, IPersistedSerializable {

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

        @Configurable(tips = "photon.emitter.config.renderer.renderMode")
        @ConfigSelector(subConfiguratorBuilder = "buildSubConfigurator")
        protected Mode renderMode = Mode.Billboard;
        @Nullable
        protected IModelRenderer model;
        @Persisted
        protected boolean shade = true;
        @Persisted
        protected boolean useBlockUV = true;

        public void buildSubConfigurator(Mode mode, ConfiguratorGroup group) {
            if (mode == Mode.Model) {
                getModel().buildConfigurator(group);
                group.addConfigurators(
                        new BooleanConfigurator("shade", this::isShade, this::setShade, true, true)
                                .setTips("photon.emitter.config.renderer.renderMode.model.shade"),
                        new BooleanConfigurator("useBlockUV", this::isUseBlockUV, this::setUseBlockUV, true, true)
                                .setTips("photon.emitter.config.renderer.renderMode.model.useBlockUV"));
            }
        }

        public IModelRenderer getModel() {
            if (model == null) {
                model = new IModelRenderer(ResourceLocation.parse("block/dirt"));
            }
            return model;
        }

        @Override
        public void deserializeNBT(HolderLookup.@NotNull Provider provider, @NotNull CompoundTag tag) {
            IPersistedSerializable.super.deserializeNBT(provider, tag);
            if (renderMode == Mode.Model && model != null) {
                model.deserializeNBT(provider, tag.getCompound("model"));
            }
        }

        @Override
        public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
            var tag = IPersistedSerializable.super.serializeNBT(provider);
            if (renderMode == Mode.Model) {
                tag.put("model", getModel().serializeNBT(provider));
            }
            return tag;
        }
    }
}
