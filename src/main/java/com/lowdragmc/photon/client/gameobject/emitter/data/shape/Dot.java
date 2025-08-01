package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;

@LDLRegisterClient(name = "dot", registry = "photon:shape")
public class Dot implements IShape {

    @Override
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        particle.setLocalPos(position.add(particle.getLocalPoseWithoutNoise()), true);
        particle.setInternalVelocity(new Vector3f(0, 0, 0));
    }
}
