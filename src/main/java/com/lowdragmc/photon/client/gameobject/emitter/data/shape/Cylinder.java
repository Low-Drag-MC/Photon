package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import oshi.util.tuples.Pair;

import java.util.List;

@LDLRegisterClient(name = "cylinder", registry = "photon:shape")
public class Cylinder implements IShape {

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
                random.nextFloat() * 1 - 0.5f,
                (float) (r * Math.sin(theta))).mul(scale);
        var radialDirection = new Vector3f((float) Math.cos(theta), 0, (float) Math.sin(theta));

        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPoseWithoutNoise()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(radialDirection.normalize().mul(0.05f), rotation));
    }

    @Override
    public List<Pair<Vector3f, Vector3f>> getGuideLines(IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var lines = new java.util.ArrayList<Pair<Vector3f, Vector3f>>();

        var outer = radius;
        var inner = (1 - radiusThickness) * radius;

        var arcRad = arc * Mth.TWO_PI / 360;

        int segments = Math.max(8, (int) (arc / 15));

        float halfHeight = 0.5f;

        for (int i = 0; i < segments; i++) {
            float theta1 = i * arcRad / segments;
            float theta2 = (i + 1) * arcRad / segments;

            Vector3f p1 = new Vector3f(
                    (float) (outer * Math.cos(theta1)),
                    -halfHeight,
                    (float) (outer * Math.sin(theta1))
            ).mul(scale);

            Vector3f p2 = new Vector3f(
                    (float) (outer * Math.cos(theta2)),
                    -halfHeight,
                    (float) (outer * Math.sin(theta2))
            ).mul(scale);

            p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
            p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

            lines.add(new Pair<>(p1, p2));
        }

        for (int i = 0; i < segments; i++) {
            float theta1 = i * arcRad / segments;
            float theta2 = (i + 1) * arcRad / segments;

            Vector3f p1 = new Vector3f(
                    (float) (outer * Math.cos(theta1)),
                    halfHeight,
                    (float) (outer * Math.sin(theta1))
            ).mul(scale);

            Vector3f p2 = new Vector3f(
                    (float) (outer * Math.cos(theta2)),
                    halfHeight,
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
                        -halfHeight,
                        (float) (inner * Math.sin(theta1))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (inner * Math.cos(theta2)),
                        -halfHeight,
                        (float) (inner * Math.sin(theta2))
                ).mul(scale);

                p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                lines.add(new Pair<>(p1, p2));
            }

            for (int i = 0; i < segments; i++) {
                float theta1 = i * arcRad / segments;
                float theta2 = (i + 1) * arcRad / segments;

                Vector3f p1 = new Vector3f(
                        (float) (inner * Math.cos(theta1)),
                        halfHeight,
                        (float) (inner * Math.sin(theta1))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (inner * Math.cos(theta2)),
                        halfHeight,
                        (float) (inner * Math.sin(theta2))
                ).mul(scale);

                p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                lines.add(new Pair<>(p1, p2));
            }

            if (arc < 360) {
                Vector3f outerStart = new Vector3f(
                        (float) (outer * Math.cos(0)),
                        -halfHeight,
                        (float) (outer * Math.sin(0))
                ).mul(scale);
                Vector3f innerStart = new Vector3f(
                        (float) (inner * Math.cos(0)),
                        -halfHeight,
                        (float) (inner * Math.sin(0))
                ).mul(scale);

                outerStart = Vector3fHelper.rotateYXY(outerStart, rotation).add(position);
                innerStart = Vector3fHelper.rotateYXY(innerStart, rotation).add(position);
                lines.add(new Pair<>(innerStart, outerStart));

                Vector3f outerEnd = new Vector3f(
                        (float) (outer * Math.cos(arcRad)),
                        -halfHeight,
                        (float) (outer * Math.sin(arcRad))
                ).mul(scale);
                Vector3f innerEnd = new Vector3f(
                        (float) (inner * Math.cos(arcRad)),
                        -halfHeight,
                        (float) (inner * Math.sin(arcRad))
                ).mul(scale);

                outerEnd = Vector3fHelper.rotateYXY(outerEnd, rotation).add(position);
                innerEnd = Vector3fHelper.rotateYXY(innerEnd, rotation).add(position);
                lines.add(new Pair<>(innerEnd, outerEnd));

                Vector3f topOuterStart = new Vector3f(
                        (float) (outer * Math.cos(0)),
                        halfHeight,
                        (float) (outer * Math.sin(0))
                ).mul(scale);
                Vector3f topInnerStart = new Vector3f(
                        (float) (inner * Math.cos(0)),
                        halfHeight,
                        (float) (inner * Math.sin(0))
                ).mul(scale);

                topOuterStart = Vector3fHelper.rotateYXY(topOuterStart, rotation).add(position);
                topInnerStart = Vector3fHelper.rotateYXY(topInnerStart, rotation).add(position);
                lines.add(new Pair<>(topInnerStart, topOuterStart));

                Vector3f topOuterEnd = new Vector3f(
                        (float) (outer * Math.cos(arcRad)),
                        halfHeight,
                        (float) (outer * Math.sin(arcRad))
                ).mul(scale);
                Vector3f topInnerEnd = new Vector3f(
                        (float) (inner * Math.cos(arcRad)),
                        halfHeight,
                        (float) (inner * Math.sin(arcRad))
                ).mul(scale);

                topOuterEnd = Vector3fHelper.rotateYXY(topOuterEnd, rotation).add(position);
                topInnerEnd = Vector3fHelper.rotateYXY(topInnerEnd, rotation).add(position);
                lines.add(new Pair<>(topInnerEnd, topOuterEnd));
            }
        }

        int verticalLines = Math.max(4, segments / 2);
        for (int i = 0; i < verticalLines; i++) {
            float theta = i * arcRad / verticalLines;

            Vector3f bottomPoint = new Vector3f(
                    (float) (outer * Math.cos(theta)),
                    -halfHeight,
                    (float) (outer * Math.sin(theta))
            ).mul(scale);

            Vector3f topPoint = new Vector3f(
                    (float) (outer * Math.cos(theta)),
                    halfHeight,
                    (float) (outer * Math.sin(theta))
            ).mul(scale);

            bottomPoint = Vector3fHelper.rotateYXY(bottomPoint, rotation).add(position);
            topPoint = Vector3fHelper.rotateYXY(topPoint, rotation).add(position);

            lines.add(new Pair<>(bottomPoint, topPoint));

            if (radiusThickness < 1.0f) {
                Vector3f innerBottomPoint = new Vector3f(
                        (float) (inner * Math.cos(theta)),
                        -halfHeight,
                        (float) (inner * Math.sin(theta))
                ).mul(scale);

                Vector3f innerTopPoint = new Vector3f(
                        (float) (inner * Math.cos(theta)),
                        halfHeight,
                        (float) (inner * Math.sin(theta))
                ).mul(scale);

                innerBottomPoint = Vector3fHelper.rotateYXY(innerBottomPoint, rotation).add(position);
                innerTopPoint = Vector3fHelper.rotateYXY(innerTopPoint, rotation).add(position);

                lines.add(new Pair<>(innerBottomPoint, innerTopPoint));
            }
        }

        if (arc < 360) {
            Vector3f bottomCenter = Vector3fHelper.rotateYXY(new Vector3f(0, -halfHeight, 0), rotation).add(position);
            Vector3f topCenter = Vector3fHelper.rotateYXY(new Vector3f(0, halfHeight, 0), rotation).add(position);

            Vector3f bottomStart = new Vector3f(
                    (float) (outer * Math.cos(0)),
                    -halfHeight,
                    (float) (outer * Math.sin(0))
            ).mul(scale);
            Vector3f topStart = new Vector3f(
                    (float) (outer * Math.cos(0)),
                    halfHeight,
                    (float) (outer * Math.sin(0))
            ).mul(scale);

            bottomStart = Vector3fHelper.rotateYXY(bottomStart, rotation).add(position);
            topStart = Vector3fHelper.rotateYXY(topStart, rotation).add(position);

            lines.add(new Pair<>(bottomCenter, bottomStart));
            lines.add(new Pair<>(topCenter, topStart));
            lines.add(new Pair<>(bottomStart, topStart));

            Vector3f bottomEnd = new Vector3f(
                    (float) (outer * Math.cos(arcRad)),
                    -halfHeight,
                    (float) (outer * Math.sin(arcRad))
            ).mul(scale);
            Vector3f topEnd = new Vector3f(
                    (float) (outer * Math.cos(arcRad)),
                    halfHeight,
                    (float) (outer * Math.sin(arcRad))
            ).mul(scale);

            bottomEnd = Vector3fHelper.rotateYXY(bottomEnd, rotation).add(position);
            topEnd = Vector3fHelper.rotateYXY(topEnd, rotation).add(position);

            lines.add(new Pair<>(bottomCenter, bottomEnd));
            lines.add(new Pair<>(topCenter, topEnd));
            lines.add(new Pair<>(bottomEnd, topEnd));
        }

        return lines;
    }
}
