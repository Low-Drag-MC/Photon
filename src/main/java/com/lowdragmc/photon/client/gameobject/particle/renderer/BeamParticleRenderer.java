package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;

/**
 * Renders {@link BeamParticle}s: a single camera-facing quad from the emitter position to the
 * (raycast) beam end. Stateless — shared singleton.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class BeamParticleRenderer {
    public static final BeamParticleRenderer INSTANCE = new BeamParticleRenderer();

    private BeamParticleRenderer() {
    }

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        for (var particle : particles) {
            if (particle instanceof BeamParticle beamParticle && beamParticle.getDelay() <= 0) {
                renderBeam(buffer, beamParticle, camera, partialTicks);
            }
        }
    }

    private void renderBeam(@Nonnull VertexConsumer pBuffer, BeamParticle particle, @Nonnull Camera camera, float partialTicks) {
        var cameraPos = camera.getPosition().toVector3f();
        var from = particle.getWorldPos();
        var end = particle.getRealEnd(camera, from);

        var offset = -particle.getRealEmit(partialTicks);
        var uvs = particle.getRealUVs(partialTicks);
        var u0 = uvs.x + offset;
        var u1 = uvs.z + offset;
        var v0 = uvs.y;
        var v1 = uvs.w;
        var beamHeight = particle.getRealWidth(partialTicks);
        var light = particle.getRealLight(partialTicks);

        var color = particle.getRealColor(partialTicks);
        var r = color.x;
        var g = color.y;
        var b = color.z;
        var a = color.w;

        var direction = new Vector3f(end).sub(from);

        var toO = new Vector3f(from).sub(cameraPos);
        Vector3f n = new Vector3f(toO).cross(direction).normalize().mul(beamHeight);
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
}
