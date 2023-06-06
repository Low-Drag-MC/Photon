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
 * @date 2023/5/26
 * @implNote Sphere
 */
@LDLRegister(name = "sphere", group = "shape")
public class Sphere implements IShape {

    @Getter @Setter
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
        var origin = inner * inner * inner;
        var bound = outer * outer * outer;
        var r = outer == inner ? outer : Math.cbrt(origin + random.nextDouble() * (bound - origin));

        var theta = Math.acos(2 * random.nextDouble() - 1);
        var phi = arc * Mth.TWO_PI * random.nextDouble() / 360;

        var pos = new Vector3(r * Math.sin(theta) * Math.cos(phi),
                r * Math.sin(theta) * Math.sin(phi),
                r * Math.cos(theta)).multiply(scale);

        particle.setPos(pos.copy().rotateYXY(rotation).add(position).add(particle.getPos()), true);
        particle.setSpeed(pos.copy().normalize().multiply(0.05).rotateYXY(rotation));
    }
}
