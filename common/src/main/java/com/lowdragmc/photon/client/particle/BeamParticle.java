package com.lowdragmc.photon.client.particle;

import com.lowdragmc.lowdraglib.utils.Vector3;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nonnull;

/**
 * @author KilaBash
 * @date 2022/06/15
 * @implNote BeamParticle
 */
@Environment(EnvType.CLIENT)
public abstract class BeamParticle extends LParticle {
    @Getter
    protected Vector3 from, end;
    @Setter @Getter
    protected float width;
    @Setter @Getter
    protected float emit;

    protected BeamParticle(ClientLevel level, Vector3 from, Vector3 end) {
        super(level, from.x, from.y, from.z);
        this.setBeam(from, end);
        width = 0.5f;
    }

    public void setBeam(Vector3 from, Vector3 end) {
        this.from = from;
        this.end = end;
        setBoundingBox(new AABB(from.vec3(), end.vec3()));
    }

    public void renderInternal(@Nonnull VertexConsumer pBuffer, @Nonnull Camera camera, float partialTicks) {
        var cameraPos = new Vector3(camera.getPosition());

        float offset = - emit * (getAge() + partialTicks);
        float u0 = getU0(partialTicks) + offset;
        float u1 = getU1(partialTicks) + offset;
        float v0 = getV0(partialTicks);
        float v1 = getV1(partialTicks);
        float beamHeight = getWidth(partialTicks);
        int lightColor = getLight(partialTicks);

        float a = getAlpha(partialTicks);
        float r = getRed(partialTicks);
        float g = getGreen(partialTicks);
        float b = getBlue(partialTicks);
        if (dynamicColor != null){
            var color = dynamicColor.apply(this, partialTicks);
            a *= color.w();
            r *= color.x();
            g *= color.y();
            b *= color.z();
        }

        Vector3 direction = end.copy().subtract(from);

        Vector3 toO = from.copy().subtract(cameraPos);
        Vector3 n = toO.copy().crossProduct(direction).normalize().multiply(beamHeight);


        var p0 = from.copy().add(n).subtract(cameraPos);
        var p1 = from.copy().add(n.multiply(-1)).subtract(cameraPos);
        var p3 = end.copy().add(n).subtract(cameraPos);
        var p4 = end.copy().add(n.multiply(-1)).subtract(cameraPos);

        pBuffer.vertex(p1.x, p1.y, p1.z).uv(u0, v0).color(r, g, b, a).uv2(lightColor).endVertex();
        pBuffer.vertex(p0.x, p0.y, p0.z).uv(u0, v1).color(r, g, b, a).uv2(lightColor).endVertex();
        pBuffer.vertex(p4.x, p4.y, p4.z).uv(u1, v1).color(r, g, b, a).uv2(lightColor).endVertex();
        pBuffer.vertex(p3.x, p3.y, p3.z).uv(u1, v0).color(r, g, b, a).uv2(lightColor).endVertex();
    }

    protected float getWidth(float pPartialTicks) {
        return width;
    }

}
