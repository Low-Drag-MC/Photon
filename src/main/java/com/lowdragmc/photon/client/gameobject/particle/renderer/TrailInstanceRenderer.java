package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.trail.TrailConfig;

import static org.lwjgl.opengl.GL30.*;

/**
 * GL-resource backend of {@link TrailParticleRenderer}: one instance per trail SEGMENT expanded
 * from a unit quad in the vertex shader (TRAIL_INSTANCE branch of photon:particle.glsl — keep the
 * layout in lockstep). aPos.x in {0,1} selects the curr/next point, aPos.y in {-1,+1} the ribbon
 * side. Point data lives in the {@value #POINT_SAMPLER} buffer texture (3 texels per point:
 * pos+width / premultiplied color / u), uploaded once per point; instances carry only the point
 * index, the packed light and the cross-ribbon v pair. Each trail's point block is padded with
 * bitwise copies of its first/last point so the shader's neighbor fetches (c-1 / c+2) stay inside
 * the trail and its endpoint-fallback equality tests hold. The index buffer reproduces the CPU
 * triangle strip's diagonal so twisted ribbons triangulate identically.
 */
class TrailInstanceRenderer extends InstancedRenderBackend {

    /** iSeg(ivec2: point index + packed light), iSegV(vec2: v0, v1) */
    static final int BASE_FLOATS = 2 + 2;
    /** texels per point: T0 = (pos.xyz, width), T1 = (color rgba, premultiplied), T2 = (u, 0, 0, 0) */
    static final int POINT_TEXELS = 3;

    private final TrailConfig config;

    public TrailInstanceRenderer(TrailConfig config) {
        this.config = config;
    }

    @Override
    protected int initialInstanceCapacity() {
        return 256;
    }

    @Override
    protected int pointTexelsPerPoint() {
        return POINT_TEXELS;
    }

    @Override
    protected void createStaticGeometry(InstanceResource resource) {
        float[] quadVertices = {
                // x (curr/next), y (up/down)
                0f, 1f,   // 0: curr up
                0f, -1f,  // 1: curr down
                1f, 1f,   // 2: next up
                1f, -1f,  // 3: next down
        };
        // same triangulation as the CPU TRIANGLE_STRIP: (up0, down0, up1), (up1, down0, down1)
        int[] quadIndices = {
                0, 1, 2, 1, 3, 2
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
    protected int customTexelsPerInstance() {
        return config.additionalGPUDataSetting.hasCustomRecord()
                ? config.additionalGPUDataSetting.customDataTexels() : 0;
    }

    @Override
    protected void defineInstanceAttributes(int stride) {
        int offset = 0;
        offset = intInstanceAttrib(1, 2, stride, offset);   // iSeg ivec2 (point index, light)
        offset = floatInstanceAttrib(2, 2, stride, offset); // iSegV vec2 (v0, v1)

        config.additionalGPUDataSetting.layoutAttribs(offset, stride);
    }
}
