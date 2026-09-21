package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.VertexAnimation;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.VertexAnimationBake;
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
import javax.annotation.Nullable;
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
        ModelPass model = null;
        if (renderer.getRenderMode() == ParticleRendererSetting.Mode.Model) {
            notifyDynamicMesh();
            model = modelPass();
        }
        for (var particle : particles) {
            if (particle instanceof TileParticle tileParticle && tileParticle.getDelay() <= 0) {
                renderParticle(buffer, tileParticle, camera, partialTicks, model);
            }
        }
    }

    /**
     * What the model branch needs that does not vary per particle. Resolving it per particle costs a mesh
     * cache lookup each time — and for an animated source a pose lookup and a clip-by-name scan.
     *
     * @param table the baked poses, or null to draw {@code mesh}'s own geometry
     */
    private record ModelPass(PhotonMesh mesh, boolean remapUV, boolean shade, Vector3f pivot,
                             @Nullable VertexAnimation animation, @Nullable float[] table) {
    }

    private ModelPass modelPass() {
        var source = renderer.getModelSource();
        var mesh = source.getMesh();
        var animation = source.vertexAnimation();
        // a table baked for a different vertex count would be indexed past its end; the rest pose is the
        // safe reading of "these two disagree"
        boolean usable = animation != null && animation.vertexCount() == mesh.vertexCount();
        return new ModelPass(mesh,
                source.hasAtlasUV() && !renderer.isUseBlockUV() && mesh.spriteBounds().length > 0,
                renderer.isShade(), renderer.getModelPivot(),
                usable ? animation : null, usable ? animation.table() : null);
    }

    private void renderParticle(@Nonnull VertexConsumer buffer, TileParticle particle, Camera camera,
                                float partialTicks, @Nullable ModelPass model) {
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

        if (model != null) { // non-null exactly in Model mode; see renderQueue
            // mesh positions are already in centered model space (PhotonMesh convention)
            var transform = new Matrix4f().translate(x, y, z)
                    .rotate(computeModelQuaternion(particle, rotation, camera, partialTicks))
                    .scale(size.mul(particle.getSpaceScale()));
            var mesh = model.mesh();
            // ⚠️ A baked pose table is not only the instanced path's business: GPU instancing is off by
            // default, and without this a per-particle-phase model drew its rest pose and never moved.
            int poseBase = poseBaseFor(model.animation(), particle, partialTicks);
            var table = poseBase < 0 ? null : model.table();
            // the normal matrix depends on the particle, not the face — it used to be rebuilt per quad
            var normalMat = transform.normal(new Matrix3f());
            var indices = mesh.indices();
            int triangle = 0;
            while (triangle < mesh.triangleCount()) {
                int i = triangle * 3;
                int va = indices[i], vb = indices[i + 1], vc = indices[i + 2];
                // ⚠️ a QUADS-mode buffer, so four vertices a primitive: an authored quad goes out
                // whole (its second triangle is (c, d, a)), a lone triangle repeats its last corner
                if (mesh.quadPaired(triangle)) {
                    int vd = indices[i + 4];
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, va, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, vb, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, vc, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, vd, r, g, b, a, light);
                    triangle += 2;
                } else {
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, va, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, vb, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, vc, r, g, b, a, light);
                    putMeshVertex(transform, normalMat, buffer, model, table, poseBase, vc, r, g, b, a, light);
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

    /** Scratch; render-thread only, and a large mesh would otherwise allocate two per corner. */
    private final Vector4f meshPos = new Vector4f();
    private final Vector3f meshNormal = new Vector3f();
    /** Scratch for the per-instance model pivot; see {@link #uploadInstances}. */
    private final Vector3f instancePivot = new Vector3f();
    private final float[] unpackedNormal = new float[3];

    /**
     * Where this particle's pose starts in the baked table, in texels, or {@code -1} when there is none.
     * MIRRORED FROM the PHOTON_VAT block of particle.glsl — the same two channels, the same wrap.
     */
    private int poseBaseFor(@Nullable VertexAnimation animation, TileParticle particle, float partialTicks) {
        if (animation == null) return -1;
        var phase = animation.phase();
        float p = phase[0] + particle.getMemRandom("instance_random") * phase[1]
                + particle.getT(partialTicks) * phase[2];
        p -= (float) Math.floor(p);
        int frame = Math.max(0, Math.min((int) (p * animation.frames()), animation.frames() - 1));
        return frame * animation.vertexCount();
    }

    private void putMeshVertex(Matrix4f transform, Matrix3f normalMat, VertexConsumer buffer,
                               ModelPass model, @Nullable float[] table, int poseBase, int vertex,
                               float red, float green, float blue, float alpha, int light) {
        var mesh = model.mesh();
        var pivot = model.pivot();
        var geometry = mesh.geometry();
        var attributes = mesh.attributes();
        int g = PhotonMesh.geometryOffset(vertex);
        int at = PhotonMesh.attributeOffset(vertex);

        float u = attributes[at];
        float v = attributes[at + 1];
        if (model.remapUV()) {
            var bounds = mesh.spriteBounds();
            int s = PhotonMesh.spriteOffset(vertex);
            float u0 = bounds[s], v0 = bounds[s + 1];
            float uw = bounds[s + 2] - u0, vh = bounds[s + 3] - v0;
            if (uw != 0f) u = (u - u0) / uw;
            if (vh != 0f) v = (v - v0) / vh;
        }
        float brightness = model.shade() ? attributes[at + 2] : 1f;

        if (table != null) {
            int texel = (poseBase + vertex) * VertexAnimationBake.FLOATS_PER_TEXEL;
            VertexAnimationBake.unpackNormal(table[texel + 3], unpackedNormal);
            meshPos.set(table[texel] + pivot.x, table[texel + 1] + pivot.y, table[texel + 2] + pivot.z, 1.0F);
            meshNormal.set(unpackedNormal[0], unpackedNormal[1], unpackedNormal[2]);
        } else {
            meshPos.set(geometry[g] + pivot.x, geometry[g + 1] + pivot.y, geometry[g + 2] + pivot.z, 1.0F);
            meshNormal.set(geometry[g + 3], geometry[g + 4], geometry[g + 5]);
        }
        var pos = transform.transform(meshPos);
        var normal = meshNormal.mul(normalMat).normalize();

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

    /** Whether this pass draws from a baked pose table, which picks the shader variant. */
    public boolean usesVertexAnimation() {
        return instanceBackend.vertexAnimation() != null;
    }

    /** Tell a provider this frame's draw read from it — its cue to keep the allocation alive. */
    private void notifyDynamicMesh() {
        var dynamic = renderer.getModelSource().asDynamic();
        if (dynamic != null) {
            dynamic.onDrawn();
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
                instanceBackend.rebuildStaticGeometry();
            } else if (instanceBackend.geometryStale()) {
                instanceBackend.updateGeometry(); // same model, new pose
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
                // ⚠️ applied here rather than baked, so the geometry buffer holds nothing but the
                // mesh. The shader does rot * (aPos * iScale) + iPos, so a baked aPos + pivot is
                // exactly rot * (pivot * iScale) added to iPos.
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
