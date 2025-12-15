package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Quaternionf;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import javax.annotation.Nullable;
import java.lang.ref.Cleaner;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

public class ParticleInstanceRenderer {
    private static class InstanceResource implements AutoCloseable {
        protected int vao = -1;
        protected int modelVbo = -1;
        protected int modelEbo = -1;
        protected int instanceVbo = -1;

        @Override
        public void close() {
            if (vao != -1) {
                glDeleteVertexArrays(vao);
                vao = -1;
            }

            if (modelVbo != -1) {
                glDeleteBuffers(modelVbo);
                modelVbo = -1;
            }

            if (instanceVbo != -1) {
                glDeleteBuffers(instanceVbo);
                instanceVbo = -1;
            }

            if (modelEbo != -1) {
                glDeleteBuffers(modelEbo);
                modelEbo = -1;
            }
        }
    }

    private final ParticleConfig config;
    @Getter
    private boolean initialized = false;

    @Nullable
    private InstanceResource resource;
    @Nullable
    private Cleaner.Cleanable cleanable;

    private int modelEboSize = 0;
    private int instanceDataSize = 0; // number of floats per instance
    private int maxInstancesSize = 0;
    private int instanceCount = 0;
    @Nullable
    private static FloatBuffer instanceDataBuffer = null;

    public ParticleInstanceRenderer(ParticleConfig config) {
        this.config = config;
    }

    public void init() {
        if (initialized) return;
        ensureCreated(config.maxParticles);
        initialized = true;
    }

    /**
     * Resize only the instance buffer capacity; do not recreate VAO/static buffers.
     */
    public void resize(int size) {
        RenderSystem.assertOnRenderThread();
        ensureCreated(size);
    }

    private void ensureCreated(int instanceCapacity) {
        RenderSystem.assertOnRenderThread();

        if (resource == null) {
            resource = new InstanceResource();
            cleanable = AutoCloseCleaner.registerRenderThread(this, resource);
        }

        if (resource.vao == -1) {
            resource.vao = glGenVertexArrays();
        }

        glBindVertexArray(resource.vao);

        if (resource.modelVbo == -1 || resource.modelEbo == -1) {
            createStaticData();
        }

        // create instance data + grow capacity if needed
        createOrResizeInstanceData(instanceCapacity);

        glBindVertexArray(0);
    }

    private void createStaticData() {
        if (resource == null) return;

        if (config.renderer.getRenderMode() == ParticleRendererSetting.Mode.Model) {
            var model = config.renderer.getModel();
            List<Pair<BakedQuad, Float>> quads = new ArrayList<>();
            for (var side : TileParticle.MODEL_SIDES) {
                var brightness = 1f;
                if (config.renderer.isShade() && side != null) {
                    brightness = switch (side) {
                        case DOWN, UP -> 0.9F;
                        case NORTH, SOUTH -> 0.8F;
                        case WEST, EAST -> 0.6F;
                    };
                }
                for (var quad : model.renderModel(null, null, null, side, LDLib2.RANDOM, ModelData.EMPTY, null)) {
                    quads.add(Pair.of(quad, brightness));
                }
            }

            // pos 3, uv 2, normal 3, brightness 1
            int floatsPerVertex = 3 + 2 + 3 + 1;
            var vertexBuffer = BufferUtils.createFloatBuffer(quads.size() * 4 * floatsPerVertex);
            var indexBuffer = BufferUtils.createIntBuffer(quads.size() * 6);
            var vertexBase = 0;
            var pivotPoint = config.renderer.getModelPivot();

            for (Pair<BakedQuad, Float> pair : quads) {
                var brightness = pair.getRight();
                var quad = pair.getLeft();

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

                        vertexBuffer.put(x).put(y).put(z); // pos
                        vertexBuffer.put(u).put(v); // uv
                        vertexBuffer.put(nX).put(nY).put(nZ); // normal
                        vertexBuffer.put(brightness); // brightness
                    }
                }

                // index
                indexBuffer.put(vertexBase).put(vertexBase + 1).put(vertexBase + 2);
                indexBuffer.put(vertexBase + 2).put(vertexBase + 3).put(vertexBase);
                vertexBase += 4;
            }

            vertexBuffer.flip();
            indexBuffer.flip();

            resource.modelVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_DYNAMIC_DRAW);
            int stride = floatsPerVertex * Float.BYTES;
            int offset = 0;

            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, offset); // position
            glEnableVertexAttribArray(0);
            offset += 3 * Float.BYTES;

            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, offset); // uv
            glEnableVertexAttribArray(1);
            offset += 2 * Float.BYTES;

            glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, offset); // normal
            glEnableVertexAttribArray(2);
            offset += 3 * Float.BYTES;

            glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, offset); // brightness
            glEnableVertexAttribArray(3);
            offset += Float.BYTES;

            resource.modelEbo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource.modelEbo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_DYNAMIC_DRAW);
            modelEboSize = 6 * quads.size();

        } else {
            // particle quad
            float[] quadVertices = {
                    // x, y, z
                    1f, -1f, 0f,
                    1f, 1f, 0f,
                    -1f, 1f, 0f,
                    -1f, -1f, 0f,
            };
            int[] quadIndices = {
                    0, 1, 2, 2, 3, 0
            };

            // bind vertex data
            resource.modelVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
            glBufferData(GL_ARRAY_BUFFER, quadVertices, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
            glEnableVertexAttribArray(0);

            // create ebo
            resource.modelEbo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource.modelEbo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, quadIndices, GL_STATIC_DRAW);
            modelEboSize = 6;
        }
    }

    private void createOrResizeInstanceData(int requestedMaxSize) {
        if (resource == null) return;

        var newVBO = false;
        if (resource.instanceVbo == -1) {
            resource.instanceVbo = glGenBuffers();
            newVBO = true;
        }

        // instanceDataSize is "floats per instance"
        instanceDataSize = config.additionalGPUDataSetting.isEnable()
                ? config.additionalGPUDataSetting.getCustomDataSize()
                : 0;

        int attribIndex;
        int offset;
        int stride;

        if (config.renderer.getRenderMode() == ParticleRendererSetting.Mode.Model) {
            instanceDataSize += 3 + 3 + 4 + 4 + 1; // pos scale rotation color light

            attribIndex = 4;
            offset = 0;
            stride = instanceDataSize * Float.BYTES;

            glBindBuffer(GL_ARRAY_BUFFER, resource.instanceVbo);

            boolean needGrow = requestedMaxSize > maxInstancesSize;
            if (needGrow || newVBO) {
                maxInstancesSize = requestedMaxSize;
                glBufferData(GL_ARRAY_BUFFER, ((long) maxInstancesSize) * instanceDataSize * Float.BYTES, GL_STREAM_DRAW);
            }

            if (newVBO) {
                // Only (re)define attributes if first time (or if you want to be extra safe: always)
                // Here we define them always to keep it simple and robust.
                glVertexAttribPointer(attribIndex, 3, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 3 * Float.BYTES;

                // scale vec3
                glVertexAttribPointer(attribIndex, 3, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 3 * Float.BYTES;

                // rotation vec4
                glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 4 * Float.BYTES;

                // color vec4
                glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 4 * Float.BYTES;

                // light int
                glVertexAttribIPointer(attribIndex, 1, GL_UNSIGNED_INT, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += Float.BYTES;
            }
        } else {
            instanceDataSize += 3 + 2 + 3 + 4 + 4 + 4 + 1; // pos size scale rotation color uv light

            attribIndex = 1;
            offset = 0;
            stride = instanceDataSize * Float.BYTES;

            glBindBuffer(GL_ARRAY_BUFFER, resource.instanceVbo);

            boolean needGrow = requestedMaxSize > maxInstancesSize;
            if (needGrow || newVBO) {
                maxInstancesSize = requestedMaxSize;
                glBufferData(GL_ARRAY_BUFFER, ((long) maxInstancesSize) * instanceDataSize * Float.BYTES, GL_STREAM_DRAW);
            }

            if (newVBO) {
                glVertexAttribPointer(attribIndex, 3, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 3 * Float.BYTES;

                // size vec2
                glVertexAttribPointer(attribIndex, 2, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 2 * Float.BYTES;

                // scale vec3
                glVertexAttribPointer(attribIndex, 3, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 3 * Float.BYTES;

                // rotation vec4
                glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 4 * Float.BYTES;

                // color vec4
                glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                attribIndex++;
                offset += 4 * Float.BYTES;

                // uv vec4
                glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                offset += 4 * Float.BYTES;
                attribIndex++;

                // light int
                glVertexAttribIPointer(attribIndex, 1, GL_UNSIGNED_INT, stride, offset);
                glEnableVertexAttribArray(attribIndex);
                glVertexAttribDivisor(attribIndex, 1);
                offset += Float.BYTES;
                attribIndex++;
            }
        }

        if (newVBO && config.additionalGPUDataSetting.isEnable() && config.additionalGPUDataSetting.hasCustomData()) {
            config.additionalGPUDataSetting.instanceDataLayout(offset, attribIndex, stride);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Full cleanup: delete VAO/VBO/EBO etc.
     * Call this when render mode/layout changes, not for instance capacity growth.
     */
    public void dispose() {
        if (cleanable != null) {
            cleanable.clean();
            cleanable = null;
        }

        resource = null;

        modelEboSize = 0;
        maxInstancesSize = 0;
        instanceCount = 0;

        initialized = false;
    }

    private static FloatBuffer getInstanceDataBuffer(int requiredCapacity) {
        if (instanceDataBuffer == null || instanceDataBuffer.capacity() < requiredCapacity) {
            int newCapacity = instanceDataBuffer == null ?
                    Math.max(requiredCapacity, 10000) :
                    Math.max(requiredCapacity, instanceDataBuffer.capacity() * 2);

            instanceDataBuffer = BufferUtils.createFloatBuffer(newCapacity);
        }
        return instanceDataBuffer;
    }

    public boolean upload(Collection<TileParticle> particles, Camera camera, float partialTicks) {
        init();
        if (resource == null) return false;

        var particleCount = particles.size();
        if (particleCount > maxInstancesSize) {
            resize(particleCount);
        }

        var required = particleCount * instanceDataSize;
        var buffer = getInstanceDataBuffer(required);
        buffer.clear();

        glBindVertexArray(resource.vao);
        glBindBuffer(GL_ARRAY_BUFFER, resource.instanceVbo);

        instanceCount = 0;
        var vec3 = camera.getPosition();
        for (var p : particles) {
            if (p.getDelay() > 0) continue;
            instanceCount++;
            var localPos = p.getLocalPos(partialTicks).mulPosition(p.getSpaceTransform());
            var x = (float) (localPos.x - vec3.x);
            var y = (float) (localPos.y - vec3.y);
            var z = (float) (localPos.z - vec3.z);

            var color = p.getRealColor(partialTicks);
            var rotation = p.getRealRotation(partialTicks);
            var renderMode = p.getConfig().renderer.getRenderMode();

            var size = p.getRealSize(partialTicks);
            var scale = p.getSpaceScale();
            var light = p.getRealLight(partialTicks);

            if (renderMode == ParticleRendererSetting.Mode.Model) {
                var quaternion = new Quaternionf().rotateXYZ(rotation.x, rotation.y, rotation.z).mul(p.getSpaceRotation());
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
                var uvs = p.getRealUVs(partialTicks);
                var quaternion = renderMode.quaternion.apply(p, camera, partialTicks);
                if (!Vector3fHelper.isZero(rotation)) {
                    quaternion = new Quaternionf(quaternion).rotateXYZ(rotation.x, rotation.y, rotation.z);
                }

                // pos vec3
                buffer.put(x).put(y).put(z);
                // size vec2
                buffer.put(size.x).put(size.y);
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

            if (config.additionalGPUDataSetting.isEnable() && config.additionalGPUDataSetting.hasCustomData()) {
                // append additional data
                config.additionalGPUDataSetting.uploadData(p, buffer, partialTicks);
            }
        }

        buffer.flip();

        // upload

        // debug
//        int bytesToUpload = buffer.remaining() * Float.BYTES;
//        int bytesAllocated = maxInstancesSize * instanceDataSize * Float.BYTES;
//        if (bytesToUpload > bytesAllocated) {
//            LDLib2.LOGGER.warn("Not enough GPU memory for particles! ({} > {})", bytesToUpload, bytesAllocated);
//        }

        glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
        return instanceCount > 0;
    }

    public void drawWithShader(ShaderInstance shader) {
        // bind shader
        shader.setDefaultUniforms(
                VertexFormat.Mode.QUADS,
                RenderSystem.getModelViewMatrix(),
                RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow()
        );
        shader.apply();

        // draw instance
        glDrawElementsInstanced(GL_TRIANGLES, modelEboSize, GL_UNSIGNED_INT, 0, instanceCount);
    }
}
