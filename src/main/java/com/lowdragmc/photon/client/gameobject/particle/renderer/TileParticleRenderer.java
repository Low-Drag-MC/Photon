package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.DynamicMeshSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingMode;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingOrientationHelper;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

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
    private final ParticleConfig config;
    /** The renderer runtime this pass draws with (slot-or-config per field): the config's default runtime
     *  for the shared pass, or a per-emitter overriding runtime for an override pass. Custom GPU data
     *  still comes from the config. */
    private final ParticleRendererSetting.Runtime renderer;
    private final ParticleInstanceRenderer instanceBackend;

    public TileParticleRenderer(ParticleConfig config, ParticleRendererSetting.Runtime renderer) {
        this.config = config;
        this.renderer = renderer;
        this.instanceBackend = new ParticleInstanceRenderer(config, renderer);
    }

    // ---------------------------------------------------------------------
    // CPU path
    // ---------------------------------------------------------------------

    public void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
        if (renderer.getRenderMode() == ParticleRendererSetting.Mode.None) {
            return;
        }
        if (renderer.getRenderMode() == ParticleRendererSetting.Mode.Model) {
            notifyDynamicMesh();
        }
        for (var particle : particles) {
            if (particle instanceof TileParticle tileParticle && tileParticle.getDelay() <= 0) {
                renderParticle(buffer, tileParticle, camera, partialTicks);
            }
        }
    }

    private void renderParticle(@Nonnull VertexConsumer buffer, TileParticle particle, Camera camera, float partialTicks) {
        var vec3 = camera.getPosition();

        var localPos = particle.getSimPos(partialTicks).mulPosition(particle.getSpaceTransform());
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
                    .rotate(computeModelQuaternion(particle, rotation, camera, partialTicks))
                    .scale(size.mul(particle.getSpaceScale()));
            // draw 3d model
            var source = renderer.getModelSource();
            var mesh = source.getMesh();
            var remapUV = source.hasAtlasUV() && !renderer.isUseBlockUV() && mesh.spriteBounds().length > 0;
            var shade = renderer.isShade();
            var pivot = renderer.getModelPivot();
            // the normal matrix depends on the particle, not the face — it used to be rebuilt per quad
            var normalMat = transform.normal(new Matrix3f());
            var indices = mesh.indices();
            int triangle = 0;
            while (triangle < mesh.triangleCount()) {
                int i = triangle * 3;
                int va = indices[i], vb = indices[i + 1], vc = indices[i + 2];
                // ⚠️ This draws into a QUADS-mode buffer, so every primitive is four vertices. A pair
                // the source authored as one quad is emitted as that quad (its second triangle is
                // (c, d, a), so the d it adds is the one index in the middle); a lone triangle repeats
                // its last corner, the degenerate form every Photon render path already tolerates.
                if (mesh.quadPaired(triangle)) {
                    int vd = indices[i + 4];
                    putMeshVertex(transform, normalMat, buffer, mesh, va, pivot, shade, remapUV, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, mesh, vb, pivot, shade, remapUV, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, mesh, vc, pivot, shade, remapUV, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, mesh, vd, pivot, shade, remapUV, r, g, b, a, light);
                    triangle += 2;
                } else {
                    putMeshVertex(transform, normalMat, buffer, mesh, va, pivot, shade, remapUV, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, mesh, vb, pivot, shade, remapUV, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, mesh, vc, pivot, shade, remapUV, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, mesh, vc, pivot, shade, remapUV, r, g, b, a, light);
                    triangle++;
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

    /** Scratch for {@link #putMeshVertex}; the CPU path is render-thread only, and a large mesh would
     *  otherwise allocate two JOML objects per emitted corner. */
    private final Vector4f meshPos = new Vector4f();
    private final Vector3f meshNormal = new Vector3f();
    /** Scratch for the per-instance model pivot; see {@link #uploadInstances}. */
    private final Vector3f instancePivot = new Vector3f();

    private void putMeshVertex(Matrix4f transform, Matrix3f normalMat, VertexConsumer buffer,
                               PhotonMesh mesh, int vertex, Vector3f pivot, boolean shade,
                               boolean remapUV, float red, float green, float blue, float alpha,
                               int light) {
        var geometry = mesh.geometry();
        var attributes = mesh.attributes();
        int g = PhotonMesh.geometryOffset(vertex);
        int at = PhotonMesh.attributeOffset(vertex);

        float u = attributes[at];
        float v = attributes[at + 1];
        if (remapUV) {
            var bounds = mesh.spriteBounds();
            int s = PhotonMesh.spriteOffset(vertex);
            float u0 = bounds[s], v0 = bounds[s + 1];
            float uw = bounds[s + 2] - u0, vh = bounds[s + 3] - v0;
            if (uw != 0f) u = (u - u0) / uw;
            if (vh != 0f) v = (v - v0) / vh;
        }
        float brightness = shade ? attributes[at + 2] : 1f;

        var pos = transform.transform(meshPos.set(geometry[g] + pivot.x, geometry[g + 1] + pivot.y,
                geometry[g + 2] + pivot.z, 1.0F));
        var normal = meshNormal.set(geometry[g + 3], geometry[g + 4], geometry[g + 5])
                .mul(normalMat).normalize();

        buffer.addVertex(pos.x, pos.y, pos.z);
        buffer.setColor(red * brightness, green * brightness, blue * brightness, alpha);
        buffer.setUv(u, v);
        buffer.setLight(light);
        buffer.setNormal(normal.x, normal.y, normal.z);
    }

    // ---------------------------------------------------------------------
    // instanced path
    // ---------------------------------------------------------------------

    /** Set by the render pass each frame from the emitter's Tangent renderer setting; see {@link #uploadInstances}. */
    public void setWantsTangent(boolean wantsTangent) {
        instanceBackend.setWantsTangent(wantsTangent);
    }

    /**
     * Tell a live-geometry provider that this frame's draw read from it — its cue to keep the allocation
     * its buffer or its pose lives in. Photon has no other way to say it is still interested, and the
     * provider's own drawing is not evidence: an emitter outlives whatever spawned it.
     */
    private void notifyDynamicMesh() {
        if (renderer.getModelSource() instanceof DynamicMeshSource source) {
            source.getDynamic().onDrawn();
        }
    }

    /**
     * Fill and upload the per-instance data for this pass's particles. Returns true if any
     * instance was uploaded (the VAO is left bound for {@link #drawInstanced}).
     */
    public boolean uploadInstances(Collection<IParticle> particles, Camera camera, float partialTicks) {
        var renderMode = renderer.getRenderMode();
        if (instanceBackend.isInitialized()) {
            if (instanceBackend.wasBuiltForModel() != (renderMode == ParticleRendererSetting.Mode.Model)) {
                // a runtime renderMode override crossed the Model/non-Model boundary: the PER-INSTANCE
                // layout differs too, so everything goes
                instanceBackend.dispose();
            } else if (instanceBackend.staticGeometryStale()) {
                // the model was replaced or hot-reloaded, the attribute stream's inputs changed, or a
                // provider moved us to another buffer — base mesh only, the instance VBO and the buffer
                // textures stay
                instanceBackend.rebuildStaticGeometry();
            } else if (instanceBackend.geometryStale()) {
                // same model, new pose: overwrite the geometry stream and nothing else
                instanceBackend.updateGeometry();
            }
        }
        if (renderMode == ParticleRendererSetting.Mode.Model) {
            notifyDynamicMesh();
        }
        var buffer = instanceBackend.beginUpload(particles.size());
        if (buffer == null) return false;
        var setting = config.additionalGPUDataSetting;
        var dataBuffer = setting.hasDataRecord() ? instanceBackend.beginDataUpload(particles.size()) : null;
        var customBuffer = setting.hasCustomRecord() ? instanceBackend.beginCustomUpload(particles.size()) : null;

        var instanceCount = 0;
        var vec3 = camera.getPosition();
        for (var p : particles) {
            if (!(p instanceof TileParticle particle) || particle.getDelay() > 0) continue;
            instanceCount++;
            var localPos = particle.getSimPos(partialTicks).mulPosition(particle.getSpaceTransform());
            var x = (float) (localPos.x - vec3.x);
            var y = (float) (localPos.y - vec3.y);
            var z = (float) (localPos.z - vec3.z);

            var color = particle.getRealColor(partialTicks);
            var rotation = particle.getRealRotation(partialTicks);
            var size = particle.getRealSize(partialTicks);
            var scale = particle.getSpaceScale();
            var light = particle.getRealLight(partialTicks);

            if (renderMode == ParticleRendererSetting.Mode.Model) {
                var quaternion = computeModelQuaternion(particle, rotation, camera, partialTicks);
                float scaleX = scale.x * size.x, scaleY = scale.y * size.y, scaleZ = scale.z * size.z;
                // ⚠️ The pivot is applied here rather than baked into the mesh, which is what keeps the
                // static geometry buffer free of renderer settings — a dynamic mesh can then replace it
                // outright. The shader computes rot * (aPos * iScale) + iPos, so a pivot that was baked
                // as aPos + pivot is exactly rot * (pivot * iScale) added to iPos.
                var pivot = renderer.getModelPivot();
                if (pivot.x != 0f || pivot.y != 0f || pivot.z != 0f) {
                    var offset = quaternion.transform(
                            instancePivot.set(pivot.x * scaleX, pivot.y * scaleY, pivot.z * scaleZ));
                    x += offset.x;
                    y += offset.y;
                    z += offset.z;
                }
                // pos vec3
                buffer.put(x).put(y).put(z);
                // scale vec3
                buffer.put(scaleX).put(scaleY).put(scaleZ);
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

            // legacy per-channel attributes (custom shaders), + packed record for the data TBO (shadergraph)
            if (setting.hasAttribs()) {
                setting.uploadAttribs(particle, buffer, partialTicks);
            }
            if (dataBuffer != null) {
                setting.uploadDataRecord(particle, dataBuffer, partialTicks);
            }
            if (customBuffer != null) {
                setting.uploadCustomRecord(particle, customBuffer, partialTicks);
            }
        }

        if (dataBuffer != null) {
            instanceBackend.endDataUpload(dataBuffer);
        }
        if (customBuffer != null) {
            instanceBackend.endCustomUpload(customBuffer);
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
            quaternion = new Quaternionf(quaternion).mul(eulerRotation(rotation));
        }
        return quaternion;
    }

    /**
     * A particle's own euler rotation (radians) as a quaternion, in <b>Unity's ZXY order</b>: roll,
     * then pitch, then yaw.
     *
     * <p>NOT JOML's {@code rotateXYZ}, which was used here before: with {@code (90, y, 0)} the X term
     * lays a mesh flat and ZXY makes Y a yaw <b>of that flat mesh</b>, where XYZ yaws it while still
     * upright and lands it at a tilt. Roll is innermost either way, so rotation-over-lifetime keeps
     * spinning a flat mesh in its own plane.</p>
     */
    public static Quaternionf eulerRotation(Vector3f rotation) {
        return new Quaternionf().rotateY(rotation.y).rotateX(rotation.x).rotateZ(rotation.z);
    }

    /**
     * A model particle's world orientation: an outer term composed <b>outside</b> its own euler
     * (inside — {@code euler * space} — made a rotated parent skew the mesh instead of carrying it).
     * The outer term is the simulation space's rotation, unless a {@link FacingMode} replaces it;
     * {@code DEFAULT} is "no facing" for a model, where on a billboard it means "face the camera".
     */
    private static Quaternionf computeModelQuaternion(TileParticle particle, Vector3f rotation,
                                                      Camera camera, float partialTicks) {
        // the particle's own runtime, as the Billboard lambda does: a facing override needs no extra pass
        var renderer = particle.getRuntime().renderer;
        var facing = renderer.getFacingMode();
        var outer = facing == FacingMode.DEFAULT
                ? particle.getSpaceRotation()
                : new Quaternionf(FacingOrientationHelper.compute(
                        facing, renderer.getFacingDirection(), particle, camera, partialTicks));
        return outer.mul(eulerRotation(rotation));
    }
}
