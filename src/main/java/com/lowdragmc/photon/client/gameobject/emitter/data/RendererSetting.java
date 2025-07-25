package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import lombok.Getter;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.phys.AABB;

import java.util.function.Supplier;

@OnlyIn(Dist.CLIENT)
@Getter
@Setter
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

    @Configurable(tips = "photon.emitter.config.renderer.layer")
    protected Layer layer = Layer.Translucent;

    @Configurable(name = "cull", subConfigurable = true, tips = "photon.emitter.config.renderer.cull")
    protected final Cull cull = new Cull();

    @Configurable(name = "photon.emitter.config.renderer.orderInLayer", tips = "photon.emitter.config.renderer.orderInLayer.tips")
    protected int orderInLayer = 0;

    @Configurable(name = "photon.emitter.config.renderer.vertexSortingMode", tips = "photon.emitter.config.renderer.vertexSortingMode.tips")
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

}
