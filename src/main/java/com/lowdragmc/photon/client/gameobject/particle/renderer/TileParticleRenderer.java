package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.FloatBuffer;
import java.util.Collection;

/**
 * Renders {@link TileParticle}s: the single entry point for both the CPU vertex path
 * ({@link #renderQueue}) and the 26.1 GPU-instanced path ({@link #fillInstances} /
 * {@link #fillInstancesModel} + {@link #modelMeshBuffer}, drawn via {@code PhotonInstancedDrawState}).
 * Both paths share the same billboard/stretched/model orientation math, so they stay visually
 * identical by construction. The particle itself only holds data and simulation.
 */
@ParametersAreNonnullByDefault
public class TileParticleRenderer {
    /** The renderer runtime this pass draws with (slot-or-config per field): the config's default runtime
     *  for the shared pass, or a per-emitter overriding runtime for an override pass. */
    private final ParticleRendererSetting.Runtime renderer;

    public TileParticleRenderer(ParticleRendererSetting.Runtime renderer) {
        this.renderer = renderer;
    }

    // ---------------------------------------------------------------------
    // CPU path
    // ---------------------------------------------------------------------

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        if (renderer.getRenderMode() == ParticleRendererSetting.Mode.None) {
            return;
        }
        for (var particle : particles) {
            if (particle instanceof TileParticle tileParticle && tileParticle.getDelay() <= 0) {
                renderParticle(buffer, tileParticle, camera, partialTicks);
            }
        }
    }

    private void renderParticle(@Nonnull VertexConsumer buffer, TileParticle particle, Camera camera, float partialTicks) {
        var vec3 = camera.position();

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
        var renderMode = renderer.getRenderMode();

        var size = particle.getRealSize(partialTicks);

        if (renderMode == ParticleRendererSetting.Mode.Model) {
            // mesh positions are already in centered model space (PhotonMesh convention)
            var transform = new Matrix4f().translate(x, y, z)
                    .rotate(computeModelQuaternion(particle, rotation))
                    .scale(size.mul(particle.getSpaceScale()));
            // draw 3d model
            var source = renderer.getModelSource();
            var mesh = source.getMesh();
            var remapUV = source.hasAtlasUV() && !renderer.isUseBlockUV();
            var shade = renderer.isShade();
            for (int quad = 0; quad < mesh.quadCount(); quad++) {
                putMeshQuad(transform, buffer, mesh, quad, shade ? mesh.shadeBrightness(quad) : 1f,
                        r, g, b, a, light, remapUV);
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

    private void putMeshQuad(Matrix4f transform, VertexConsumer buffer, PhotonMesh mesh, int quad,
                             float brightness, float red, float green, float blue, float alpha, int light,
                             boolean remapUV) {
        var vertices = mesh.vertices();
        var pivotPoint = renderer.getModelPivot();
        var normalMat = transform.normal(new Matrix3f());

        float u0 = 0, v0 = 0, uw = 1, vh = 1;
        if (remapUV) {
            var bounds = mesh.spriteBounds();
            u0 = bounds[quad * 4];
            v0 = bounds[quad * 4 + 1];
            uw = bounds[quad * 4 + 2] - u0;
            vh = bounds[quad * 4 + 3] - v0;
        }

        for (int corner = 0; corner < 4; corner++) {
            int off = PhotonMesh.vertexOffset(quad, corner);
            var x = vertices[off] + pivotPoint.x;
            var y = vertices[off + 1] + pivotPoint.y;
            var z = vertices[off + 2] + pivotPoint.z;
            var u = vertices[off + 3];
            var v = vertices[off + 4];
            if (remapUV) {
                u = (u - u0) / uw;
                v = (v - v0) / vh;
            }

            var pos = transform.transform(new Vector4f(x, y, z, 1.0F));
            var normal = new Vector3f(vertices[off + 5], vertices[off + 6], vertices[off + 7]).mul(normalMat).normalize();

            buffer.addVertex(pos.x, pos.y, pos.z);
            buffer.setColor(red * brightness, green * brightness, blue * brightness, alpha);
            buffer.setUv(u, v);
            buffer.setLight(light);
            buffer.setNormal(normal.x, normal.y, normal.z);
        }
    }

    // ---------------------------------------------------------------------
    // instanced path
    // ---------------------------------------------------------------------

    /** Floats per billboard instance on the 26.1 texel-buffer path (pos3 size2 scale3 rot4 color4
     *  uv4 light1) — MIRRORED IN particle.vsh; keep in lockstep. */
    public static final int INSTANCE_FLOATS = 21;

    /** Floats per Model-mode instance (pos3 scale3 rot4 color4 light1) — MIRRORED IN the shader. */
    public static final int MODEL_INSTANCE_FLOATS = 15;

    /** The Model-mode instanced fill: {@value #MODEL_INSTANCE_FLOATS} floats per live particle. */
    public int fillInstancesModel(Collection<IParticle> particles, Camera camera, float partialTicks,
                                  FloatBuffer out, AdditionalGPUDataSetting setting,
                                  @Nullable FloatBuffer dataBuffer,
                                  @Nullable FloatBuffer customBuffer) {
        var count = 0;
        var vec3 = camera.position();
        for (var p : particles) {
            if (!(p instanceof TileParticle particle) || particle.getDelay() > 0) continue;
            count++;
            var localPos = particle.getLocalPos(partialTicks).mulPosition(particle.getSpaceTransform());
            var color = particle.getRealColor(partialTicks);
            var rotation = particle.getRealRotation(partialTicks);
            var size = particle.getRealSize(partialTicks);
            var scale = particle.getSpaceScale();
            var light = particle.getRealLight(partialTicks);
            var quaternion = computeModelQuaternion(particle, rotation);
            out.put((float) (localPos.x - vec3.x)).put((float) (localPos.y - vec3.y)).put((float) (localPos.z - vec3.z));
            out.put(scale.x * size.x).put(scale.y * size.y).put(scale.z * size.z);
            out.put(quaternion.x).put(quaternion.y).put(quaternion.z).put(quaternion.w);
            out.put(color.x).put(color.y).put(color.z).put(color.w);
            out.put(Float.intBitsToFloat(light));
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

    /** Model base mesh baked in the 1.21 instanced layout — 9 floats per vertex (pos3+pivot,
     *  uv2 optionally atlas-remapped, normal3, brightness1); locations 0-3 are applied by
     *  {@code PhotonInstancedDrawState.MODEL}. Sequential-quad indexed (1.21's EBO pattern).
     *  Rebuilt when the mesh hot-reloads (identity compare). */
    @Nullable
    private com.mojang.blaze3d.buffers.GpuBuffer modelVertexBuffer;
    @Nullable
    private com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh modelBuiltMesh;
    private int modelIndexCount;

    @Nullable
    public com.mojang.blaze3d.buffers.GpuBuffer modelMeshBuffer() {
        var source = renderer.getModelSource();
        var mesh = source == null ? null : source.getMesh();
        if (mesh == null) {
            return null;
        }
        if (modelVertexBuffer == null || modelBuiltMesh != mesh) {
            if (modelVertexBuffer != null) {
                modelVertexBuffer.close();
                modelVertexBuffer = null;
            }
            var remapUV = source.hasAtlasUV() && !renderer.isUseBlockUV();
            var shade = renderer.isShade();
            var pivot = renderer.getModelPivot();
            var quadCount = mesh.quadCount();
            var vertices = mesh.vertices();
            var bounds = mesh.spriteBounds();
            // pos 3, uv 2, normal 3, brightness 1 — the 1.21 layout
            var bytes = org.lwjgl.system.MemoryUtil.memAlloc(quadCount * 4 * 9 * Float.BYTES);
            try {
                for (int quad = 0; quad < quadCount; quad++) {
                    var brightness = shade ? mesh.shadeBrightness(quad) : 1f;
                    float u0 = 0, v0 = 0, uw = 1, vh = 1;
                    if (remapUV) {
                        u0 = bounds[quad * 4];
                        v0 = bounds[quad * 4 + 1];
                        uw = bounds[quad * 4 + 2] - u0;
                        vh = bounds[quad * 4 + 3] - v0;
                    }
                    for (int corner = 0; corner < 4; corner++) {
                        int off = com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh.vertexOffset(quad, corner);
                        var u = vertices[off + 3];
                        var v = vertices[off + 4];
                        if (remapUV) {
                            u = (u - u0) / uw;
                            v = (v - v0) / vh;
                        }
                        bytes.putFloat(vertices[off] + pivot.x)
                                .putFloat(vertices[off + 1] + pivot.y)
                                .putFloat(vertices[off + 2] + pivot.z);
                        bytes.putFloat(u).putFloat(v);
                        bytes.putFloat(vertices[off + 5]).putFloat(vertices[off + 6]).putFloat(vertices[off + 7]);
                        bytes.putFloat(brightness);
                    }
                }
                bytes.flip();
                if (!bytes.hasRemaining()) {
                    modelIndexCount = 0;
                    return null;
                }
                modelVertexBuffer = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(
                        () -> "Photon model mesh", com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, bytes);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(bytes);
            }
            modelIndexCount = quadCount * 6;
            modelBuiltMesh = mesh;
        }
        return modelVertexBuffer;
    }

    public int modelIndexCount() {
        return modelIndexCount;
    }

    /**
     * The 26.1 instanced fill: append {@value #INSTANCE_FLOATS} floats per live particle to
     * {@code out} (the billboard/stretched branch). Model mode and GPU-data configs are not
     * eligible — callers gate and fall back to the CPU path. Returns the appended instance count.
     */
    public int fillInstances(Collection<IParticle> particles, Camera camera, float partialTicks,
                             FloatBuffer out, AdditionalGPUDataSetting setting,
                             @Nullable FloatBuffer dataBuffer,
                             @Nullable FloatBuffer customBuffer) {
        var renderMode = renderer.getRenderMode();
        if (renderMode == ParticleRendererSetting.Mode.None
                || renderMode == ParticleRendererSetting.Mode.Model) {
            return 0;
        }
        var count = 0;
        var vec3 = camera.position();
        for (var p : particles) {
            if (!(p instanceof TileParticle particle) || particle.getDelay() > 0) continue;
            count++;
            var localPos = particle.getLocalPos(partialTicks).mulPosition(particle.getSpaceTransform());
            var x = (float) (localPos.x - vec3.x);
            var y = (float) (localPos.y - vec3.y);
            var z = (float) (localPos.z - vec3.z);

            var color = particle.getRealColor(partialTicks);
            var rotation = particle.getRealRotation(partialTicks);
            var size = particle.getRealSize(partialTicks);
            var scale = particle.getSpaceScale();
            var light = particle.getRealLight(partialTicks);
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

            out.put(x).put(y).put(z);                                             // pos vec3
            out.put(finalSizeX).put(finalSizeY);                                  // size vec2
            out.put(scale.x).put(scale.y).put(scale.z);                           // scale vec3
            out.put(quaternion.x).put(quaternion.y).put(quaternion.z).put(quaternion.w); // rot quat
            out.put(color.x).put(color.y).put(color.z).put(color.w);              // color vec4
            out.put(uvs.x).put(uvs.w).put(uvs.z).put(uvs.y);                      // uv vec4 (flip v)
            out.put(Float.intBitsToFloat(light));                                 // light int
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

        float stretch = renderer.getLengthScale() + speed * renderer.getVelocityScale();
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
