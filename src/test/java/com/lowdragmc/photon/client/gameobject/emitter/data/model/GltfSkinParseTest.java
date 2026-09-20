package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.AnimationClip;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.MeshSkin;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.Skeleton;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading a skinned, animated glTF. {@code SkinDeformerTest} pins the maths this feeds; these pin the
 * decisions the <b>file format</b> forces, each of which is silent when wrong:
 *
 * <ul>
 *   <li>a skinned mesh's own node transform is ignored — applying it puts the model at twice its offset;</li>
 *   <li>a joint's non-joint ancestors are still part of the hierarchy — dropping one arrives rotated;</li>
 *   <li>{@code JOINTS_0} indexes the skin's joint list, not the nodes — confusing them animates with the
 *       wrong bone;</li>
 *   <li>inverse bind matrices are {@code MAT4} accessors — rejecting them leaves an identity bind pose,
 *       which is right only for a model authored at the origin.</li>
 * </ul>
 */
class GltfSkinParseTest {

    /**
     * A one-triangle skinned mesh.
     *
     * <pre>
     *   node 0  mesh 0, skin 0, translation (100,0,0)   &lt;- MUST be ignored
     *   node 2  "armatureRoot", rotation 90 deg +Y      &lt;- not a joint, but a joint's parent
     *     node 1  "bone", translation (0,1,0)           &lt;- skin 0's only joint
     * </pre>
     *
     * The inverse bind matrix is a translation of {@code (9,0,0)} — not a plausible bind pose, but
     * unmistakable in an assertion, which identity would not be.
     */
    private static String skinnedGltf(String channelPath) {
        var bytes = ByteBuffer.allocate(252).order(ByteOrder.LITTLE_ENDIAN);
        // positions @0
        float[][] positions = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}};
        for (var p : positions) for (float f : p) bytes.putFloat(f);
        // normals @36
        for (int i = 0; i < 3; i++) bytes.putFloat(0).putFloat(0).putFloat(1);
        // uvs @72
        float[][] uvs = {{0, 0}, {1, 0}, {0, 1}};
        for (var uv : uvs) for (float f : uv) bytes.putFloat(f);
        // JOINTS_0 @96, unsigned bytes, every vertex on the skin's local joint 0
        for (int i = 0; i < 3; i++) {
            bytes.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        // WEIGHTS_0 @108
        for (int i = 0; i < 3; i++) bytes.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f);
        // inverse bind matrix @156, column-major, translation in elements 12..14
        float[] inverseBind = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 9, 0, 0, 1};
        for (float f : inverseBind) bytes.putFloat(f);
        // animation input @220 and output @228
        bytes.putFloat(0f).putFloat(1f);
        bytes.putFloat(0).putFloat(0).putFloat(0);
        bytes.putFloat(0).putFloat(0).putFloat(5);

        return """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0, 2]}],
                  "nodes": [
                    {"mesh": 0, "skin": 0, "translation": [100, 0, 0]},
                    {"name": "bone", "translation": [0, 1, 0]},
                    {"name": "armatureRoot", "rotation": [0, 0.7071068, 0, 0.7071068], "children": [1]}
                  ],
                  "meshes": [{"primitives": [{"attributes": {
                    "POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2, "JOINTS_0": 3, "WEIGHTS_0": 4}}]}],
                  "skins": [{"joints": [1], "inverseBindMatrices": 5}],
                  "animations": [{
                    "name": "wave",
                    "channels": [{"sampler": 0, "target": {"node": 1, "path": "%s"}}],
                    "samplers": [{"input": 6, "output": 7, "interpolation": "LINEAR"}]
                  }],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,   "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 36,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 72,  "componentType": 5126, "count": 3, "type": "VEC2"},
                    {"bufferView": 0, "byteOffset": 96,  "componentType": 5121, "count": 3, "type": "VEC4"},
                    {"bufferView": 0, "byteOffset": 108, "componentType": 5126, "count": 3, "type": "VEC4"},
                    {"bufferView": 0, "byteOffset": 156, "componentType": 5126, "count": 1, "type": "MAT4"},
                    {"bufferView": 0, "byteOffset": 220, "componentType": 5126, "count": 2, "type": "SCALAR"},
                    {"bufferView": 0, "byteOffset": 228, "componentType": 5126, "count": 2, "type": "VEC3"}
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 252}],
                  "buffers": [{"byteLength": 252, "uri": "data:application/octet-stream;base64,%s"}]
                }
                """.formatted(channelPath, Base64.getEncoder().encodeToString(bytes.array()));
    }

    private static com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel parse(String json)
            throws IOException {
        return GltfMeshParser.parseModel(json.getBytes(StandardCharsets.UTF_8), false);
    }

    @Test
    void readsTheSkeletonIncludingAJointsNonJointAncestors() throws IOException {
        var model = parse(skinnedGltf("translation"));
        assertTrue(model.isAnimated(), "a skinned mesh with a clip is animated");
        var skeleton = model.skeleton();
        assertNotNull(skeleton);
        assertEquals(2, skeleton.jointCount(), "the bone and the armature root above it");
        // parents precede children, so the non-joint ancestor is slot 0
        assertEquals("armatureRoot", skeleton.name(0));
        assertEquals("bone", skeleton.name(1));
        assertEquals(-1, skeleton.parent(0));
        assertEquals(0, skeleton.parent(1), "the bone hangs off the armature root");
    }

    /** The bug this guards: MAT4 was not a component type the accessor reader knew. */
    @Test
    void readsInverseBindMatricesFromTheirMat4Accessor() throws IOException {
        var model = parse(skinnedGltf("translation"));
        var skeleton = model.skeleton();
        assertNotNull(skeleton);
        // slot 1 is the bone; its inverse bind matrix's translation column is the fixture's (9,0,0)
        int at = 1 * Skeleton.FLOATS_PER_MATRIX;
        assertEquals(9f, skeleton.inverseBind()[at + 3], 1e-5f,
                "the inverse bind matrix was dropped and silently replaced with the identity");
        // and the joint no skin gave a matrix for keeps the identity it was created with
        assertEquals(0f, skeleton.inverseBind()[3], 1e-5f);
    }

    /** glTF 3.7.3: the joint matrices carry the placement, so the mesh node's transform is ignored. */
    @Test
    void aSkinnedMeshIgnoresItsOwnNodeTransform() throws IOException {
        var model = parse(skinnedGltf("translation"));
        var geometry = model.mesh().geometry();
        for (int vertex = 0; vertex < model.mesh().vertexCount(); vertex++) {
            float x = geometry[PhotonMesh.geometryOffset(vertex)];
            assertTrue(x < 50f, "vertex " + vertex + " is at x=" + x + ", so the node's +100 was baked in");
        }
        assertEquals(1f, geometry[PhotonMesh.geometryOffset(1)], 1e-5f, "authored position, untouched");
    }

    @Test
    void mapsPerVertexJointsThroughTheSkinsJointList() throws IOException {
        var model = parse(skinnedGltf("translation"));
        var skin = model.skin();
        assertNotNull(skin);
        assertEquals(model.mesh().vertexCount(), skin.vertexCount());
        assertFalse(skin.isEmpty());
        for (int vertex = 0; vertex < skin.vertexCount(); vertex++) {
            int at = vertex * MeshSkin.INFLUENCES;
            // the file says local joint 0, which is node 1, which sorted to slot 1 — not 0
            assertEquals(1, skin.joints()[at], "vertex " + vertex + " bound to the wrong skeleton slot");
            assertEquals(1f, skin.weights()[at], 1e-5f);
            assertEquals(0f, skin.weights()[at + 1], 1e-5f);
        }
    }

    @Test
    void readsAnimationChannelsAgainstTheSkeletonsSlots() throws IOException {
        var model = parse(skinnedGltf("translation"));
        assertEquals(1, model.clips().size());
        var clip = model.clips().getFirst();
        assertEquals("wave", clip.name());
        assertEquals(1f, clip.duration(), 1e-5f);
        assertEquals(1, clip.channels().size());
        var channel = clip.channels().getFirst();
        assertEquals(1, channel.joint(), "targets the bone's slot, not its node index");
        assertEquals(AnimationClip.Path.TRANSLATION, channel.path());
        assertEquals(AnimationClip.Interpolation.LINEAR, channel.interpolation());
        assertEquals(5f, channel.values()[5], 1e-5f, "the last keyframe's z");
        assertSame(clip, model.clip("wave"));
        assertSame(clip, model.clipAt(99), "clipAt clamps");
        assertEquals(java.util.List.of("wave"), model.clipNames());
    }

    /**
     * Morph targets are a {@code weights} channel, which is not a TRS path. Sampling one into a TRS slot
     * would corrupt whatever that slot holds, so the channel is dropped — and with it the only channel,
     * which makes the clip itself go away rather than exist and do nothing.
     */
    @Test
    void morphTargetChannelsAreSkipped() throws IOException {
        var model = parse(skinnedGltf("weights"));
        assertTrue(model.clips().isEmpty(), "a clip whose every channel we cannot drive is not a clip");
        assertNotNull(model.skeleton(), "the skin is still read, though");
        assertFalse(model.isAnimated() && !model.clips().isEmpty());
    }

    /** A file with no skin must not grow one, and must not pay for any of this. */
    @Test
    void aStaticFileStaysStatic() throws IOException {
        var json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}}]}],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 36, "componentType": 5126, "count": 3, "type": "VEC3"}
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 72}],
                  "buffers": [{"byteLength": 72, "uri": "data:application/octet-stream;base64,%s"}]
                }
                """.formatted(Base64.getEncoder().encodeToString(staticBuffer()));
        var model = parse(json);
        assertNull(model.skin());
        assertNull(model.skeleton());
        assertFalse(model.isAnimated());
        assertTrue(model.clips().isEmpty());
        assertEquals(1, model.mesh().triangleCount());
    }

    private static byte[] staticBuffer() {
        var bytes = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        float[][] positions = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}};
        for (var p : positions) for (float f : p) bytes.putFloat(f);
        for (int i = 0; i < 3; i++) bytes.putFloat(0).putFloat(0).putFloat(1);
        return bytes.array();
    }
}
