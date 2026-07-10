package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;

/**
 * Renders {@link BeamParticle}s: a single camera-facing quad from the emitter position to the
 * (raycast) beam end. Both the CPU vertex path ({@link #renderQueue}) and the GPU-instanced path
 * ({@link #uploadInstances}/{@link #drawInstanced}, backed by {@link BeamInstanceRenderer})
 * sample the beam through the same {@link #sampleBeam} helper; the quad expansion itself is
 * mirrored between {@link #renderBeam} and the BEAM_INSTANCE branch of photon:particle.glsl.
 * The raycast end resolution stays on the CPU in both paths.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class BeamParticleRenderer {

    /** Per-frame beam sampling shared by the CPU and instanced paths (world-space from/end). */
    private record BeamFrame(Vector3f from, Vector3f end,
                             float u0, float v0, float u1, float v1,
                             float width, int light,
                             float r, float g, float b, float a) {
    }

    private final BeamConfig config;
    private final BeamInstanceRenderer instanceBackend;

    public BeamParticleRenderer(BeamConfig config) {
        this.config = config;
        this.instanceBackend = new BeamInstanceRenderer(config);
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
        var cameraPos = camera.getPosition().toVector3f();
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

        var toO = new Vector3f(from).sub(cameraPos);
        Vector3f n = new Vector3f(toO).cross(direction).normalize().mul(frame.width());
        Vector3f normal = new Vector3f(direction).cross(n).normalize();

        var p0 = new Vector3f(from).add(n).sub(cameraPos);
        var p1 = new Vector3f(from).add(n.mul(-1)).sub(cameraPos);
        var p3 = new Vector3f(end).add(n).sub(cameraPos);
        var p4 = new Vector3f(end).add(n.mul(-1)).sub(cameraPos);

        pBuffer.addVertex(p1.x, p1.y, p1.z).setUv(u0, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        pBuffer.addVertex(p0.x, p0.y, p0.z).setUv(u0, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        pBuffer.addVertex(p4.x, p4.y, p4.z).setUv(u1, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        pBuffer.addVertex(p3.x, p3.y, p3.z).setUv(u1, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
    }

    // ---------------------------------------------------------------------
    // instanced path
    // ---------------------------------------------------------------------

    /**
     * Fill and upload the per-instance data for this pass's beams. Returns true if any instance
     * was uploaded (the VAO is left bound for {@link #drawInstanced}). Like the tile path, the
     * additional-data selection comes from the pass-owning config (batched passes share it).
     */
    public boolean uploadInstances(Collection<IParticle> particles, Camera camera, float partialTicks) {
        var buffer = instanceBackend.beginUpload(particles.size());
        if (buffer == null) return false;

        var setting = config.additionalGPUDataSetting;
        var dataBuffer = setting.hasDataRecord() ? instanceBackend.beginDataUpload(particles.size()) : null;
        var instanceCount = 0;
        var cameraPos = camera.getPosition().toVector3f();
        for (var p : particles) {
            if (!(p instanceof BeamParticle particle) || particle.getDelay() > 0) continue;
            instanceCount++;
            var frame = sampleBeam(particle, camera, partialTicks);

            // iStart vec4 (camera-relative xyz + width)
            buffer.put(frame.from().x - cameraPos.x).put(frame.from().y - cameraPos.y).put(frame.from().z - cameraPos.z);
            buffer.put(frame.width());
            // iEnd vec3
            buffer.put(frame.end().x - cameraPos.x).put(frame.end().y - cameraPos.y).put(frame.end().z - cameraPos.z);
            // iColor vec4
            buffer.put(frame.r()).put(frame.g()).put(frame.b()).put(frame.a());
            // iUV vec4 (scroll baked in)
            buffer.put(frame.u0()).put(frame.v0()).put(frame.u1()).put(frame.v1());
            // iLight int
            buffer.put(Float.intBitsToFloat(frame.light()));

            if (setting.hasAttribs()) {
                setting.uploadAttribs(particle, buffer, partialTicks);
            }
            if (dataBuffer != null) {
                setting.uploadDataRecord(particle, dataBuffer, partialTicks);
            }
        }

        if (dataBuffer != null) {
            instanceBackend.endDataUpload(dataBuffer);
        }
        instanceBackend.endUpload(buffer, instanceCount);
        return instanceCount > 0;
    }

    public void drawInstanced(ShaderInstance shader) {
        instanceBackend.drawWithShader(shader);
    }

    /**
     * Full GL teardown of the instanced resources. Call when the instance layout changes
     * (not for capacity growth).
     */
    public void dispose() {
        instanceBackend.dispose();
    }
}
