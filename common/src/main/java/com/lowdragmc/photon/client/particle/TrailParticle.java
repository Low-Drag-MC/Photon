package com.lowdragmc.photon.client.particle;

import com.lowdragmc.lowdraglib.utils.Vector3;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Vector4f;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.util.Mth;
import org.apache.commons.lang3.function.TriFunction;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * @author KilaBash
 * @date 2022/05/30
 * @implNote TrailParticle
 */
@Environment(EnvType.CLIENT)
public abstract class TrailParticle extends LParticle {
    public enum UVMode {
        Stretch,
        Tile
    }
    @Setter @Getter
    protected int maxTail;
    @Setter @Getter
    protected float width;
    @Getter
    private float minimumVertexDistance = 0.01f;
    @Getter
    private float squareDist = minimumVertexDistance * minimumVertexDistance;
    @Setter @Getter
    protected boolean dieWhenRemoved = true;
    @Setter @Getter
    protected UVMode uvMode = UVMode.Stretch;
    @Setter
    protected BiPredicate<TrailParticle, Vector3> onAddTail;
    @Setter
    protected Predicate<TrailParticle> onRemoveTails;
    @Setter
    protected TriFunction<TrailParticle, Integer, Float, Float> dynamicTailWidth;
    @Setter
    protected TriFunction<TrailParticle, Integer, Float, Vector4f> dynamicTailColor;
    @Setter
    protected TriFunction<LParticle, Integer, Float, Vector4f> dynamicTailUVs;
    //runtime
    @Getter
    protected LinkedList<Vector3> tails = new LinkedList<>();


    protected TrailParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        maxTail = 40;
        width = 0.5f;
        cull = false;
    }

    protected TrailParticle(ClientLevel level, double x, double y, double z, double sX, double sY, double sZ) {
        super(level, x, y, z, sX, sY, sZ);
        cull = false;
    }

    public void setMinimumVertexDistance(float minimumVertexDistance) {
        this.minimumVertexDistance = minimumVertexDistance;
        this.squareDist = minimumVertexDistance * minimumVertexDistance;
    }

    protected boolean shouldAddTail(Vector3 newTail) {
        if (onAddTail != null) {
            return onAddTail.test(this, newTail);
        }
        return true;
    }

    protected void removeTails() {
        if (isRemoved()) {
            tails.pollFirst();
        } else if (onRemoveTails == null || !onRemoveTails.test(this)) {
            while (tails.size() > maxTail) {
                tails.removeFirst();
            }
        }
    }

    @Override
    public void tick() {
        if (delay > 0) {
            delay--;
            return;
        }

        updateOrigin();

        Vector3 tail = getTail();
        if (!isRemoved() && shouldAddTail(tail)) {
            boolean shouldAdd = true;
            if (squareDist > 0) {
                var last = tails.pollLast();
                if (last != null) {
                    var distLast = tail.copy().subtract(last).magSquared();
                    if (distLast < squareDist) {
                        shouldAdd = false;
                        tails.addLast(last);
                    } else {
                        var last2 = tails.peekLast();
                        if (last2 != null) {
                            var distLast2 = tail.copy().subtract(last2).magSquared();
                            if (distLast2 < squareDist) {
                                shouldAdd = false;
                            } else if (distLast < distLast2) {
                                tails.addLast(last);
                            }
                        } else {
                            tails.addLast(last);
                        }
                    }
                }
            }
            if (shouldAdd) {
                addNewTail(tail);
            }
        }
        removeTails();

        if (this.age++ >= this.lifetime && lifetime > 0) {
            this.remove();
        }

        update();

        if (lifetime > 0) {
            t = 1.0f * age / this.lifetime;
        }
    }

    protected void addNewTail(Vector3 tail) {
        this.tails.add(tail);
    }

    @Override
    public boolean isAlive() {
        if (this.removed) {
            if (dieWhenRemoved) return false;
            return !tails.isEmpty();
        }
        return true;
    }

    protected Vector3 getTail() {
        return new Vector3(this.xo, this.yo, this.zo);
    }

    public void renderInternal(@Nonnull VertexConsumer buffer, @Nonnull Camera camera, float partialTicks) {
        double x = (Mth.lerp(partialTicks, this.xo, this.x));
        double y = (Mth.lerp(partialTicks, this.yo, this.y));
        double z = (Mth.lerp(partialTicks, this.zo, this.z));

        if (positionAddition != null) {
            var addition = positionAddition.apply(this, partialTicks);
            x += addition.x;
            y += addition.y;
            z += addition.z;
        }

        Vector3 cameraPos = new Vector3(camera.getPosition());
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
        int light = dynamicLight == null ? this.getLight(partialTicks) : dynamicLight.apply(this, partialTicks);

        var pushHead = true;
        if (tails.peekLast() != null && tails.peekLast().equals(new Vector3(x, y, z))) {
            pushHead = false;
        } else {
            tails.addLast(new Vector3(x, y, z));
        }

        var iter = tails.iterator();
        Vector3 lastUp = null, lastDown = null, lastNormal = null, tail = null;
        float la = a;
        float lr = r;
        float lg = g ;
        float lb = b;
        int tailIndex = 0;
        while (iter.hasNext()) {
            var nextTail = iter.next();
            if (tail == null) {
                tail = nextTail;
            } else {
                float width = getWidth(tailIndex, partialTicks);

                var vec = nextTail.copy().subtract(tail);
                var normal = vec.crossProduct(tail.copy().subtract(cameraPos)).normalize();
                if (lastNormal == null) {
                    lastNormal= normal;
                }

                var avgNormal = lastNormal.add(normal).divide(2);
                var up = tail.copy().add(avgNormal.copy().multiply(width)).subtract(cameraPos);
                var down = tail.copy().add(avgNormal.copy().multiply(-width)).subtract(cameraPos);

                float ta = a;
                float tr = r;
                float tg = g ;
                float tb = b;
                if (dynamicTailColor != null) {
                    var color = dynamicTailColor.apply(this, tailIndex, partialTicks);
                    ta *= color.w();
                    tr *= color.x();
                    tg *= color.y();
                    tb *= color.z();
                }

                if (lastUp != null) {
                    var uvs = getUVs(tailIndex - 1, partialTicks);
                    float u0 = uvs.x(), u1 = uvs.z(), v0 = uvs.y(), v1 = uvs.w();
                    pushBuffer(buffer, light, lastUp, lastDown, la, lr, lg, lb, u0, u1, v0, v1, ta, tr, tg, tb, up, down);
                }

                la = ta;
                lr = tr;
                lg = tg;
                lb = tb;
                lastUp = up;
                lastDown = down;
                tail = nextTail;
                lastNormal = normal;
                tailIndex++;
            }
        }
        // add head
        if (tail != null && lastNormal != null) {
            float width = getWidth(tailIndex, partialTicks);
            var uvs = getUVs(tailIndex - 1, partialTicks);
            float u0 = uvs.x(), u1 = uvs.z(), v0 = uvs.y(), v1 = uvs.w();

            float ta = a;
            float tr = r;
            float tg = g ;
            float tb = b;
            if (dynamicTailColor != null) {
                var color = dynamicTailColor.apply(this, tailIndex, partialTicks);
                ta *= color.w();
                tr *= color.x();
                tg *= color.y();
                tb *= color.z();
            }
            var up = tail.copy().add(lastNormal.copy().multiply(width)).subtract(cameraPos);
            var down = tail.copy().add(lastNormal.copy().multiply(-width)).subtract(cameraPos);
            pushBuffer(buffer, light, lastUp, lastDown, la, lr, lg, lb, u0, u1, v0, v1, ta, tr, tg, tb, up, down);
        }
        if (pushHead) {
            tails.pollLast();
        }
    }

    private void pushBuffer(@Nonnull VertexConsumer buffer, int light, Vector3 lastUp, Vector3 lastDown, float la, float lr, float lg, float lb, float u0, float u1, float v0, float v1, float ta, float tr, float tg, float tb, Vector3 up, Vector3 down) {
        buffer.vertex(down.x, down.y, down.z).uv(u1, v1).color(tr, tg, tb, ta).uv2(light).endVertex();
        buffer.vertex(up.x, up.y, up.z).uv(u1, v0).color(tr, tg, tb, ta).uv2(light).endVertex();
        buffer.vertex(lastUp.x, lastUp.y, lastUp.z).uv(u0, v0).color(lr, lg, lb, la).uv2(light).endVertex();

        buffer.vertex(lastUp.x, lastUp.y, lastUp.z).uv(u0, v0).color(lr, lg, lb, la).uv2(light).endVertex();
        buffer.vertex(lastDown.x, lastDown.y, lastDown.z).uv(u0, v1).color(lr, lg, lb, la).uv2(light).endVertex();
        buffer.vertex(down.x, down.y, down.z).uv(u1, v1).color(tr, tg, tb, ta).uv2(light).endVertex();
    }

    public Vector4f getUVs(int tailIndex, float partialTicks) {
        float u0, u1, v0, v1;
        if (getUvMode() == UVMode.Stretch) {
            if (dynamicTailUVs != null) {
                var uvs = dynamicTailUVs.apply(this, tailIndex, partialTicks);
                u0 = uvs.x();
                v0 = uvs.y();
                u1 = uvs.z();
                v1 = uvs.w();
            } else {
                u0 = this.getU0(tailIndex, partialTicks);
                u1 = this.getU1(tailIndex, partialTicks);
                v0 = this.getV0(tailIndex, partialTicks);
                v1 = this.getV1(tailIndex, partialTicks);
            }
        } else {
            if (dynamicUVs != null) {
                var uvs = dynamicUVs.apply(this, partialTicks);
                u0 = uvs.x();
                v0 = uvs.y();
                u1 = uvs.z();
                v1 = uvs.w();
            } else {
                u0 = this.getU0(partialTicks);
                u1 = this.getU1(partialTicks);
                v0 = this.getV0(partialTicks);
                v1 = this.getV1(partialTicks);
            }
        }
        return new Vector4f(u0, v0, u1, v1);
    }

    public float getWidth(int tail, float pPartialTicks) {
        if (dynamicTailWidth != null) {
            return dynamicTailWidth.apply(this, tail, pPartialTicks);
        }
        return width;
    }

    protected float getU0(int tail, float pPartialTicks) {
        return (tail) / (tails.size() - 1f);
    }

    protected float getV0(int tail, float pPartialTicks) {
        return 0;
    }

    protected float getU1(int tail, float pPartialTicks) {
        return (tail + 1) / (tails.size() - 1f);
    }

    protected float getV1(int tail, float pPartialTicks) {
        return 1;
    }

    @Override
    public void resetParticle() {
        super.resetParticle();
        tails.clear();
    }

    public static class Basic extends TrailParticle {
        @Getter
        final ParticleRenderType renderType;

        public Basic(ClientLevel level, double x, double y, double z, ParticleRenderType renderType) {
            super(level, x, y, z);
            this.renderType = renderType;
        }

        public Basic(ClientLevel level, double x, double y, double z, double sX, double sY, double sZ, ParticleRenderType renderType) {
            super(level, x, y, z, sX, sY, sZ);
            this.renderType = renderType;
        }

    }

}
