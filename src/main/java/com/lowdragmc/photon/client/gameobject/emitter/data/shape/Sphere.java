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

@LDLRegisterClient(name = "sphere", registry = "photon:shape")
public class Sphere implements IShape {

    @Getter @Setter
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
        var origin = inner * inner * inner;
        var bound = outer * outer * outer;
        var r = outer == inner ? outer : Math.cbrt(origin + random.nextDouble() * (bound - origin));

        var theta = Math.acos(2 * random.nextDouble() - 1);
        var phi = arc * Mth.TWO_PI * random.nextDouble() / 360;

        var pos = new Vector3f(
                (float) (r * Math.sin(theta) * Math.cos(phi)), // X = r * sin(θ) * cos(φ)
                (float) (r * Math.cos(theta)),                  // Y = r * cos(θ) （北极到南极）
                (float) (r * Math.sin(theta) * Math.sin(phi))   // Z = r * sin(θ) * sin(φ)
        ).mul(scale);


        particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPoseWithoutNoise()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(new Vector3f(pos).normalize().mul(0.05f), rotation));
    }


    @Override
    public List<Pair<Vector3f, Vector3f>> getGuideLines(IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var lines = new java.util.ArrayList<Pair<Vector3f, Vector3f>>();

        var outer = radius;
        var inner = (1 - radiusThickness) * radius;

        var arcRad = arc * Mth.TWO_PI / 360;

        int segments = Math.max(8, (int) (arc / 15)); // 每15度一个线段，最少8个
        int meridians = Math.max(6, segments / 2); // 经线数量
        int parallels = Math.max(4, segments / 3); // 纬线数量

        for (int i = 0; i <= parallels; i++) {
            float theta = (float) (Math.PI * i / parallels); // 从0到π
            float y = (float) (outer * Math.cos(theta));
            float circleRadius = (float) (outer * Math.sin(theta));

            if (Math.abs(circleRadius) < 0.01f) continue;

            for (int j = 0; j < segments; j++) {
                float phi1 = j * arcRad / segments;
                float phi2 = (j + 1) * arcRad / segments;

                Vector3f p1 = new Vector3f(
                        (float) (circleRadius * Math.cos(phi1)),
                        y,
                        (float) (circleRadius * Math.sin(phi1))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (circleRadius * Math.cos(phi2)),
                        y,
                        (float) (circleRadius * Math.sin(phi2))
                ).mul(scale);

                p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                lines.add(new Pair<>(p1, p2));
            }
        }

        for (int i = 0; i < meridians; i++) {
            float phi = i * arcRad / meridians;

            for (int j = 0; j < parallels; j++) {
                float theta1 = (float) (Math.PI * j / parallels);
                float theta2 = (float) (Math.PI * (j + 1) / parallels);

                Vector3f p1 = new Vector3f(
                        (float) (outer * Math.sin(theta1) * Math.cos(phi)),
                        (float) (outer * Math.cos(theta1)),
                        (float) (outer * Math.sin(theta1) * Math.sin(phi))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (outer * Math.sin(theta2) * Math.cos(phi)),
                        (float) (outer * Math.cos(theta2)),
                        (float) (outer * Math.sin(theta2) * Math.sin(phi))
                ).mul(scale);

                p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                lines.add(new Pair<>(p1, p2));
            }
        }

        if (radiusThickness < 1.0f) {
            for (int i = 0; i <= parallels; i++) {
                float theta = (float) (Math.PI * i / parallels);
                float y = (float) (inner * Math.cos(theta));
                float circleRadius = (float) (inner * Math.sin(theta));

                if (Math.abs(circleRadius) < 0.01f) continue;

                for (int j = 0; j < segments; j++) {
                    float phi1 = j * arcRad / segments;
                    float phi2 = (j + 1) * arcRad / segments;

                    Vector3f p1 = new Vector3f(
                            (float) (circleRadius * Math.cos(phi1)),
                            y,
                            (float) (circleRadius * Math.sin(phi1))
                    ).mul(scale);

                    Vector3f p2 = new Vector3f(
                            (float) (circleRadius * Math.cos(phi2)),
                            y,
                            (float) (circleRadius * Math.sin(phi2))
                    ).mul(scale);

                    p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                    p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                    lines.add(new Pair<>(p1, p2));
                }
            }

            for (int i = 0; i < meridians; i++) {
                float phi = i * arcRad / meridians;

                for (int j = 0; j < parallels; j++) {
                    float theta1 = (float) (Math.PI * j / parallels);
                    float theta2 = (float) (Math.PI * (j + 1) / parallels);

                    Vector3f p1 = new Vector3f(
                            (float) (inner * Math.sin(theta1) * Math.cos(phi)),
                            (float) (inner * Math.cos(theta1)),
                            (float) (inner * Math.sin(theta1) * Math.sin(phi))
                    ).mul(scale);

                    Vector3f p2 = new Vector3f(
                            (float) (inner * Math.sin(theta2) * Math.cos(phi)),
                            (float) (inner * Math.cos(theta2)),
                            (float) (inner * Math.sin(theta2) * Math.sin(phi))
                    ).mul(scale);

                    p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                    p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                    lines.add(new Pair<>(p1, p2));
                }
            }

            int radialLines = Math.max(4, meridians / 2);
            for (int i = 0; i < radialLines; i++) {
                float phi = i * arcRad / radialLines;

                for (int j = 0; j <= parallels / 2; j++) {
                    float theta = (float) (Math.PI * j * 2 / parallels);

                    Vector3f innerPoint = new Vector3f(
                            (float) (inner * Math.sin(theta) * Math.cos(phi)),
                            (float) (inner * Math.cos(theta)),
                            (float) (inner * Math.sin(theta) * Math.sin(phi))
                    ).mul(scale);

                    Vector3f outerPoint = new Vector3f(
                            (float) (outer * Math.sin(theta) * Math.cos(phi)),
                            (float) (outer * Math.cos(theta)),
                            (float) (outer * Math.sin(theta) * Math.sin(phi))
                    ).mul(scale);

                    innerPoint = Vector3fHelper.rotateYXY(innerPoint, rotation).add(position);
                    outerPoint = Vector3fHelper.rotateYXY(outerPoint, rotation).add(position);

                    lines.add(new Pair<>(innerPoint, outerPoint));
                }
            }
        }

        if (arc < 360) {
            Vector3f center = Vector3fHelper.rotateYXY(new Vector3f(0, 0, 0), rotation).add(position);

            for (int j = 0; j < parallels; j++) {
                float theta1 = (float) (Math.PI * j / parallels);
                float theta2 = (float) (Math.PI * (j + 1) / parallels);

                Vector3f p1 = new Vector3f(
                        (float) (outer * Math.sin(theta1) * Math.cos(0)),
                        (float) (outer * Math.cos(theta1)),
                        (float) (outer * Math.sin(theta1) * Math.sin(0))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (outer * Math.sin(theta2) * Math.cos(0)),
                        (float) (outer * Math.cos(theta2)),
                        (float) (outer * Math.sin(theta2) * Math.sin(0))
                ).mul(scale);

                p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                lines.add(new Pair<>(p1, p2));

                lines.add(new Pair<>(center, p1));
            }

            for (int j = 0; j < parallels; j++) {
                float theta1 = (float) (Math.PI * j / parallels);
                float theta2 = (float) (Math.PI * (j + 1) / parallels);

                Vector3f p1 = new Vector3f(
                        (float) (outer * Math.sin(theta1) * Math.cos(arcRad)),
                        (float) (outer * Math.cos(theta1)),
                        (float) (outer * Math.sin(theta1) * Math.sin(arcRad))
                ).mul(scale);

                Vector3f p2 = new Vector3f(
                        (float) (outer * Math.sin(theta2) * Math.cos(arcRad)),
                        (float) (outer * Math.cos(theta2)),
                        (float) (outer * Math.sin(theta2) * Math.sin(arcRad))
                ).mul(scale);

                p1 = Vector3fHelper.rotateYXY(p1, rotation).add(position);
                p2 = Vector3fHelper.rotateYXY(p2, rotation).add(position);

                lines.add(new Pair<>(p1, p2));

                lines.add(new Pair<>(center, p1));
            }
        }

        return lines;
    }
}
