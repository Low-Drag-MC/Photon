package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;

/**
 * Renders {@link TileParticle}s: the single entry point for both the CPU vertex path
 * ({@link #renderQueue}) and the GPU-instanced path ({@link #uploadInstances}/{@link #drawInstanced},
 * backed by {@link ParticleInstanceRenderer} for GL resources). Both paths share the same
 * billboard/stretched/model orientation math, so they stay visually identical by construction.
 * The particle itself only holds data and simulation.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class TileParticleRenderer {
    public static final Direction[] MODEL_SIDES = TileParticle.MODEL_SIDES;

    private final ParticleConfig config;
    private final ParticleInstanceRenderer instanceBackend;

    public TileParticleRenderer(ParticleConfig config) {
        this.config = config;
        this.instanceBackend = new ParticleInstanceRenderer(config);
    }

    // ---------------------------------------------------------------------
    // CPU path
    // ---------------------------------------------------------------------

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        if (config.renderer.getRenderMode() == ParticleRendererSetting.Mode.None) {
            return;
        }
        for (var particle : particles) {
            if (particle instanceof TileParticle tileParticle && tileParticle.getDelay() <= 0) {
                renderParticle(buffer, tileParticle, camera, partialTicks);
            }
        }
    }

    private void renderParticle(@Nonnull VertexConsumer buffer, TileParticle particle, Camera camera, float partialTicks) {
        var vec3 = camera.getPosition();

        var localPos = particle.getLocalPos(partialTicks).mulPosition(particle.getSpaceTransform());
        var x = (float) (localPos.x - vec3.x);
        var y = (float) (localPos.y - vec3.y);
        var z = (float) (localPos.z - vec3.z);

        var color = particle.getRealColor(partialTicks);
        var r = color.x();
        var g = color.y();
        var b = color.z();
        var a = color.w();

        var light = particle.getRealLight(partialTicks);

        var rotation = particle.getRealRotation(partialTicks);
        var renderMode = config.renderer.getRenderMode();

        var size = particle.getRealSize(partialTicks);

        if (renderMode == ParticleRendererSetting.Mode.Model) {
            var transform = new Matrix4f().translate(x, y, z)
                    .rotate(computeModelQuaternion(particle, rotation))
                    .scale(size.mul(particle.getSpaceScale()))
                    .translate(-0.5f, -0.5f, -0.5f);
            // draw 3d model
            var model = config.renderer.getModel();
            for (var side : MODEL_SIDES) {
                var brightness = (side != null && config.renderer.isShade()) ? switch (side) {
                    case DOWN, UP:
                        yield 0.9F;
                    case NORTH:
                    case SOUTH:
                        yield 0.8F;
                    case WEST:
                    case EAST:
                        yield 0.6F;
                } : 1f;
                var quads = model.renderModel(null, null, null, side, particle.getRandomSource(), ModelData.EMPTY, null);
                for (var quad : quads) {
                    putBulkData(transform, buffer, quad, brightness, r, g, b, a, light);
                }
            }
        } else {
            Quaternionf quaternion;
            float finalSizeX = size.x;
            float finalSizeY = size.y;
            float finalSizeZ = size.z;
            var spaceScale = particle.getSpaceScale();

            if (renderMode == ParticleRendererSetting.Mode.StretchedBillboard) {
                var frame = computeStretchedFrame(particle, localPos, vec3.x, vec3.y, vec3.z, size, spaceScale);
                quaternion = frame.rotation;
                finalSizeX = frame.stretchedSizeX;
                x -= frame.offsetX;
                y -= frame.offsetY;
                z -= frame.offsetZ;
            } else {
                quaternion = computeBillboardQuaternion(particle, renderMode, camera, partialTicks, rotation);
            }

            var rawVertexes = new Vector3f[]{
                    new Vector3f(1.0F, -1.0F, 0.0F),
                    new Vector3f(1.0F, 1.0F, 0.0F),
                    new Vector3f(-1.0F, 1.0F, 0.0F),
                    new Vector3f(-1.0F, -1.0F, 0.0F),
            };
            var normal = new Vector3f(0, 0, 1);

            for (var i = 0; i < 4; ++i) {
                var vertex = rawVertexes[i];
                vertex.mul(finalSizeX, finalSizeY, finalSizeZ);
                vertex = quaternion.transform(vertex);
                vertex.mul(spaceScale);
                vertex.add(x, y, z);
            }

            normal = quaternion.transform(normal);

            var uvs = particle.getRealUVs(partialTicks);
            var u0 = uvs.x();
            var v0 = uvs.y();
            var u1 = uvs.z();
            var v1 = uvs.w();

            buffer.addVertex(rawVertexes[0].x(), rawVertexes[0].y(), rawVertexes[0].z()).setUv(u1, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
            buffer.addVertex(rawVertexes[1].x(), rawVertexes[1].y(), rawVertexes[1].z()).setUv(u1, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
            buffer.addVertex(rawVertexes[2].x(), rawVertexes[2].y(), rawVertexes[2].z()).setUv(u0, v0).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
            buffer.addVertex(rawVertexes[3].x(), rawVertexes[3].y(), rawVertexes[3].z()).setUv(u0, v1).setColor(r, g, b, a).setLight(light).setNormal(normal.x, normal.y, normal.z);
        }
    }

    private void putBulkData(Matrix4f transform, VertexConsumer buffer, BakedQuad quad, float brightness, float red, float green, float blue, float alpha, int light) {
        int[] vertices = quad.getVertices();
        int points = vertices.length / 8;

        try (MemoryStack memoryStack = MemoryStack.stackPush()) {
            var byteBuffer = memoryStack.malloc(DefaultVertexFormat.BLOCK.getVertexSize());
            var intBuffer = byteBuffer.asIntBuffer();

            var u0 = quad.getSprite().getU0();
            var v0 = quad.getSprite().getV0();
            var u1 = quad.getSprite().getU1();
            var v1 = quad.getSprite().getV1();
            var uw = u1 - u0;
            var vh = v1 - v0;
            var pivotPoint = config.renderer.getModelPivot();

            for (int k = 0; k < points; ++k) {
                intBuffer.clear();
                intBuffer.put(vertices, k * 8, 8);
                var x = byteBuffer.getFloat(0) + pivotPoint.x; // 0
                var y = byteBuffer.getFloat(4) + pivotPoint.y; // 1
                var z = byteBuffer.getFloat(8) + pivotPoint.z; // 2
                var u = byteBuffer.getFloat(16); // 4 u
                var v = byteBuffer.getFloat(20); // 5 v
                var normalData = byteBuffer.getInt(IQuadTransformer.NORMAL * 4);
                float nX = ((byte) normalData      ) / 127.0f;
                float nY = ((byte)(normalData>>8 )) / 127.0f;
                float nZ = ((byte)(normalData>>16)) / 127.0f;
                if (!config.renderer.isUseBlockUV()) {
                    u =  (u - u0) / uw;
                    v =  (v - v0) / vh;
                }

                var pos = transform.transform(new Vector4f(x, y, z, 1.0F));
                var normalMat = transform.normal(new Matrix3f());
                var normal = new Vector3f(nX, nY, nZ).mul(normalMat).normalize();

                buffer.addVertex(pos.x, pos.y, pos.z);
                buffer.setColor(red * brightness, green * brightness, blue * brightness, alpha);
                buffer.setUv(u, v);
                buffer.setLight(light);
                buffer.setNormal(normal.x, normal.y, normal.z);
            }
        }
    }

    // ---------------------------------------------------------------------
    // instanced path
    // ---------------------------------------------------------------------

    /**
     * Fill and upload the per-instance data for this pass's particles. Returns true if any
     * instance was uploaded (the VAO is left bound for {@link #drawInstanced}).
     */
    public boolean uploadInstances(Collection<IParticle> particles, Camera camera, float partialTicks) {
        var buffer = instanceBackend.beginUpload(particles.size());
        if (buffer == null) return false;

        var instanceCount = 0;
        var vec3 = camera.getPosition();
        var renderMode = config.renderer.getRenderMode();
        for (var p : particles) {
            if (!(p instanceof TileParticle particle) || particle.getDelay() > 0) continue;
            instanceCount++;
            var localPos = particle.getLocalPos(partialTicks).mulPosition(particle.getSpaceTransform());
            var x = (float) (localPos.x - vec3.x);
            var y = (float) (localPos.y - vec3.y);
            var z = (float) (localPos.z - vec3.z);

            var color = particle.getRealColor(partialTicks);
            var rotation = particle.getRealRotation(partialTicks);
            var size = particle.getRealSize(partialTicks);
            var scale = particle.getSpaceScale();
            var light = particle.getRealLight(partialTicks);

            if (renderMode == ParticleRendererSetting.Mode.Model) {
                var quaternion = computeModelQuaternion(particle, rotation);
                // pos vec3
                buffer.put(x).put(y).put(z);
                // scale vec3
                buffer.put(scale.x * size.x).put(scale.y * size.y).put(scale.z * size.z);
                // rot quat (vec4)
                buffer.put(quaternion.x).put(quaternion.y).put(quaternion.z).put(quaternion.w);
                // color vec4
                buffer.put(color.x).put(color.y).put(color.z).put(color.w);
                // light int
                buffer.put(Float.intBitsToFloat(light));
            } else {
                var uvs = particle.getRealUVs(partialTicks);

                Quaternionf quaternion;
                float finalSizeX = size.x;
                float finalSizeY = size.y;
                if (renderMode == ParticleRendererSetting.Mode.StretchedBillboard) {
                    var frame = computeStretchedFrame(particle, localPos, vec3.x, vec3.y, vec3.z, size, scale);
                    quaternion = frame.rotation;
                    finalSizeX = frame.stretchedSizeX;
                    x -= frame.offsetX;
                    y -= frame.offsetY;
                    z -= frame.offsetZ;
                } else {
                    quaternion = computeBillboardQuaternion(particle, renderMode, camera, partialTicks, rotation);
                }

                // pos vec3
                buffer.put(x).put(y).put(z);
                // size vec2
                buffer.put(finalSizeX).put(finalSizeY);
                // scale vec3
                buffer.put(scale.x).put(scale.y).put(scale.z);
                // rot quat (vec4)
                buffer.put(quaternion.x).put(quaternion.y).put(quaternion.z).put(quaternion.w);
                // color vec4
                buffer.put(color.x).put(color.y).put(color.z).put(color.w);
                // uv vec4 (flip v)
                buffer.put(uvs.x).put(uvs.w).put(uvs.z).put(uvs.y);
                // light int
                buffer.put(Float.intBitsToFloat(light));
            }

            if (config.additionalGPUDataSetting.hasCustomData()) {
                // append additional data
                config.additionalGPUDataSetting.uploadData(particle, buffer, partialTicks);
            }
        }

        instanceBackend.endUpload(buffer, instanceCount);
        return instanceCount > 0;
    }

    public void drawInstanced(ShaderInstance shader) {
        instanceBackend.drawWithShader(shader);
    }

    /**
     * Full GL teardown of the instanced resources. Call when the render mode / model / instance
     * layout changes (not for capacity growth).
     */
    public void dispose() {
        instanceBackend.dispose();
    }

    // ---------------------------------------------------------------------
    // shared orientation math (used by BOTH the CPU and instanced paths)
    // ---------------------------------------------------------------------

    /**
     * Velocity-aligned stretched-billboard frame: rotation quaternion, the stretched X size
     * (lengthScale + speed * velocityScale) and the trailing camera-relative position offset.
     */
    private record StretchedFrame(Quaternionf rotation, float stretchedSizeX,
                                  float offsetX, float offsetY, float offsetZ) {
    }

    private StretchedFrame computeStretchedFrame(TileParticle particle, Vector3f worldPos,
                                                 double camX, double camY, double camZ,
                                                 Vector3f size, Vector3f spaceScale) {
        Vector3f vel = particle.getRealVelocity();
        float speed = vel.length();

        Vector3f right = new Vector3f();
        if (speed > 1e-5f) {
            right.set(vel).div(speed);
        } else {
            right.set(1, 0, 0);
        }

        Vector3f dirToCam = new Vector3f((float) (camX - worldPos.x), (float) (camY - worldPos.y), (float) (camZ - worldPos.z));
        if (dirToCam.lengthSquared() > 1e-5f) {
            dirToCam.normalize();
        } else {
            dirToCam.set(0, 0, 1);
        }

        Vector3f up = new Vector3f();
        dirToCam.cross(right, up);

        if (up.lengthSquared() < 1e-5f) {
            if (Math.abs(right.y) > 0.99f) {
                up.set(0, 0, 1).cross(right).normalize();
            } else {
                up.set(0, 1, 0).cross(right).normalize();
            }
        } else {
            up.normalize();
        }

        Vector3f forward = new Vector3f();
        right.cross(up, forward).normalize();

        Matrix3f mat = new Matrix3f(
                right.x,   right.y,   right.z,
                up.x,      up.y,      up.z,
                forward.x, forward.y, forward.z
        );
        var quaternion = new Quaternionf().setFromNormalized(mat);

        float stretch = config.renderer.getLengthScale() + speed * config.renderer.getVelocityScale();
        float stretchedSizeX = size.x * stretch;

        float offsetAmount = (stretchedSizeX - size.x) * spaceScale.x;
        return new StretchedFrame(quaternion, stretchedSizeX,
                right.x * offsetAmount, right.y * offsetAmount, right.z * offsetAmount);
    }

    private static Quaternionf computeBillboardQuaternion(TileParticle particle, ParticleRendererSetting.Mode renderMode,
                                                          Camera camera, float partialTicks, Vector3f rotation) {
        var quaternion = renderMode.quaternion.apply(particle, camera, partialTicks);
        if (!Vector3fHelper.isZero(rotation)) {
            quaternion = new Quaternionf(quaternion).rotateXYZ(rotation.x, rotation.y, rotation.z);
        }
        return quaternion;
    }

    private static Quaternionf computeModelQuaternion(TileParticle particle, Vector3f rotation) {
        return new Quaternionf().rotateXYZ(rotation.x, rotation.y, rotation.z).mul(particle.getSpaceRotation());
    }
}
