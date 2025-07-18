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
import org.joml.Vector3f;

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

}
