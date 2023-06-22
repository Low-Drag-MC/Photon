package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.particle.LParticle;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.AABB;

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
        final Quaternion quaternion;

        Mode(@Nullable Quaternion quaternion) {
            this.quaternion = quaternion;
        }

        Mode(float yRot, float xRot) {
            this.quaternion = new Quaternion(Quaternion.ONE);
            this.quaternion.mul(Vector3f.YP.rotationDegrees(-yRot));
            this.quaternion.mul(Vector3f.XP.rotationDegrees(xRot));
        }
    }

    public enum Layer {
        Opaque,
        Translucent
    }

    @Getter
    @Setter
    @Configurable(tips = "photon.emitter.config.renderer.renderMode")
    protected Mode renderMode = Mode.Billboard;

    @Getter
    @Setter
    @Configurable(tips = "photon.emitter.config.renderer.layer")
    protected Layer layer = Layer.Translucent;

    @Getter
    @Setter
    @Configurable(tips = "photon.emitter.config.renderer.bloomEffect")
    protected boolean bloomEffect = false;

    @Getter
    @Configurable(name = "cull", subConfigurable = true, tips = "photon.emitter.config.renderer.cull")
    protected final Cull cull = new Cull();

    public void setupQuaternion(LParticle particle) {
        particle.setQuaternion(renderMode.quaternion);
    }

    public static class Cull extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @NumberRange(range = {-10000, 10000})
        protected Vector3 from = new Vector3(-0.5f, -0.5f, -0.5f);

        @Setter
        @Getter
        @Configurable
        @NumberRange(range = {-10000, 10000})
        protected Vector3 to = new Vector3(0.5f, 0.5f, 0.5f);

        public AABB getCullAABB(LParticle particle, float partialTicks) {
            var pos = particle.getPos(partialTicks);
            return new AABB(from.x, from.y, from.z, to.x, to.y, to.z).move(pos.x, pos.y, pos.z);
        }
    }
}
