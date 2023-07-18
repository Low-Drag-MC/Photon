package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.particle.LParticle;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import lombok.Getter;
import lombok.Setter;
import lombok.val;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.AABB;
import java.util.function.Function;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote RendererSetting
 */
@Environment(EnvType.CLIENT)
public class RendererSetting {
    public enum Mode {
        Billboard(p -> null),
        Horizontal(0, 90),
        Vertical(0, 0),
        Speed(p -> {
            var speed = p.getVelocity();
            if (speed.isZero()) return null;
            return CalQuaternion(speed.normalize());
        });

        final Function<LParticle, Quaternion> quaternion;

        Mode(Function<LParticle, Quaternion> quaternion) {
            this.quaternion = quaternion;
        }

        Mode(float yRot, float xRot) {
            val q = new Quaternion(Quaternion.ONE);
            q.mul(Vector3f.YP.rotationDegrees(-yRot));
            q.mul(Vector3f.XP.rotationDegrees(xRot));
            this.quaternion = p -> q;
        }


        public static Quaternion CalQuaternion(Vector3 dir) {
            Quaternion cal = new Quaternion(Quaternion.ONE);
            //欧拉角Y: cosY = z/sqrt(x^2+z^2)
            var CosY = dir.z / Math.sqrt(dir.x * dir.x + dir.z * dir.z);
            var CosYDiv2 = Math.sqrt((CosY + 1) / 2);
            if (dir.x < 0) CosYDiv2 = -CosYDiv2;

            var SinYDiv2 = Math.sqrt((1-CosY) / 2);

            //欧拉角X: cosX = sqrt((x^2+z^2)/(x^2+y^2+z^2)
            var CosX = Math.sqrt((dir.x * dir.x + dir.z * dir.z) / (dir.x * dir.x + dir.y * dir.y + dir.z * dir.z));
            if (dir.z < 0) CosX = -CosX;
            var CosXDiv2 = Math.sqrt((CosX + 1) / 2);
            if (dir.y > 0) CosXDiv2 = -CosXDiv2;
            var SinXDiv2 = Math.sqrt((1 - CosX) / 2);

            //四元数w = cos(x/2)cos(y/2)
            cal.set((float) (SinXDiv2 * CosYDiv2),
                    (float) (CosXDiv2 * SinYDiv2),
                    (float) (-SinXDiv2 * SinYDiv2),
                    (float) (CosXDiv2 * CosYDiv2));
            return cal;
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

    public void setupQuaternion(LParticle emitter, LParticle particle) {
        particle.setQuaternionSupplier(() -> renderMode.quaternion.apply(emitter));
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
