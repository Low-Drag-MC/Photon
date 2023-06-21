package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote RendererSetting
 */
@Environment(EnvType.CLIENT)
public class RendererSetting {
    public enum Mode {
        Billboard(null),
        Horizontal(0, 90),
        Vertical(0, 0);

        @Nullable
        final Quaternionf quaternion;

        Mode(@Nullable Quaternionf quaternion) {
            this.quaternion = quaternion;
        }

        Mode(float yRot, float xRot) {
            this.quaternion = new Quaternionf();
            this.quaternion.rotateY((float) Math.toRadians(-yRot));
            this.quaternion.rotateX((float) Math.toRadians(xRot));
        }
    }

    public enum Layer {
        Opaque,
        Translucent
    }

    @Getter
    @Setter
    @Configurable(tips = "Defines the render mode of the particle renderer.")
    protected Mode renderMode = Mode.Billboard;

    @Getter
    @Setter
    @Configurable(tips = "Defines the render layer of the particle renderer.")
    protected Layer layer = Layer.Translucent;

    @Getter
    @Setter
    @Configurable(tips = "Render particles with the bloom effect.")
    protected boolean bloomEffect = false;

    @Getter
    @Configurable(name = "cull", subConfigurable = true, tips = "Cull particles that are out of view.")
    protected final Cull cull = new Cull();

    public void setupQuaternion(LParticle particle) {
        particle.setQuaternion(renderMode.quaternion);
    }

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

        public AABB getCullAABB(LParticle particle, float partialTicks) {
            var pos = particle.getPos(partialTicks);
            return new AABB(from.x, from.y, from.z, to.x, to.y, to.z).move(pos.x, pos.y, pos.z);
        }
    }
}
