package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamConfig;

import static org.lwjgl.opengl.GL30.*;

/**
 * GL-resource backend of {@link BeamParticleRenderer}: one instance per beam expanded from a
 * unit quad in the vertex shader (BEAM_INSTANCE branch of photon:particle.glsl — keep the
 * layout in lockstep). aPos.x in {0,1} selects start/end, aPos.y in {-1,+1} the width side.
 */
class BeamInstanceRenderer extends InstancedRenderBackend {

    /** iStart(vec4: xyz + width), iEnd(vec3), iColor(vec4), iUV(vec4), iLight(int) */
    static final int BASE_FLOATS = 4 + 3 + 4 + 4 + 1;

    private final BeamConfig config;

    public BeamInstanceRenderer(BeamConfig config) {
        this.config = config;
    }

    @Override
    protected int initialInstanceCapacity() {
        return 16;
    }

    @Override
    protected void createStaticGeometry(InstanceResource resource) {
        // corner order matches the CPU emission p1(from-n), p0(from+n), p4(end+n), p3(end-n)
        float[] quadVertices = {
                // x (start/end), y (side)
                0f, -1f,
                0f, 1f,
                1f, 1f,
                1f, -1f,
        };
        int[] quadIndices = {
                0, 1, 2, 2, 3, 0
        };

        resource.modelVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
        glBufferData(GL_ARRAY_BUFFER, quadVertices, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        resource.modelEbo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource.modelEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, quadIndices, GL_STATIC_DRAW);
        modelEboSize = 6;
    }

    @Override
    protected int instanceFloats() {
        return BASE_FLOATS + config.additionalGPUDataSetting.attribFloats();
    }

    @Override
    protected int dataTexelsPerInstance() {
        return config.additionalGPUDataSetting.dataTexels();
    }

    @Override
    protected void defineInstanceAttributes(int stride) {
        int attribIndex = 1;
        int offset = 0;
        offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // iStart vec4 (xyz + width)
        offset = floatInstanceAttrib(attribIndex++, 3, stride, offset); // iEnd vec3
        offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // iColor vec4
        offset = floatInstanceAttrib(attribIndex++, 4, stride, offset); // iUV vec4
        offset = intInstanceAttrib(attribIndex++, stride, offset);      // iLight int

        config.additionalGPUDataSetting.layoutAttribs(offset, stride);
    }
}
