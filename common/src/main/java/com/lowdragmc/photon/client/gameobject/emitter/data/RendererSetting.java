package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib.client.renderer.impl.IModelRenderer;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberColor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.editor.configurator.BooleanConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote RendererSetting
 */
@Environment(EnvType.CLIENT)
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

    @Setter
    @Getter
    @Configurable(tips = "photon.emitter.config.renderer.bloomColor")
    @NumberColor
    protected int bloomColor = -1;

    @Configurable(name = "cull", subConfigurable = true, tips = "photon.emitter.config.renderer.cull")
    protected final Cull cull = new Cull();

    public static class Cull extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @NumberRange(range = {-10000, 10000})
        protected Vector3f from = new Vector3f(-0.5f, -0.5f, -0.5f);

        @Setter
        @Getter
        @Configurable
        @NumberRange(range = {-10000, 10000})
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
            Horizontal(0, 90),
            Vertical(0, 0),
            VerticalBillboard((p, c, t) -> {
                var quaternion = new Quaternionf();
                quaternion.rotateY((float) Math.toRadians(-c.getYRot()));
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
                quaternion.rotateY((float) Math.toRadians(-yRot));
                quaternion.rotateX((float) Math.toRadians(xRot));
                this.quaternion = (p, c, t) -> quaternion;
            }
        }

        @Persisted
        protected Mode renderMode = Mode.Billboard;
        @Nullable
        protected IModelRenderer model;
        @Persisted
        protected boolean shade = true;
        @Persisted
        protected boolean useBlockUV = true;

        @Override
        public void buildConfigurator(ConfiguratorGroup father) {
            var configurator = new ConfiguratorSelectorConfigurator<>("renderMode",
                    false, this::getRenderMode, this::setRenderMode, Mode.Billboard, true,
                    Arrays.stream(Mode.values()).toList(), Mode::name, (mode, container) -> {
                if (mode == Mode.Model) {
                    getModel().buildConfigurator(container);
                    var shadeConfigurator = new BooleanConfigurator("shade", this::isShade, this::setShade, true, true);
                    shadeConfigurator.setTips("photon.emitter.config.renderer.renderMode.model.shade");
                    container.addConfigurators(shadeConfigurator);
                    var useBlockUVConfigurator = new BooleanConfigurator("useBlockUV", this::isUseBlockUV, this::setUseBlockUV, true, true);
                    shadeConfigurator.setTips("photon.emitter.config.renderer.renderMode.model.useBlockUV");
                    container.addConfigurators(useBlockUVConfigurator);
                }
            });
            configurator.setTips("photon.emitter.config.renderer.renderMode");
            father.addConfigurators(configurator);
            IConfigurable.super.buildConfigurator(father);
        }

        public IModelRenderer getModel() {
            if (model == null) {
                model = new IModelRenderer(new ResourceLocation("block/dirt"));
            }
            return model;
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            IPersistedSerializable.super.deserializeNBT(tag);
            if (renderMode == Mode.Model) {
                if (model == null) {
                    model = new IModelRenderer(new ResourceLocation("block/dirt"));
                }
                model.deserializeNBT(tag.getCompound("model"));
            }
        }

        @Override
        public CompoundTag serializeNBT() {
            var tag = IPersistedSerializable.super.serializeNBT();
            if (renderMode == Mode.Model && model != null) {
                tag.put("model", getModel().serializeNBT());
            }
            return tag;
        }
    }
}
