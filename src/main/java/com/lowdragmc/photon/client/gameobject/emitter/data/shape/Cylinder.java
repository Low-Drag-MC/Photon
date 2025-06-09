package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

@LDLRegister(name = "cylinder", group = "shape")
public class Cylinder implements IShape {

    @Getter
    @Setter
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
        var origin = inner * inner;
        var bound = outer * outer;
        var r = outer == inner ? outer : Math.sqrt(origin + random.nextDouble() * (bound - origin));

        var theta = arc * Mth.TWO_PI * random.nextDouble() / 360;

        var pos = new Vector3f((float) (r * Math.cos(theta)),
                random.nextFloat() * scale.y - scale.y / 2,
                (float) (r * Math.sin(theta))).mul(scale);

        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPos()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(new Vector3f(pos).normalize().mul(0.05f), rotation));
    }
}
