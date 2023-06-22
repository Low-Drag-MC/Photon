package com.lowdragmc.photon.client.particle;

import com.lowdragmc.lowdraglib.utils.Vector3;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.BiFunction;

/**
 * @author KilaBash
 * @date 2022/06/15
 * @implNote BeamParticle
 */
@Environment(EnvType.CLIENT)
public abstract class BeamParticle extends LParticle {
    @Getter
    protected Vector3 end;
    @Setter @Getter
    protected float width;
    @Setter @Getter
    protected float emit;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Float> dynamicWidth = null;
    @Setter
    @Nullable
    protected BiFunction<LParticle, Float, Float> dynamicEmit = null;

    protected BeamParticle(ClientLevel level, Vector3 from, Vector3 end) {
        super(level, from.x, from.y, from.z);
        this.moveless = true;
        this.setBeam(from, end);
        width = 0.2f;
    }


    public void setBeam(Vector3 from, Vector3 end) {
        setPos(from, true);
        this.end = end;
    }

    public void renderInternal(@Nonnull VertexConsumer pBuffer, @Nonnull Camera camera, float partialTicks) {
        var cameraPos = new Vector3(camera.getPosition());
        var from = getPos(partialTicks);
        var end = new Vector3(from).add(this.end);

        float offset = - getEmit(partialTicks) * (getAge() + partialTicks);
        float u0 = getU0(partialTicks) + offset;
        float u1 = getU1(partialTicks) + offset;
        float v0 = getV0(partialTicks);
        float v1 = getV1(partialTicks);
        float beamHeight = getWidth(partialTicks);
        int light = dynamicLight == null ? this.getLight(partialTicks) : dynamicLight.apply(this, partialTicks);

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

        Vector3 toO;
        if (quaternion == null) {
            toO = new Vector3(from).subtract(cameraPos);
        } else {
            var rotation = quaternion.toXYZ();
            var zVec = Vector3.Z.copy();
            toO = zVec.rotate(rotation.x(), Vector3.X).rotate(rotation.y(), Vector3.Y).rotate(rotation.z(), Vector3.Z);
        }
        Vector3 n = new Vector3(toO).crossProduct(direction).normalize().multiply(beamHeight);


        var p0 = from.copy().add(n).subtract(cameraPos);
        var p1 = from.copy().add(n.multiply(-1)).subtract(cameraPos);
        var p3 = end.copy().add(n).subtract(cameraPos);
        var p4 = end.copy().add(n.multiply(-1)).subtract(cameraPos);

        pBuffer.vertex(p1.x, p1.y, p1.z).uv(u0, v0).color(r, g, b, a).uv2(light).endVertex();
        pBuffer.vertex(p0.x, p0.y, p0.z).uv(u0, v1).color(r, g, b, a).uv2(light).endVertex();
        pBuffer.vertex(p4.x, p4.y, p4.z).uv(u1, v1).color(r, g, b, a).uv2(light).endVertex();
        pBuffer.vertex(p3.x, p3.y, p3.z).uv(u1, v0).color(r, g, b, a).uv2(light).endVertex();
    }

    protected float getWidth(float pPartialTicks) {
        if (dynamicWidth != null) {
            return dynamicWidth.apply(this, pPartialTicks);
        }
        return width;
    }

    protected float getEmit(float pPartialTicks) {
        if (dynamicEmit != null) {
            return dynamicEmit.apply(this, pPartialTicks);
        }
        return emit;
    }

}
