package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import org.joml.Vector3f;
import com.lowdragmc.photon.client.particle.LParticle;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Dot
 */
@LDLRegister(name = "dot", group = "shape")
public class Dot implements IShape {

    @Override
    public void nextPosVel(LParticle particle, LParticle emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        particle.setPos(position.add(particle.getPos()), true);
        particle.setSpeed(new Vector3f(0, 0, 0));
    }
}
