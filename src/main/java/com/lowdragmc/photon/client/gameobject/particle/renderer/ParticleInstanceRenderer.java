package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL30.*;

/**
 * GL-resource backend of {@link TileParticleRenderer}: billboard-quad or baked-model base
 * geometry plus the tile per-instance layout (pos/size/scale/rot/color/uv/light + custom data).
 * Buffer management and the draw call live in {@link InstancedRenderBackend}.
 */
class ParticleInstanceRenderer extends InstancedRenderBackend {

    private final ParticleConfig config;

    public ParticleInstanceRenderer(ParticleConfig config) {
        this.config = config;
    }

    @Override
    protected int initialInstanceCapacity() {
        return config.getMaxParticles();
    }

    @Override
    protected void createStaticGeometry(InstanceResource resource) {
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

    @Override
    protected int instanceFloats() {
        var custom = config.additionalGPUDataSetting.getCustomDataSize();
        return custom + (config.renderer.getRenderMode() == ParticleRendererSetting.Mode.Model
                ? 3 + 3 + 4 + 4 + 1        // pos scale rotation color light
                : 3 + 2 + 3 + 4 + 4 + 4 + 1); // pos size scale rotation color uv light
    }

    @Override
    protected void defineInstanceAttributes(int stride) {
        int attribIndex;
        int offset = 0;

        if (config.renderer.getRenderMode() == ParticleRendererSetting.Mode.Model) {
            attribIndex = 4;
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // pos vec3
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // scale vec3
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // rotation vec4
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // color vec4
            offset = intInstanceAttrib(attribIndex++, stride, offset);      // light int
        } else {
            attribIndex = 1;
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // pos vec3
            offset = floatInstanceAttrib(attribIndex++, 2, stride, offset); // size vec2
            offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // scale vec3
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // rotation vec4
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // color vec4
            offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // uv vec4
            offset = intInstanceAttrib(attribIndex++, stride, offset);      // light int
        }

        config.additionalGPUDataSetting.instanceDataLayout(offset, stride);
    }

    @Override
    protected void zeroInactiveCustomSlots() {
        config.additionalGPUDataSetting.zeroInactiveSlots();
    }
}
