package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.utils.Vector3fHelper;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Box
 */
@LDLRegister(name = "box", group = "shape")
public class Box implements IShape {
    public enum Type {
        Volume,
        Shell,
        Edge
    }

    @Getter @Setter
    @Configurable
    private Type emitFrom = Type.Volume;

    @Override
    public void nextPosVel(LParticle particle, LParticle emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var random = particle.getRandomSource();
        scale = new Vector3f(Math.abs(scale.x), Math.abs(scale.y), Math.abs(scale.z)).mul(0.5f);
        Vector3f pos = new Vector3f(random.nextFloat() * 2 * scale.x - scale.x,
                random.nextFloat() * 2 * scale.y - scale.y,
                random.nextFloat() * 2 * scale.z - scale.z);
        if (emitFrom == Type.Shell) {
            double xy = scale.x * scale.y;
            double yz = scale.y * scale.z;
            double xz = scale.x * scale.z;
            var randomValue = random.nextDouble() * (xy + yz + xz);
            if (randomValue < xy) {
                pos.z = random.nextFloat() > 0.5 ? scale.z : -scale.z;
            } else if (randomValue < yz + xy) {
                pos.x = random.nextFloat() > 0.5 ? scale.x : -scale.x;
            } else {
                pos.y = random.nextFloat() > 0.5 ? scale.y : -scale.y;
            }
        } else if (emitFrom == Type.Edge) {
            var randomValue = random.nextDouble() * (scale.x + scale.y + scale.z);
            if (randomValue < scale.x) {
                pos.z = random.nextFloat() > 0.5 ? scale.z : -scale.z;
                pos.y = random.nextFloat() > 0.5 ? scale.y : -scale.y;
            } else if (randomValue < scale.x + scale.y) {
                pos.z = random.nextFloat() > 0.5 ? scale.z : -scale.z;
                pos.x = random.nextFloat() > 0.5 ? scale.x : -scale.x;
            } else {
                pos.x = random.nextFloat() > 0.5 ? scale.x : -scale.x;
                pos.y = random.nextFloat() > 0.5 ? scale.y : -scale.y;
            }
        }
        particle.setPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getPos()), true);
        particle.setSpeed(Vector3fHelper.rotateYXY(new Vector3f(0, 0.05f, 0), rotation));
    }
}
