package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.particle.LParticle;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Dot
 */
@LDLRegister(name = "dot", group = "shape")
public class Dot implements IShape {

    @Override
    public void nextPosVel(LParticle particle, LParticle emitter, Vector3 position, Vector3 rotation, Vector3 scale) {
        particle.setPos(position.add(particle.getPos()), true);
        particle.setSpeed(new Vector3(0, 0, 0));
    }
}
