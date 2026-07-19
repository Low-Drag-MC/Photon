package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.trail.TrailConfig;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;

/**
 * Renders {@link TrailParticle}s as a camera-facing ribbon. The CPU path ({@link #renderQueue})
 * emits a triangle strip with degenerate-triangle stitching between trails — kept byte-identical
 * to the historical implementation. The GPU-instanced path ({@link #uploadInstances}/
 * {@link #drawInstanced}, backed by {@link TrailInstanceRenderer}) uploads one instance per
 * segment via {@link #collectRenderPoints}, which replicates the CPU point selection quirks
 * (head push with the oldest tail's color/width, dead-prefix skipping, oldest-live-tail
 * partial-tick lerp); the ribbon expansion itself is mirrored in the TRAIL_INSTANCE branch of
 * photon:particle.glsl. Render-thread only (scratch state).
 */
@ParametersAreNonnullByDefault
public class TrailParticleRenderer {

    private final TrailConfig config;
    private final TrailInstanceRenderer instanceBackend;

    // ------------------------------------------------------------------
    // per-trail collection scratch (reused; render thread only)
    // ------------------------------------------------------------------
    private float[] px = new float[0], py = new float[0], pz = new float[0];
    private float[] width = new float[0];
    private float[] cr = new float[0], cg = new float[0], cb = new float[0], ca = new float[0];
    private float[] u = new float[0];
    private float[] pointT = new float[0], pointLife = new float[0];
    private float scratchV0, scratchV1;
    private int scratchLight;

    public TrailParticleRenderer(TrailConfig config) {
        this.config = config;
        this.instanceBackend = new TrailInstanceRenderer(config);
    }

    // ---------------------------------------------------------------------
    // CPU path (unchanged strip emission)
    // ---------------------------------------------------------------------

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        for (var particle : particles) {
            if (particle instanceof TrailParticle trailParticle && trailParticle.getDelay() <= 0) {
                renderTrail(buffer, trailParticle, camera.position().toVector3f(), partialTicks);
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

    // ---------------------------------------------------------------------
    // instanced path
    // ---------------------------------------------------------------------

    /**
     * Fill and upload one instance per rendered trail segment, with the per-point data uploaded
     * once into the point buffer texture (vertex pulling). Returns true if any instance was
     * uploaded (the VAO is left bound for {@link #drawInstanced}). Like the tile path, the
     * additional-data selection comes from the pass-owning config (batched passes share it).
     */
    public boolean uploadInstances(Collection<IParticle> particles, Camera camera, float partialTicks) {
        // capacity pre-scan (upper bounds: one segment per tail incl. the pushed head;
        // points = tails + pushed head + 2 pads)
        var instanceCapacity = 0;
        var pointCapacity = 0;
        for (var p : particles) {
            if (p instanceof TrailParticle trail && trail.getDelay() <= 0) {
                instanceCapacity += trail.getTails().size();
                pointCapacity += trail.getTails().size() + 3;
            }
        }
        if (instanceCapacity == 0) return false;

        var buffer = instanceBackend.beginUpload(instanceCapacity);
        if (buffer == null) return false;
        var pointBuffer = instanceBackend.beginPointUpload(pointCapacity);
        if (pointBuffer == null) return false;

        var setting = config.additionalGPUDataSetting;
        var dataBuffer = setting.hasDataRecord() ? instanceBackend.beginDataUpload(instanceCapacity) : null;
        var customBuffer = setting.hasCustomRecord() ? instanceBackend.beginCustomUpload(instanceCapacity) : null;
        var instanceCount = 0;
        var pointCount = 0;
        var cameraPos = camera.position().toVector3f();
        for (var p : particles) {
            if (!(p instanceof TrailParticle trail) || trail.getDelay() > 0) continue;
            var count = collectRenderPoints(trail, cameraPos, partialTicks);
            if (count < 2) continue;

            // padded point block: bitwise copies of the first/last point keep the shader's
            // neighbor fetches (c-1 / c+2) inside this trail and make its endpoint-fallback
            // equality tests hold
            putPointTexels(pointBuffer, 0);
            for (int j = 0; j < count; j++) {
                putPointTexels(pointBuffer, j);
            }
            putPointTexels(pointBuffer, count - 1);
            var base = pointCount + 1; // index of the first real point
            pointCount += count + 2;

            for (int i = 0; i < count - 1; i++) {
                // iSeg ivec2 (point index of the segment's curr point, packed light)
                buffer.put(Float.intBitsToFloat(base + i)).put(Float.intBitsToFloat(scratchLight));
                // iSegV vec2 (v0, v1)
                buffer.put(scratchV0).put(scratchV1);

                // stage the per-point channel pair (point_t/point_life) before any upload — this is also
                // the representative segment t/length the per-segment custom-data sampling reads (curr end)
                if (setting.hasAttribs() || dataBuffer != null || customBuffer != null) {
                    setting.setSegmentValues(pointT[i], pointT[i + 1], pointLife[i], pointLife[i + 1]);
                }
                if (setting.hasAttribs()) {
                    setting.uploadAttribs(trail, buffer, partialTicks);
                }
                if (dataBuffer != null) {
                    setting.uploadDataRecord(trail, dataBuffer, partialTicks);
                }
                if (customBuffer != null) {
                    setting.uploadCustomRecord(trail, customBuffer, partialTicks);
                }
                instanceCount++;
            }
        }

        if (dataBuffer != null) {
            instanceBackend.endDataUpload(dataBuffer);
        }
        if (customBuffer != null) {
            instanceBackend.endCustomUpload(customBuffer);
        }
        instanceBackend.endPointUpload(pointBuffer);
        instanceBackend.endUpload(buffer, instanceCount);
        return instanceCount > 0;
    }

    /** Writes one point's {@link TrailInstanceRenderer#POINT_TEXELS} texels from the scratch arrays. */
    private void putPointTexels(java.nio.FloatBuffer pointBuffer, int j) {
        // T0: pos + width
        pointBuffer.put(px[j]).put(py[j]).put(pz[j]).put(width[j]);
        // T1: premultiplied color
        pointBuffer.put(cr[j]).put(cg[j]).put(cb[j]).put(ca[j]);
        // T2: u + spare
        pointBuffer.put(u[j]).put(0f).put(0f).put(0f);
    }

    /**
     * Builds the camera-relative render-point list of one trail into the scratch arrays,
     * mirroring the CPU strip's point selection exactly: pushed head (lifeTime 100, oldest
     * tail's color/width), dead-prefix + passed-segment skipping, and the partial-tick lerp of
     * the oldest live point. Returns the number of points (0 or 1 = nothing to render).
     */
    private int collectRenderPoints(TrailParticle particle, Vector3f cameraPos, float partialTicks) {
        var tails = particle.getTails();
        var rawTails = particle.getRawTails();
        var color = particle.getRealColor(partialTicks);
        scratchLight = particle.getRealLight(partialTicks);

        Vector3f headPos = particle.getHeadPosition(partialTicks);
        int tailSize = tails.size();
        boolean pushHead = true;
        if (tailSize > 0) {
            Vector3f lastPos = tails.getPosition(tailSize - 1);
            if (lastPos.equals(headPos)) {
                pushHead = false;
            }
        }
        // logical point list = tails plus (optionally) the head; n mirrors the CPU strip's size
        int n = tailSize + (pushHead ? 1 : 0);
        if (n < 2) return 0;

        var lerpDur = 0f;
        var t = 0f;
        if (rawTails.size() > 1) {
            lerpDur = rawTails.getLifeTime(1) - rawTails.getLifeTime(0);
            t = 1 - (rawTails.getLifeTime(0) + 1 - partialTicks) / lerpDur;
        }

        // first rendered segment k — the skipped segments form a prefix (lifetimes grow with index)
        int k = -1;
        boolean lerpFirst = false;
        float lerpFactor = 0;
        for (int i = 0; i < n - 1; i++) {
            float lifeI = (pushHead && i == n - 1) ? 100 : tails.getLifeTime(i);
            float lifeI1 = (pushHead && i + 1 == n - 1) ? 100 : tails.getLifeTime(i + 1);
            if (lifeI - partialTicks < 0f && lifeI1 - partialTicks < 0) continue;
            var currT = lifeI / lerpDur;
            var nextT = lifeI1 / lerpDur;
            if (nextT < t) continue;
            k = i;
            if (lerpDur > 0 && currT <= t && t <= nextT) {
                lerpFirst = true;
                lerpFactor = (t - currT) / (nextT - currT);
            }
            break;
        }
        if (k == -1) return 0;

        int count = n - k;
        ensureScratch(count);
        float lifetime = Math.max(particle.getLifetimeSupplier().get(), 1.0E-5f);
        float uLast = 0;

        for (int j = k; j < n; j++) {
            int idx = j - k;
            boolean isPushedHead = pushHead && j == n - 1;

            Vector3f pos = isPushedHead ? headPos : tails.getPosition(j);
            if (j == k && lerpFirst) {
                Vector3f next = (pushHead && j + 1 == n - 1) ? headPos : tails.getPosition(j + 1);
                pos = new Vector3f(pos).lerp(next, lerpFactor);
            }
            px[idx] = pos.x - cameraPos.x;
            py[idx] = pos.y - cameraPos.y;
            pz[idx] = pos.z - cameraPos.z;

            // the pushed head reuses the OLDEST tail's color/width (CPU quirk — keep it)
            float tcr, tcg, tcb, tca, tw;
            if (isPushedHead) {
                if (tailSize > 0) {
                    var headColor = tails.getColor(0);
                    tcr = headColor.x();
                    tcg = headColor.y();
                    tcb = headColor.z();
                    tca = headColor.w();
                    tw = tails.getWidth(0);
                } else {
                    tcr = tcg = tcb = tca = 1;
                    tw = 0;
                }
            } else {
                var tailColor = tails.getColor(j);
                tcr = tailColor.x();
                tcg = tailColor.y();
                tcb = tailColor.z();
                tca = tailColor.w();
                tw = tails.getWidth(j);
            }
            cr[idx] = color.x() * tcr;
            cg[idx] = color.y() * tcg;
            cb[idx] = color.z() * tcb;
            ca[idx] = color.w() * tca;
            width[idx] = tw;

            // per-point u: segment j's u0; the head point continues with the last segment's u1
            if (j <= n - 2) {
                var uvs = particle.getUVs(j, n, partialTicks);
                u[idx] = uvs.x();
                if (j == k) {
                    scratchV0 = uvs.y();
                    scratchV1 = uvs.w();
                }
                if (j == n - 2) {
                    uLast = uvs.z();
                }
            } else {
                u[idx] = uLast;
            }

            // per-point channels
            pointT[idx] = j / (n - 1f);
            float life = isPushedHead ? 100 : tails.getLifeTime(j);
            pointLife[idx] = 1 - Math.min(Math.max(life / lifetime, 0f), 1f);
        }
        return count;
    }

    private void ensureScratch(int count) {
        if (px.length >= count) return;
        int capacity = Math.max(count, Math.max(px.length * 2, 64));
        px = new float[capacity];
        py = new float[capacity];
        pz = new float[capacity];
        width = new float[capacity];
        cr = new float[capacity];
        cg = new float[capacity];
        cb = new float[capacity];
        ca = new float[capacity];
        u = new float[capacity];
        pointT = new float[capacity];
        pointLife = new float[capacity];
    }

    // TODO(M2): drawInstanced — re-expressed as a RenderPass.drawIndexed(instanceCount) draw with
    // the material pipeline when the instancing backend moves off raw GL.

    /**
     * Full GL teardown of the instanced resources. Call when the instance layout changes
     * (not for capacity growth).
     */
    public void dispose() {
        instanceBackend.dispose();
    }
}
