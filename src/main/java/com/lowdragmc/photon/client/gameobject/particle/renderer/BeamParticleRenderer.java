package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.render.PhotonCameraUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.FloatBuffer;
import java.util.Collection;

/**
 * Renders {@link BeamParticle}s: a single camera-facing quad from the emitter position to the
 * (raycast) beam end. Both the CPU vertex path ({@link #renderQueue}) and the 26.1 GPU-instanced
 * path ({@link #fillInstances}) sample the beam through the same {@link #sampleBeam} helper; the
 * quad expansion itself is mirrored between {@link #renderBeam} and the BEAM_INSTANCE branch of
 * photon:particle.glsl. The raycast end resolution stays on the CPU in both paths.
 */
@ParametersAreNonnullByDefault
public class BeamParticleRenderer {

    /** Per-frame beam sampling shared by the CPU and instanced paths (world-space from/end). */
    private record BeamFrame(Vector3f from, Vector3f end,
                             float u0, float v0, float u1, float v1,
                             float width, int light,
                             float r, float g, float b, float a) {
    }

    public BeamParticleRenderer() {
    }

    private static BeamFrame sampleBeam(BeamParticle particle, Camera camera, float partialTicks) {
        var from = particle.getWorldPos();
        var end = particle.getRealEnd(camera, from);

        var offset = -particle.getRealEmit(partialTicks);
        var uvs = particle.getRealUVs(partialTicks);
        var color = particle.getRealColor(partialTicks);

        return new BeamFrame(from, end,
                uvs.x + offset, uvs.y, uvs.z + offset, uvs.w,
                particle.getRealWidth(partialTicks),
                particle.getRealLight(partialTicks),
                color.x, color.y, color.z, color.w);
    }

    // ---------------------------------------------------------------------
    // CPU path
    // ---------------------------------------------------------------------

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        for (var particle : particles) {
            if (particle instanceof BeamParticle beamParticle && beamParticle.getDelay() <= 0) {
                renderBeam(buffer, beamParticle, camera, partialTicks);
            }
        }
    }

    private void renderBeam(@Nonnull VertexConsumer pBuffer, BeamParticle particle, @Nonnull Camera camera, float partialTicks) {
        // eye drives the beam-facing plane; origin is what emitted positions are relative to —
        // distinct in the editor scene, where the camera's position() is intentionally ZERO
        var eye = PhotonCameraUtils.facingEye(camera);
        var origin = PhotonCameraUtils.renderOrigin(camera);
        var frame = sampleBeam(particle, camera, partialTicks);
        var from = frame.from();
        var end = frame.end();
        var u0 = frame.u0();
        var v0 = frame.v0();
        var u1 = frame.u1();
        var v1 = frame.v1();
        var light = frame.light();
        var r = frame.r();
        var g = frame.g();
        var b = frame.b();
        var a = frame.a();

        var direction = new Vector3f(end).sub(from);

        var toO = new Vector3f(from).sub(eye);
        Vector3f n = new Vector3f(toO).cross(direction).normalize().mul(frame.width());
        Vector3f normal = new Vector3f(direction).cross(n).normalize();

        var p0 = new Vector3f(from).add(n).sub(origin);
        var p1 = new Vector3f(from).add(n.mul(-1)).sub(origin);
        var p3 = new Vector3f(end).add(n).sub(origin);
        var p4 = new Vector3f(end).add(n.mul(-1)).sub(origin);

        pBuffer.addVertex(p1.x, p1.y, p1.z).setUv(u0, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        pBuffer.addVertex(p0.x, p0.y, p0.z).setUv(u0, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        pBuffer.addVertex(p4.x, p4.y, p4.z).setUv(u1, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        pBuffer.addVertex(p3.x, p3.y, p3.z).setUv(u1, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
    }

    // ---------------------------------------------------------------------
    // instanced path
    // ---------------------------------------------------------------------

    /** The 26.1 instanced fill: 16 floats per beam (start3+halfWidth end3 color4 uv4 light1), plus the
     *  attribute tail / additional-data records when the pass needs them. */
    public int fillInstances(Collection<IParticle> particles, Camera camera, float partialTicks,
                             FloatBuffer out, AdditionalGPUDataSetting setting,
                             @Nullable FloatBuffer dataBuffer,
                             @Nullable FloatBuffer customBuffer) {
        var count = 0;
        // EYE-relative endpoints (see TrailParticleRenderer.fillInstances) + ModelOffset shift-back
        var cameraPos = PhotonCameraUtils.facingEye(camera);
        for (var p : particles) {
            if (!(p instanceof BeamParticle particle) || particle.getDelay() > 0) continue;
            count++;
            var frame = sampleBeam(particle, camera, partialTicks);
            out.put(frame.from().x - cameraPos.x).put(frame.from().y - cameraPos.y).put(frame.from().z - cameraPos.z);
            out.put(frame.width());
            out.put(frame.end().x - cameraPos.x).put(frame.end().y - cameraPos.y).put(frame.end().z - cameraPos.z);
            out.put(frame.r()).put(frame.g()).put(frame.b()).put(frame.a());
            out.put(frame.u0()).put(frame.v0()).put(frame.u1()).put(frame.v1());
            out.put(Float.intBitsToFloat(frame.light()));
            // legacy per-channel attributes (custom shaders) + the packed records (shadergraph) — 1.21 parity
            if (setting.hasAttribs()) {
                setting.uploadAttribs(particle, out, partialTicks);
            }
            if (dataBuffer != null) {
                setting.uploadDataRecord(particle, dataBuffer, partialTicks);
            }
            if (customBuffer != null) {
                setting.uploadCustomRecord(particle, customBuffer, partialTicks);
            }
        }
        return count;
    }

}
