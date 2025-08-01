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


@LDLRegisterClient(name = "cone", registry = "photon:shape")
public class Cone implements IShape {

    @Getter
    @Setter
    @Configurable(name = "angle")
    @ConfigNumber(range = {0, 90}, wheel = 10)
    private float angle = 25;
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
                (float) (r *  Math.sin(theta))).mul(scale);

        var speed = new Vector3f(0, 1, 0)
                .rotateAxis((float) ((r / radius) * Math.toRadians(angle)), 0, 0, -1)
                .rotateAxis((float) theta, 0, -1, 0);

        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPoseWithoutNoise()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(speed.normalize().mul(0.05f), rotation));
    }

    @Override
    public List<Pair<Vector3f, Vector3f>> getGuideLines(IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var lines = new java.util.ArrayList<Pair<Vector3f, Vector3f>>();

        var outer = radius;
        var inner = (1 - radiusThickness) * radius;

        var arcRad = arc * Mth.TWO_PI / 360;

        int segments = Math.max(8, (int) (arc / 15)); // 每15度一个线段，最少8个

        float emissionHeight = 2.0f; // 发射线的高度，可以根据需要调整

        float topOuterRadius = (float) (outer + emissionHeight * Math.tan(Math.toRadians(angle)));
        float topInnerRadius = (float) (inner + emissionHeight * Math.tan(Math.toRadians(angle)));

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

        for (int i = 0; i < segments; i++) {
            float theta1 = i * arcRad / segments;
            float theta2 = (i + 1) * arcRad / segments;

            Vector3f p1 = new Vector3f(
                    (float) (topOuterRadius * Math.cos(theta1)),
                    emissionHeight,
                    (float) (topOuterRadius * Math.sin(theta1))
            ).mul(scale);

            Vector3f p2 = new Vector3f(
                    (float) (topOuterRadius * Math.cos(theta2)),
                    emissionHeight,
                    (float) (topOuterRadius * Math.sin(theta2))
            ).mul(scale);

            p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
            p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

            lines.add(new Pair<>(p1, p2));
        }

        if (radiusThickness < 1.0f) {
            // 底面内圆弧线
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

            for (int i = 0; i < segments; i++) {
                float theta1 = i * arcRad / segments;
                float theta2 = (i + 1) * arcRad / segments;

                Vector3f p1 = new Vector3f(
                        (float) (topInnerRadius * Math.cos(theta1)),
                        emissionHeight,
                        (float) (topInnerRadius * Math.sin(theta1))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (topInnerRadius * Math.cos(theta2)),
                        emissionHeight,
                        (float) (topInnerRadius * Math.sin(theta2))
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

                Vector3f topOuterStart = new Vector3f(
                        (float) (topOuterRadius * Math.cos(0)),
                        emissionHeight,
                        (float) (topOuterRadius * Math.sin(0))
                ).mul(scale);
                Vector3f topInnerStart = new Vector3f(
                        (float) (topInnerRadius * Math.cos(0)),
                        emissionHeight,
                        (float) (topInnerRadius * Math.sin(0))
                ).mul(scale);

                topOuterStart = Vector3fHelper.rotateYXY(topOuterStart, rotation).add(position);
                topInnerStart = Vector3fHelper.rotateYXY(topInnerStart, rotation).add(position);
                lines.add(new Pair<>(topInnerStart, topOuterStart));

                Vector3f topOuterEnd = new Vector3f(
                        (float) (topOuterRadius * Math.cos(arcRad)),
                        emissionHeight,
                        (float) (topOuterRadius * Math.sin(arcRad))
                ).mul(scale);
                Vector3f topInnerEnd = new Vector3f(
                        (float) (topInnerRadius * Math.cos(arcRad)),
                        emissionHeight,
                        (float) (topInnerRadius * Math.sin(arcRad))
                ).mul(scale);

                topOuterEnd = Vector3fHelper.rotateYXY(topOuterEnd, rotation).add(position);
                topInnerEnd = Vector3fHelper.rotateYXY(topInnerEnd, rotation).add(position);
                lines.add(new Pair<>(topInnerEnd, topOuterEnd));
            }
        }

        int emissionLines = Math.max(4, segments / 2); // 发射线数量
        for (int i = 0; i < emissionLines; i++) {
            float theta = i * arcRad / emissionLines;

            Vector3f startPoint = new Vector3f(
                    (float) (outer * Math.cos(theta)),
                    0f,
                    (float) (outer * Math.sin(theta))
            ).mul(scale);

            Vector3f endPoint = new Vector3f(
                    (float) (topOuterRadius * Math.cos(theta)),
                    emissionHeight,
                    (float) (topOuterRadius * Math.sin(theta))
            ).mul(scale);

            startPoint = Vector3fHelper.rotateYXY(startPoint, rotation).add(position);
            endPoint = Vector3fHelper.rotateYXY(endPoint, rotation).add(position);

            lines.add(new Pair<>(startPoint, endPoint));

            if (radiusThickness < 1.0f) {
                Vector3f innerStartPoint = new Vector3f(
                        (float) (inner * Math.cos(theta)),
                        0f,
                        (float) (inner * Math.sin(theta))
                ).mul(scale);

                Vector3f innerEndPoint = new Vector3f(
                        (float) (topInnerRadius * Math.cos(theta)),
                        emissionHeight,
                        (float) (topInnerRadius * Math.sin(theta))
                ).mul(scale);

                innerStartPoint = Vector3fHelper.rotateYXY(innerStartPoint, rotation).add(position);
                innerEndPoint = Vector3fHelper.rotateYXY(innerEndPoint, rotation).add(position);

                lines.add(new Pair<>(innerStartPoint, innerEndPoint));
            }
        }

        if (arc < 360) {
            Vector3f center = Vector3fHelper.rotateYXY(new Vector3f(0, 0, 0), rotation).add(position);
            Vector3f topCenter = Vector3fHelper.rotateYXY(new Vector3f(0, emissionHeight, 0), rotation).add(position);

            Vector3f startPoint = new Vector3f(
                    (float) (outer * Math.cos(0)),
                    0f,
                    (float) (outer * Math.sin(0))
            ).mul(scale);
            Vector3f topStartPoint = new Vector3f(
                    (float) (topOuterRadius * Math.cos(0)),
                    emissionHeight,
                    (float) (topOuterRadius * Math.sin(0))
            ).mul(scale);

            startPoint = Vector3fHelper.rotateYXY(startPoint, rotation).add(position);
            topStartPoint = Vector3fHelper.rotateYXY(topStartPoint, rotation).add(position);

            lines.add(new Pair<>(center, startPoint));
            lines.add(new Pair<>(topCenter, topStartPoint));

            Vector3f endPoint = new Vector3f(
                    (float) (outer * Math.cos(arcRad)),
                    0f,
                    (float) (outer * Math.sin(arcRad))
            ).mul(scale);
            Vector3f topEndPoint = new Vector3f(
                    (float) (topOuterRadius * Math.cos(arcRad)),
                    emissionHeight,
                    (float) (topOuterRadius * Math.sin(arcRad))
            ).mul(scale);

            endPoint = Vector3fHelper.rotateYXY(endPoint, rotation).add(position);
            topEndPoint = Vector3fHelper.rotateYXY(topEndPoint, rotation).add(position);

            lines.add(new Pair<>(center, endPoint));
            lines.add(new Pair<>(topCenter, topEndPoint));
        }

        return lines;
    }
}
