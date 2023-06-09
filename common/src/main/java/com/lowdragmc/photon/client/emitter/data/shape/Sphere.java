package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.utils.Vector3fHelper;
import org.joml.Vector3f;
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
    public void nextPosVel(LParticle particle, Vector3f position, Vector3f rotation, Vector3f scale) {
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

        particle.setPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getPos()), true);
        particle.setSpeed(Vector3fHelper.rotateYXY(new Vector3f(pos).normalize().mul(0.05f), rotation));
    }
}
