package com.lowdragmc.photon.client.gameobject.particle.renderer;

import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailConfig;
import org.joml.Vector2f;

import static org.lwjgl.opengl.GL30.*;

/**
 * GL-resource backend of the AraTrail instanced path: one instance per SEGMENT, expanded in the
 * vertex shader from either a unit quad (flat ribbon, ARA_TRAIL_INSTANCE) or a double ring baked
 * from the section polygon (tube, ARA_TRAIL_TUBE_INSTANCE) — keep photon:particle.glsl in
 * lockstep. Point data (4 texels per point) lives in the point buffer texture; instances carry
 * only the point index (+ the cross-ribbon v pair in flat mode). The per-point frames are
 * CPU-computed, so no neighbor fetches and no padding are needed. The mode, the section polygon
 * and (tube) uvWidthFactor are baked into the static geometry — {@link #geometryStale()} detects
 * edits by snapshot compare (the polygon editor mutates Vector2fs in place, so events are
 * unreliable; the compare also covers NBT loads).
 */
class AraTrailInstanceRenderer extends InstancedRenderBackend {

    /** flat: iSeg(int point index) + iSegV(vec2 vA, vB) */
    static final int BASE_FLOATS_FLAT = 1 + 2;
    /** tube: iSeg(int point index) — vCoord lives in the point texels, uAround in the base mesh */
    static final int BASE_FLOATS_TUBE = 1;
    /**
     * texels per point — flat: (pos+u / offset / normal / color);
     * tube: (pos+thickness / bitangent+vCoord / tangent / color)
     */
    static final int POINT_TEXELS = 4;

    private final AraTrailConfig config;

    // geometry snapshot, recorded when the static geometry is built
    private boolean tubeMode;
    private Vector2f[] sectionSnapshot = new Vector2f[0];
    private float uvWidthFactorSnapshot;

    public AraTrailInstanceRenderer(AraTrailConfig config) {
        this.config = config;
    }

    boolean isTubeMode() {
        return tubeMode;
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
        tubeMode = config.section.isEnable();

        float[] baseVertices;
        int[] baseIndices;
        int componentsPerVertex;

        if (tubeMode) {
            var sectionVertices = config.section.vertices;
            sectionSnapshot = new Vector2f[sectionVertices.size()];
            for (int i = 0; i < sectionSnapshot.length; i++) {
                sectionSnapshot[i] = new Vector2f(sectionVertices.get(i));
            }
            uvWidthFactorSnapshot = config.uvWidthFactor;

            int segments = config.section.getSegments();
            componentsPerVertex = 4;
            if (segments < 1) {
                // degenerate polygon — empty geometry (the CPU path renders nothing there either)
                baseVertices = new float[0];
                baseIndices = new int[0];
            } else {
                int ringVertices = segments + 1;
                // two rings: x=0 (curr point) first, then x=1 (next point);
                // vertex = (x, sectionVert.x, sectionVert.y, uAround)
                baseVertices = new float[2 * ringVertices * 4];
                int v = 0;
                for (int x = 0; x <= 1; x++) {
                    for (int j = 0; j <= segments; j++) {
                        baseVertices[v++] = x;
                        baseVertices[v++] = sectionVertices.get(j).x;
                        baseVertices[v++] = sectionVertices.get(j).y;
                        // mirrors the CPU uv: (j / (float) segments) * uvWidthFactor
                        baseVertices[v++] = (j / (float) segments) * config.uvWidthFactor;
                    }
                }
                // CPU ring indexing: (N_j, N_j+1, C_j), (N_j+1, C_j+1, C_j)
                baseIndices = new int[segments * 6];
                int t = 0;
                for (int j = 0; j < segments; j++) {
                    baseIndices[t++] = ringVertices + j;
                    baseIndices[t++] = ringVertices + j + 1;
                    baseIndices[t++] = j;
                    baseIndices[t++] = ringVertices + j + 1;
                    baseIndices[t++] = j + 1;
                    baseIndices[t++] = j;
                }
            }
        } else {
            sectionSnapshot = new Vector2f[0];
            componentsPerVertex = 2;
            baseVertices = new float[]{
                    // x (curr/next), y (+/- bitangent side)
                    0f, 1f,   // 0: curr +
                    0f, -1f,  // 1: curr -
                    1f, 1f,   // 2: next +
                    1f, -1f,  // 3: next -
            };
            // CPU flat triangulation: (B_next, B_curr, A_curr), (A_curr, A_next, B_next)
            baseIndices = new int[]{
                    3, 1, 0, 0, 2, 3
            };
        }

        resource.modelVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, resource.modelVbo);
        glBufferData(GL_ARRAY_BUFFER, baseVertices, GL_STATIC_DRAW);
        glVertexAttribPointer(0, componentsPerVertex, GL_FLOAT, false, componentsPerVertex * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        resource.modelEbo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, resource.modelEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, baseIndices, GL_STATIC_DRAW);
        modelEboSize = baseIndices.length;
    }

    @Override
    protected int instanceFloats() {
        return (tubeMode ? BASE_FLOATS_TUBE : BASE_FLOATS_FLAT)
                + config.additionalGPUDataSetting.attribFloats();
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
        offset = intInstanceAttrib(1, 1, stride, offset);       // iSeg int (point index)
        if (!tubeMode) {
            offset = floatInstanceAttrib(2, 2, stride, offset); // iSegV vec2 (vA, vB)
        }

        config.additionalGPUDataSetting.layoutAttribs(offset, stride);
    }

    /** Whether the baked geometry no longer matches the config (mode / section polygon / tube uvWidthFactor). */
    boolean geometryStale() {
        if (!isInitialized()) return false;
        if (tubeMode != config.section.isEnable()) return true;
        if (!tubeMode) return false;
        if (uvWidthFactorSnapshot != config.uvWidthFactor) return true;
        var sectionVertices = config.section.vertices;
        if (sectionVertices.size() != sectionSnapshot.length) return true;
        for (int i = 0; i < sectionSnapshot.length; i++) {
            var current = sectionVertices.get(i);
            if (current.x != sectionSnapshot[i].x || current.y != sectionSnapshot[i].y) return true;
        }
        return false;
    }
}
