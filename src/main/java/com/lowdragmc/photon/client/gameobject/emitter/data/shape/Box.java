package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;

@LDLRegisterClient(name = "box", registry = "photon:shape")
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
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
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
        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPos()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(new Vector3f(0, 0.05f, 0), rotation));
    }
}
