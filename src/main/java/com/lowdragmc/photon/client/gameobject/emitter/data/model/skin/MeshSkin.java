package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Which joints move each vertex, and how much — glTF's {@code JOINTS_0} / {@code WEIGHTS_0}, indexed by
 * the same vertex numbering the mesh's geometry stream uses.
 *
 * <p>Four influences a vertex, the one set glTF requires every implementation to support and in practice
 * the only one exporters write.</p>
 *
 * <p>⚠️ <b>A vertex whose weights sum to zero is not skinned</b> and is copied through untouched. That is
 * how a file mixing a skinned mesh with a rigid one is carried in a single mesh: the rigid parts already
 * have their node transform baked into their positions, so leaving them alone is exactly right, and it
 * avoids inventing an identity joint for them to bind to.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MeshSkin {

    /** Joints influencing one vertex. */
    public static final int INFLUENCES = 4;

    private final int[] joints;
    private final float[] weights;

    /**
     * @param joints  {@code vertexCount * }{@link #INFLUENCES} skeleton joint indices
     * @param weights the matching weights; a vertex's four summing to zero means "rigid, leave it"
     */
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

    /** Whether any vertex at all is skinned; a mesh where none is needs no deformation. */
    public boolean isEmpty() {
        for (float weight : weights) {
            if (weight != 0f) return false;
        }
        return true;
    }
}
