package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.IntTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.phys.AABB;
import org.appliedenergistics.yoga.YogaDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@OnlyIn(Dist.CLIENT)
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RendererSetting {

    public enum Layer {
        Opaque,
        Translucent
    }

    public enum SortMode {
        NONE(() -> null),
        DISTANCE(RenderSystem::getVertexSorting);
        public final Supplier<VertexSorting> vertexSorting;

        SortMode(Supplier<VertexSorting> vertexSorting) {
            this.vertexSorting = vertexSorting;
        }

        public VertexSorting getVertexSorting() {
            return vertexSorting.get();
        }

    }

    @Configurable(name = "RendererSetting.materials", collapse = false)
    @ConfigList(configuratorMethod = "createMaterialConfigurator", addDefaultMethod = "addDefaultMaterial")
    @ReadOnlyManaged(serializeMethod = "materialSerialize", deserializeMethod = "materialDeserialize")
    @EqualsAndHashCode.Include
    protected List<MaterialSetting> materials = new ArrayList<>();

    @Configurable(name = "RendererSetting.layer", tips = "photon.emitter.config.renderer.layer")
    @EqualsAndHashCode.Include
    protected Layer layer = Layer.Translucent;

    @Configurable(name = "RendererSetting.cull", subConfigurable = true, tips = "photon.emitter.config.renderer.cull")
    protected final Cull cull = new Cull();

    @Configurable(name = "photon.emitter.config.renderer.orderInLayer", tips = "photon.emitter.config.renderer.orderInLayer.tips")
    @EqualsAndHashCode.Include
    protected int orderInLayer = 0;

    @Configurable(name = "photon.emitter.config.renderer.vertexSortingMode", tips = "photon.emitter.config.renderer.vertexSortingMode.tips")
    @EqualsAndHashCode.Include
    protected SortMode vertexSortingMode = SortMode.NONE;

    public static class Cull extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
        protected AABB cullBox = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);

        public AABB getCullAABB(Emitter particle, float partialTicks) {
            var transform = particle.transform();
            var scale = transform.scale();
            var pos = transform.position();
            return new AABB(cullBox.minX * scale.x, cullBox.minY * scale.y, cullBox.minZ * scale.z,
                    cullBox.maxX * scale.x, cullBox.maxY * scale.y, cullBox.maxZ * scale.z)
                    .move(pos.x, pos.y, pos.z);
        }
    }

    private Configurator createMaterialConfigurator(Supplier<MaterialSetting> getter, Consumer<MaterialSetting> setter) {
        var configurator = getter.get().createDirectConfigurator();
        if (configurator instanceof ConfiguratorGroup group) {
            group.setCollapse(false);
            group.lineContainer.setDisplay(YogaDisplay.NONE);
            return group;
        }
        return configurator;
    }

    private MaterialSetting addDefaultMaterial() {
        return new MaterialSetting();
    }

    private IntTag materialSerialize(List<MaterialSetting> value) {
        return IntTag.valueOf(value.size());
    }

    private List<MaterialSetting> materialDeserialize(IntTag size) {
        var materials = new ArrayList<MaterialSetting>();
        for (int i = 0; i < size.getAsInt(); i++) {
            materials.add(addDefaultMaterial());
        }
        return materials;
    }

}
