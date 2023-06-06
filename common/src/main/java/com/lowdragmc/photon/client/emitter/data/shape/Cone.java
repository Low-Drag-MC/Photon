package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.particle.LParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote Circle
 */
@LDLRegister(name = "cone", group = "shape")
public class Cone implements IShape {

    @Getter
    @Setter
    @Configurable
    @NumberRange(range = {0, 90}, wheel = 10)
    private float angle = 25;
    @Getter
    @Setter
    @Configurable
    @NumberRange(range = {0, 1000})
    private float radius = .5f;
    @Getter @Setter
    @Configurable
    @NumberRange(range = {0, 1})
    private float radiusThickness = 1;
    @Getter @Setter
    @Configurable
    @NumberRange(range = {0, 360}, wheel = 10)
    private float arc = 360;

    @Override
    public void nextPosVel(LParticle particle, Vector3 position, Vector3 rotation, Vector3 scale) {
        var random = particle.getRandomSource();
        var outer = radius;
        var inner = (1 - radiusThickness) * radius;
        var origin = inner * inner;
        var bound = outer * outer;
        var r = outer == inner ? outer : Math.sqrt(origin + random.nextDouble() * (bound - origin));

        var theta = arc * Mth.TWO_PI * random.nextDouble() / 360;

        var pos = new Vector3(r * Math.cos(theta),
                0,
                r *  Math.sin(theta)).multiply(scale);

        var speed = new Vector3(0, 1, 0)
                .rotate((r / radius) * Math.toRadians(angle), new Vector3(0, 0, -1))
                .rotate(theta, new Vector3(0, -1, 0));

        particle.setPos(pos.copy().rotateYXY(rotation).add(position).add(particle.getPos()), true);
        particle.setSpeed(speed.normalize().multiply(0.05).rotateYXY(rotation));
    }
}
