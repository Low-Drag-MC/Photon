package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.VertexAnimation;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.VertexAnimationBake;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingMode;
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingOrientationHelper;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.FloatBuffer;
import java.util.Collection;

/**
 * Renders {@link TileParticle}s: the single entry point for both the CPU vertex path
 * ({@link #renderQueue}) and the GPU-instanced path ({@link #fillInstances} /
 * {@link #fillInstancesModel} + {@link #modelMeshBuffer}, laid out by {@code PhotonInstanceLayouts}).
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
        var animation = vertexAnimationFor(mesh);
        return new ModelPass(mesh,
                source.hasAtlasUV() && !renderer.isUseBlockUV() && mesh.spriteBounds().length > 0,
                renderer.isShade(), renderer.getModelPivot(),
                animation, animation == null ? null : animation.table());
    }

    private void renderParticle(@Nonnull VertexConsumer buffer, TileParticle particle, Camera camera,
                                float partialTicks, @Nullable ModelPass model) {
        var vec3 = camera.position();

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
    /** The second half of {@link #poseBaseFor}'s answer, read by the {@link #putMeshVertex} calls that
     *  follow it. Fields rather than a returned record: this is per particle, per frame. */
    private float poseBlend;
    private int poseNextBase;
    private final float[] unpackedNormal = new float[3];

    /**
     * Where this particle's pose starts in the baked table, in texels, or {@code -1} when there is none.
     * MIRRORED FROM the PHOTON_VAT block of particle.glsl — the same two channels, the same wrap.
     */
    private int poseBaseFor(@Nullable VertexAnimation animation, TileParticle particle, float partialTicks) {
        poseBlend = 0f; // reset before the early return, so a stale blend cannot outlive its table
        if (animation == null) return -1;
        var phase = animation.phase();
        float p = phase[0] + particle.getMemRandom("instance_random") * phase[1]
                + particle.getT(partialTicks) * phase[2];
        p -= (float) Math.floor(p);
        float cursor = p * animation.frames();
        int frame = Math.max(0, Math.min((int) cursor, animation.frames() - 1));
        // the blend and the following frame, for putMeshVertex; see the PHOTON_VAT block of particle.glsl
        poseBlend = animation.interpolates() ? cursor - (float) Math.floor(cursor) : 0f;
        poseNextBase = (frame + 1 == animation.frames() ? 0 : frame + 1) * animation.vertexCount();
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
            float px = table[texel], py = table[texel + 1], pz = table[texel + 2];
            float nx = unpackedNormal[0], ny = unpackedNormal[1], nz = unpackedNormal[2];
            if (poseBlend > 0f) {
                int next = (poseNextBase + vertex) * VertexAnimationBake.FLOATS_PER_TEXEL;
                VertexAnimationBake.unpackNormal(table[next + 3], unpackedNormal);
                px += (table[next] - px) * poseBlend;
                py += (table[next + 1] - py) * poseBlend;
                pz += (table[next + 2] - pz) * poseBlend;
                nx += (unpackedNormal[0] - nx) * poseBlend;
                ny += (unpackedNormal[1] - ny) * poseBlend;
                nz += (unpackedNormal[2] - nz) * poseBlend;
            }
            meshPos.set(px + pivot.x, py + pivot.y, pz + pivot.z, 1.0F);
            meshNormal.set(nx, ny, nz);
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
        notifyDynamicMesh(); // this frame's draw read from the provider — its cue to keep the allocation
        for (var p : particles) {
            if (!(p instanceof TileParticle particle) || particle.getDelay() > 0) continue;
            count++;
            var localPos = particle.getSimPos(partialTicks).mulPosition(particle.getSpaceTransform());
            var color = particle.getRealColor(partialTicks);
            var rotation = particle.getRealRotation(partialTicks);
            var size = particle.getRealSize(partialTicks);
            var scale = particle.getSpaceScale();
            var light = particle.getRealLight(partialTicks);
            var quaternion = computeModelQuaternion(particle, rotation, camera, partialTicks);
            float scaleX = scale.x * size.x, scaleY = scale.y * size.y, scaleZ = scale.z * size.z;
            float x = (float) (localPos.x - vec3.x);
            float y = (float) (localPos.y - vec3.y);
            float z = (float) (localPos.z - vec3.z);
            // ⚠️ applied here rather than baked into the mesh buffer, so the geometry holds nothing but
            // the mesh. The shader does rot * (aPos * iScale) + iPos, so a baked aPos + pivot is exactly
            // rot * (pivot * iScale) added to iPos — and this way a pose table, whose positions never pass
            // through the mesh buffer, gets the pivot too.
            var pivot = renderer.getModelPivot();
            if (pivot.x != 0f || pivot.y != 0f || pivot.z != 0f) {
                var offset = quaternion.transform(
                        instancePivot.set(pivot.x * scaleX, pivot.y * scaleY, pivot.z * scaleZ));
                x += offset.x;
                y += offset.y;
                z += offset.z;
            }
            out.put(x).put(y).put(z);
            out.put(scaleX).put(scaleY).put(scaleZ);
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

    /** Model base mesh in the instanced layout — 9 floats per vertex (pos3, uv2 optionally
     *  atlas-remapped, normal3, brightness1), or 13 with the emitter's Tangent setting on (normal widens
     *  to vec4 with brightness in w, then tangent4); see {@code PhotonInstanceLayouts.MODEL} /
     *  {@code MODEL_TANGENT}.
     *  <p>
     *  One vertex per MESH vertex and drawn through {@link #modelIndexBuffer}, not four-per-quad through
     *  the shared sequential-quad indices: {@link PhotonMesh} welds now (glTF keeps the file's own indices,
     *  OBJ welds by v/vt/vn), so vertices are no longer grouped four to a face and the quad pattern would
     *  read the wrong corners entirely.
     *  <p>
     *  ⚠️ The model pivot is NOT baked in — it is a per-instance offset (see {@link #fillInstancesModel}),
     *  which is what lets an animated pivot work without a rebuild and what keeps a baked pose table, whose
     *  positions never pass through this buffer, honouring it too. */
    @Nullable
    private GpuBuffer modelVertexBuffer;
    @Nullable
    private GpuBuffer modelIndexBuffer;
    @Nullable
    private PhotonMesh modelBuiltMesh;
    /** Whether {@link #modelVertexBuffer} was baked with the tangent layout — a change forces a rebuild. */
    private boolean modelBuiltTangent;
    private int modelIndexCount;

    /**
     * @param withTangent upload the mesh tangent as vertex data. Decided per bake by the emitter's
     *                    Tangent renderer setting, and it must agree with the {@code InstancedVariant}
     *                    the group draws with — the two describe the same attribute layout.
     */
    @Nullable
    public GpuBuffer modelMeshBuffer(boolean withTangent) {
        var source = renderer.getModelSource();
        var mesh = source == null ? null : source.getMesh();
        if (mesh == null) {
            return null;
        }
        if (modelVertexBuffer == null || modelBuiltMesh != mesh || modelBuiltTangent != withTangent) {
            closeModelBuffers();
            var remapUV = source.hasAtlasUV() && !renderer.isUseBlockUV() && mesh.spriteBounds().length > 0;
            var shade = renderer.isShade();
            var geometry = mesh.geometry();
            var attributes = mesh.attributes();
            var bounds = mesh.spriteBounds();
            // only touched when the emitter asked for tangents — the mesh generates them on first access
            var tangents = withTangent ? mesh.tangents() : null;
            // pos 3, uv 2, normal 3, brightness 1 — the 1.21 layout; with tangents the brightness rides in
            // the normal's w and a tangent4 follows (MIRRORED FROM particle.glsl; keep in lockstep)
            var floatsPerVertex = withTangent ? 3 + 2 + 4 + 4 : 3 + 2 + 3 + 1;
            var vertexCount = mesh.vertexCount();
            var indices = mesh.indices();
            if (vertexCount == 0 || indices.length == 0) {
                modelIndexCount = 0;
                return null;
            }
            var bytes = MemoryUtil.memAlloc(vertexCount * floatsPerVertex * Float.BYTES);
            try {
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    int g = PhotonMesh.geometryOffset(vertex);
                    int at = PhotonMesh.attributeOffset(vertex);
                    var u = attributes[at];
                    var v = attributes[at + 1];
                    if (remapUV) {
                        int s = PhotonMesh.spriteOffset(vertex);
                        float u0 = bounds[s], v0 = bounds[s + 1];
                        float uw = bounds[s + 2] - u0, vh = bounds[s + 3] - v0;
                        if (uw != 0f) u = (u - u0) / uw;
                        if (vh != 0f) v = (v - v0) / vh;
                    }
                    bytes.putFloat(geometry[g]).putFloat(geometry[g + 1]).putFloat(geometry[g + 2]);
                    bytes.putFloat(u).putFloat(v);
                    bytes.putFloat(geometry[g + 3]).putFloat(geometry[g + 4]).putFloat(geometry[g + 5]);
                    bytes.putFloat(shade ? attributes[at + 2] : 1f); // aNormal.w when tangents are on
                    if (tangents != null) {
                        // tangent.xyz + handedness in w. The atlas->sprite UV remap above is a positive
                        // per-axis scale, so it can't rotate the tangent — no remap needed here.
                        int tan = PhotonMesh.tangentOffset(vertex);
                        bytes.putFloat(tangents[tan]).putFloat(tangents[tan + 1])
                                .putFloat(tangents[tan + 2]).putFloat(tangents[tan + 3]);
                    }
                }
                bytes.flip();
                modelVertexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "Photon model mesh", GpuBuffer.USAGE_VERTEX, bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
            var indexBytes = MemoryUtil.memAlloc(indices.length * Integer.BYTES);
            try {
                for (var index : indices) {
                    indexBytes.putInt(index);
                }
                indexBytes.flip();
                modelIndexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "Photon model indices", GpuBuffer.USAGE_INDEX, indexBytes);
            } finally {
                MemoryUtil.memFree(indexBytes);
            }
            modelIndexCount = indices.length;
            modelBuiltMesh = mesh;
            modelBuiltTangent = withTangent;
        }
        return modelVertexBuffer;
    }

    /** The mesh's own triangle indices; always present alongside {@link #modelMeshBuffer}'s result. */
    @Nullable
    public GpuBuffer modelIndexBuffer() {
        return modelIndexBuffer;
    }

    public int modelIndexCount() {
        return modelIndexCount;
    }

    private void closeModelBuffers() {
        if (modelVertexBuffer != null) {
            modelVertexBuffer.close();
            modelVertexBuffer = null;
        }
        if (modelIndexBuffer != null) {
            modelIndexBuffer.close();
            modelIndexBuffer = null;
        }
    }

    /** Whether this pass draws from a baked pose table, which picks the shader variant. */
    public boolean usesVertexAnimation() {
        return vertexAnimation() != null;
    }

    /** The pose table this pass would draw from, or null for an ordinary (or mismatched) model. */
    @Nullable
    public VertexAnimation vertexAnimation() {
        var source = renderer.getModelSource();
        return source == null ? null : vertexAnimationFor(source.getMesh());
    }

    /** The source's pose table, but only when it matches the mesh it would be indexed against — a table
     *  baked for a different vertex count would be read past its end, and the rest pose is the safe
     *  reading of "these two disagree". */
    @Nullable
    private VertexAnimation vertexAnimationFor(@Nullable PhotonMesh mesh) {
        if (mesh == null || renderer.getRenderMode() != ParticleRendererSetting.Mode.Model) {
            return null;
        }
        var animation = renderer.getModelSource().vertexAnimation();
        return animation != null && animation.vertexCount() == mesh.vertexCount() ? animation : null;
    }

    /** Tell a provider this frame's draw read from it — its cue to keep the allocation alive. */
    private void notifyDynamicMesh() {
        var dynamic = renderer.getModelSource().asDynamic();
        if (dynamic != null) {
            dynamic.onDrawn();
        }
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
            var localPos = particle.getSimPos(partialTicks).mulPosition(particle.getSpaceTransform());
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
