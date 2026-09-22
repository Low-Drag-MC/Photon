package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;


/**
 * glTF's {@code JOINTS_0} / {@code WEIGHTS_0}, indexed by the mesh's vertex numbering.
 * A vertex whose weights sum to zero is rigid and left alone.
 */
public final class MeshSkin {

    public static final int INFLUENCES = 4;

    private final int[] joints;
    private final float[] weights;

    public MeshSkin(int[] joints, float[] weights) {
        this.joints = joints;
        this.weights = weights;
    }

    public int vertexCount() {
        return joints.length / INFLUENCES;
    }

    public int[] joints() {
        return joints;
    }

    public float[] weights() {
        return weights;
    }

    public boolean isEmpty() {
        for (float weight : weights) {
            if (weight != 0f) return false;
        }
        return true;
    }
}
