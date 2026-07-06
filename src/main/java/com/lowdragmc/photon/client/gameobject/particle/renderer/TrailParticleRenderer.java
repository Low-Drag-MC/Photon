package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;

/**
 * Renders {@link TrailParticle}s as a camera-facing triangle strip with degenerate-triangle
 * stitching between trails. Stateless — shared singleton.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class TrailParticleRenderer {
    public static final TrailParticleRenderer INSTANCE = new TrailParticleRenderer();

    private TrailParticleRenderer() {
    }

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        for (var particle : particles) {
            if (particle instanceof TrailParticle trailParticle && trailParticle.getDelay() <= 0) {
                renderTrail(buffer, trailParticle, camera.getPosition().toVector3f(), partialTicks);
            }
        }
    }

    private void renderTrail(VertexConsumer buffer, TrailParticle particle, Vector3f cameraPos, float partialTicks) {
        var tails = particle.getTails();
        var rawTails = particle.getRawTails();
        var color = particle.getRealColor(partialTicks);
        var light = particle.getRealLight(partialTicks);

        Vector3f lastNormal = null;
        Vector3f lastFaceNormal = null;
        Vector3f lastUp = null;

        Vector3f headPos = particle.getHeadPosition(partialTicks);
        boolean pushHead = true;
        int tailSize = tails.size();
        if (tailSize > 0) {
            Vector3f lastPos = tails.getPosition(tailSize - 1);
            if (lastPos.equals(headPos)) {
                pushHead = false;
            }
        }

        if (pushHead) {
            var headTail = new TrailParticle.Tail(headPos, 100, tails.getColor(0), tails.getWidth(0));
            tails.add(headTail);
        }

        var lerpDur = 0f;
        var t = 0f;
        if (rawTails.size() > 1) {
            lerpDur = rawTails.getLifeTime(1) - rawTails.getLifeTime(0);
            t = 1 - (rawTails.getLifeTime(0) + 1 - partialTicks) / lerpDur;
        }
        int size = tails.size();
        for (int i = 0; i < size - 1; i++) {
            // skip dead tails
            if ((tails.getLifeTime(i) - partialTicks < 0f && tails.getLifeTime(i + 1) - partialTicks < 0)) {
                continue;
            }
            var currT = tails.getLifeTime(i) / lerpDur;
            var nextT = tails.getLifeTime(i + 1) / lerpDur;
            if (nextT < t) continue;

            // basic
            Vector3f tailPos = tails.getPosition(i);
            Vector3f next = tails.getPosition(i + 1);
            // apply interpolation for tail
            if (lerpDur > 0) {
                if (currT <= t && t <= nextT) {
                    tailPos = tailPos.lerp(next, (t - currT) / (nextT - currT));
                }
            }

            Vector3f curr = new Vector3f(tailPos);
            Vector3f vec = new Vector3f(next).sub(curr);
            Vector3f toTail = new Vector3f(curr).sub(cameraPos);
            Vector3f normal = new Vector3f(vec).cross(toTail).normalize();

            if (lastNormal == null) lastNormal = normal;

            Vector3f avgNormal = new Vector3f(lastNormal).add(normal).div(2);
            Vector3f up = new Vector3f(tailPos).add(new Vector3f(avgNormal).mul(tails.getWidth(i))).sub(cameraPos);
            Vector3f down = new Vector3f(tailPos).add(new Vector3f(avgNormal).mul(-tails.getWidth(i))).sub(cameraPos);
            Vector3f faceNormal = new Vector3f(avgNormal).cross(vec).normalize();

            var tailColor = tails.getColor(i);
            float ta = color.w() * tailColor.w();
            float tr = color.x() * tailColor.x();
            float tg = color.y() * tailColor.y();
            float tb = color.z() * tailColor.z();

            Vector4f uvs = particle.getUVs(i, size, partialTicks);
            float u0 = uvs.x(), u1 = uvs.z(), v0 = uvs.y(), v1 = uvs.w();

            // 1. push first strip segment
            if (lastUp == null) {
                pushVertex(buffer, light, up, faceNormal, tr, tg, tb, ta, u0, v0);
                pushVertex(buffer, light, up, faceNormal, tr, tg, tb, ta, u0, v0);
            }

            // 2. push next segment
            pushVertex(buffer, light, up, faceNormal, tr, tg, tb, ta, u0, v0);
            pushVertex(buffer, light, down, faceNormal, tr, tg, tb, ta, u0, v1);

            // 保留 last
            lastUp = up;
            lastNormal = normal;
            lastFaceNormal = faceNormal;
        }

        // handle head segment
        int headIndex = size - 1;
        // 修改 head segment 处理
        if (headIndex > 0 && lastNormal != null) {
            Vector3f head = tails.getPosition(headIndex);

            // 注意这里，直接用 lastNormal

            Vector3f up = new Vector3f(head).add(new Vector3f(lastNormal).mul(tails.getWidth(headIndex))).sub(cameraPos);
            Vector3f down = new Vector3f(head).add(new Vector3f(lastNormal).mul(-tails.getWidth(headIndex))).sub(cameraPos);

            var headColor = tails.getColor(headIndex);
            float ta = color.w() * headColor.w();
            float tr = color.x() * headColor.x();
            float tg = color.y() * headColor.y();
            float tb = color.z() * headColor.z();

            Vector4f uvs = particle.getUVs(headIndex - 1, size, partialTicks);
            float u1 = uvs.z(), v0 = uvs.y(), v1 = uvs.w();

            // 继续用之前的 u1、v0、v1
            pushVertex(buffer, light, up, lastFaceNormal, tr, tg, tb, ta, u1, v0);
            pushVertex(buffer, light, down, lastFaceNormal, tr, tg, tb, ta, u1, v1);
            lastUp = up;
        }

        // 3. **Degenerate triangle 插入**
        if (lastUp != null) {
            pushVertex(buffer, light, lastUp, lastFaceNormal, 0, 0, 0, 0, 0, 0); // 重复点1
            pushVertex(buffer, light, lastUp, lastFaceNormal, 0, 0, 0, 0, 0, 0); // 重复点2
        }

        if (pushHead) {
            tails.removeLast();
        }
    }

    private static void pushVertex(VertexConsumer buffer, int light, Vector3f pos, Vector3f normal, float r, float g, float b, float a, float u, float v) {
        buffer.addVertex(pos.x, pos.y, pos.z).setUv(u, v).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
    }
}
