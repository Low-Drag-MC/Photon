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
import oshi.util.tuples.Pair;

import java.util.List;

@LDLRegisterClient(name = "circle", registry = "photon:shape")
public class Circle implements IShape {

    @Getter
    @Setter
    @Configurable(name = "radius")
    @ConfigNumber(range = {0, 1000})
    private float radius = .5f;
    @Getter @Setter
    @Configurable(name = "radiusThickness")
    @ConfigNumber(range = {0, 1})
    private float radiusThickness = 1;
    @Getter @Setter
    @Configurable(name = "arc")
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
                0f,
                (float) (r * Math.sin(theta))).mul(scale);

        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPoseWithoutNoise()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(new Vector3f(pos).normalize().mul(0.05f), rotation));
    }

    @Override
    public List<Pair<Vector3f, Vector3f>> getGuideLines(IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var lines = new java.util.ArrayList<Pair<Vector3f, Vector3f>>();
        var outer = radius;
        var inner = (1 - radiusThickness) * radius;
        var arcRad = arc * Mth.TWO_PI / 360;
        int segments = Math.max(8, (int) (arc / 10)); // 每10度一个线段，最少8个

        for (int i = 0; i < segments; i++) {
            float theta1 = i * arcRad / segments;
            float theta2 = (i + 1) * arcRad / segments;

            Vector3f p1 = new Vector3f(
                    (float) (outer * Math.cos(theta1)),
                    0f,
                    (float) (outer * Math.sin(theta1))
            ).mul(scale);

            Vector3f p2 = new Vector3f(
                    (float) (outer * Math.cos(theta2)),
                    0f,
                    (float) (outer * Math.sin(theta2))
            ).mul(scale);

            p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
            p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

            lines.add(new Pair<>(p1, p2));
        }

        if (radiusThickness < 1.0f) {
            for (int i = 0; i < segments; i++) {
                float theta1 = i * arcRad / segments;
                float theta2 = (i + 1) * arcRad / segments;

                Vector3f p1 = new Vector3f(
                        (float) (inner * Math.cos(theta1)),
                        0f,
                        (float) (inner * Math.sin(theta1))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (inner * Math.cos(theta2)),
                        0f,
                        (float) (inner * Math.sin(theta2))
                ).mul(scale);

                p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                lines.add(new Pair<>(p1, p2));
            }

            if (arc < 360) {
                Vector3f outerStart = new Vector3f(
                        (float) (outer * Math.cos(0)),
                        0f,
                        (float) (outer * Math.sin(0))
                ).mul(scale);
                Vector3f innerStart = new Vector3f(
                        (float) (inner * Math.cos(0)),
                        0f,
                        (float) (inner * Math.sin(0))
                ).mul(scale);

                outerStart = Vector3fHelper.rotateYXY(outerStart, rotation).add(position);
                innerStart = Vector3fHelper.rotateYXY(innerStart, rotation).add(position);
                lines.add(new Pair<>(innerStart, outerStart));

                Vector3f outerEnd = new Vector3f(
                        (float) (outer * Math.cos(arcRad)),
                        0f,
                        (float) (outer * Math.sin(arcRad))
                ).mul(scale);
                Vector3f innerEnd = new Vector3f(
                        (float) (inner * Math.cos(arcRad)),
                        0f,
                        (float) (inner * Math.sin(arcRad))
                ).mul(scale);

                outerEnd = Vector3fHelper.rotateYXY(outerEnd, rotation).add(position);
                innerEnd = Vector3fHelper.rotateYXY(innerEnd, rotation).add(position);
                lines.add(new Pair<>(innerEnd, outerEnd));
            }
        } else if (arc < 360) {
            Vector3f center = Vector3fHelper.rotateYXY(new Vector3f(0, 0, 0), rotation).add(position);

            Vector3f start = new Vector3f(
                    (float) (outer * Math.cos(0)),
                    0f,
                    (float) (outer * Math.sin(0))
            ).mul(scale);
            start = Vector3fHelper.rotateYXY(start, rotation).add(position);
            lines.add(new Pair<>(center, start));

            Vector3f end = new Vector3f(
                    (float) (outer * Math.cos(arcRad)),
                    0f,
                    (float) (outer * Math.sin(arcRad))
            ).mul(scale);
            end = Vector3fHelper.rotateYXY(end, rotation).add(position);
            lines.add(new Pair<>(center, end));
        }

        return lines;

    }
}
