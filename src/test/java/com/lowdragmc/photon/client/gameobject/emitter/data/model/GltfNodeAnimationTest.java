package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.MeshSkin;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinDeformer;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two shapes of glTF that used to be dropped: a file with animations but no mesh, and a mesh node that
 * is animated without being skinned.
 */
class GltfNodeAnimationTest {

    private static SkinnedModel parse(String json) throws IOException {
        return GltfMeshParser.parseModel(json.getBytes(StandardCharsets.UTF_8), false);
    }

    /** One triangle, two keyframes of a +5 translation in z. */
    private static String buffer() {
        var bytes = ByteBuffer.allocate(36 + 8 + 24).order(ByteOrder.LITTLE_ENDIAN);
        float[][] positions = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}};
        for (var p : positions) for (float f : p) bytes.putFloat(f);
        bytes.putFloat(0f).putFloat(1f);
        bytes.putFloat(0).putFloat(0).putFloat(0);
        bytes.putFloat(0).putFloat(0).putFloat(5);
        return Base64.getEncoder().encodeToString(bytes.array());
    }

    private static final String ACCESSORS = """
            "accessors": [
              {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
              {"bufferView": 0, "byteOffset": 36, "componentType": 5126, "count": 2, "type": "SCALAR"},
              {"bufferView": 0, "byteOffset": 44, "componentType": 5126, "count": 2, "type": "VEC3"}
            ],
            "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 68}],
            "buffers": [{"byteLength": 68, "uri": "data:application/octet-stream;base64,%s"}]
            """;

    /**
     * A node carrying a mesh, no skin, and a translation channel. Its transform is no longer a constant,
     * so it cannot be baked into the vertices — the node becomes a joint and the mesh binds to it.
     */
    @Test
    void anAnimatedUnskinnedMeshNodeIsBoundToItsNode() throws IOException {
        var model = parse("""
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}],
                  "nodes": [{"name": "propeller", "mesh": 0, "translation": [10, 0, 0]}],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
                  "animations": [{
                    "name": "spin",
                    "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}}],
                    "samplers": [{"input": 1, "output": 2, "interpolation": "LINEAR"}]
                  }],
                  %s
                }
                """.formatted(ACCESSORS.formatted(buffer())));

        assertTrue(model.isAnimated(), "an animated rigid node makes the model animated");
        var skin = Objects.requireNonNull(model.skin());
        assertFalse(skin.isEmpty(), "its vertices are bound to something");
        assertEquals(1f, skin.weights()[0], 1e-6f, "fully, to one joint");
        assertEquals(1, model.clips().size(), "the channel was kept, not dropped for naming a non-joint");

        // ⚠️ the node's own +10 must NOT be baked in: the joint carries it
        assertEquals(0f, model.mesh().geometry()[0], 1e-5f, "vertices are in the node's local space");

        // at rest the joint puts it back at +10, and the clip adds z on top
        var deformer = new SkinDeformer(Objects.requireNonNull(model.skeleton()));
        var out = new float[model.mesh().vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.pose(null, 0f);
        deformer.deform(model.mesh(), skin, out);
        assertEquals(10f, out[0], 1e-5f, "the rest pose reproduces the authored placement");

        deformer.pose(model.clipAt(0), 1f);
        deformer.deform(model.mesh(), skin, out);
        assertEquals(5f, out[2], 1e-5f, "and the clip moved it");
    }

    /** A static node in the same file still bakes, so nothing pays for a feature it does not use. */
    @Test
    void anUnanimatedNodeStillBakesItsTransform() throws IOException {
        var model = parse("""
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0, "translation": [10, 0, 0]}],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
                  %s
                }
                """.formatted(ACCESSORS.formatted(buffer())));
        assertFalse(model.isAnimated());
        assertNull(model.skeleton());
        assertEquals(10f, model.mesh().geometry()[0], 1e-5f, "baked, as before");
    }

    /** A child of an animated node moves with it, even though nothing targets the child. */
    @Test
    void aStaticMeshUnderAnAnimatedParentMovesToo() throws IOException {
        var model = parse("""
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}],
                  "nodes": [
                    {"name": "arm", "children": [1]},
                    {"name": "hand", "mesh": 0, "translation": [2, 0, 0]}
                  ],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
                  "animations": [{
                    "name": "swing",
                    "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}}],
                    "samplers": [{"input": 1, "output": 2, "interpolation": "LINEAR"}]
                  }],
                  %s
                }
                """.formatted(ACCESSORS.formatted(buffer())));

        assertTrue(model.isAnimated());
        var deformer = new SkinDeformer(Objects.requireNonNull(model.skeleton()));
        var out = new float[model.mesh().vertexCount() * PhotonMesh.FLOATS_PER_GEOMETRY];
        deformer.pose(model.clipAt(0), 1f);
        deformer.deform(model.mesh(), Objects.requireNonNull(model.skin()), out);

        assertEquals(2f, out[0], 1e-5f, "its own offset under the arm");
        assertEquals(5f, out[2], 1e-5f, "plus the arm's movement");
    }

    /** A file with animations and no mesh at all: the skeleton and clips are the whole point of it. */
    @Test
    void anAnimationOnlyFileKeepsItsSkeletonAndClips() throws IOException {
        var model = parse("""
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}],
                  "nodes": [{"name": "hips"}],
                  "skins": [{"joints": [0]}],
                  "animations": [{
                    "name": "idle",
                    "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}}],
                    "samplers": [{"input": 1, "output": 2, "interpolation": "LINEAR"}]
                  }],
                  %s
                }
                """.formatted(ACCESSORS.formatted(buffer())));

        assertTrue(model.mesh().isEmpty(), "there is no geometry in it");
        assertNull(model.skin(), "and nothing to skin");
        assertNotNull(model.skeleton());
        assertEquals("hips", model.skeleton().name(0));
        assertEquals(1, model.clips().size());
        assertEquals("idle", model.clips().getFirst().name());
        assertFalse(model.isAnimated(), "it cannot be drawn; it is a donor");
    }
}
