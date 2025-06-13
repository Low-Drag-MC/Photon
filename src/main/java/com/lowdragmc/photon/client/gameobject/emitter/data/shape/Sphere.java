package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;

@LDLRegisterClient(name = "sphere", registry = "photon:shape")
public class Sphere implements IShape {

    @Getter @Setter
    @Configurable
    @ConfigNumber(range = {0, 1000})
    private float radius = .5f;
    @Getter @Setter
    @Configurable
    @ConfigNumber(range = {0, 1})
    private float radiusThickness = 1;
    @Getter @Setter
    @Configurable
    @ConfigNumber(range = {0, 360}, wheel = 10)
    private float arc = 360;

    @Override
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var random = particle.getRandomSource();
        var outer = radius;
        var inner = (1 - radiusThickness) * radius;
        var origin = inner * inner * inner;
        var bound = outer * outer * outer;
        var r = outer == inner ? outer : Math.cbrt(origin + random.nextDouble() * (bound - origin));

        var theta = Math.acos(2 * random.nextDouble() - 1);
        var phi = arc * Mth.TWO_PI * random.nextDouble() / 360;

        var pos = new Vector3f((float) (r * Math.sin(theta) * Math.cos(phi)),
                (float) (r * Math.sin(theta) * Math.sin(phi)),
                (float) (r * Math.cos(theta))).mul(scale);

        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPos()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(new Vector3f(pos).normalize().mul(0.05f), rotation));
    }
}
