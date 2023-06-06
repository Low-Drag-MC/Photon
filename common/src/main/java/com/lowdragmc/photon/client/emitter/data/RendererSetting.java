package com.lowdragmc.photon.client.emitter.data;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.photon.client.particle.LParticle;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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

    @Getter
    @Setter
    @Configurable(tips = "Defines the render mode of the particle renderer.")
    protected Mode renderMode = Mode.Billboard;

    @Getter
    @Setter
    @Configurable(tips = "Render particles with the bloom effect.")
    protected boolean bloomEffect = false;

    public void setupQuaternion(LParticle particle) {
        particle.setQuaternion(renderMode.quaternion);
    }

}
