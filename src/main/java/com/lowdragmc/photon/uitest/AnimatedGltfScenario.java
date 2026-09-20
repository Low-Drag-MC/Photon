package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.GltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A glTF model playing its own animation, end to end through the registry and the resource manager.
 *
 * <p>The parsing and the skinning maths are unit-tested; what only a client can show is the chain:
 * registry entry -> resource manager -> parsed skin -> posed geometry -> the mesh a render pass and an
 * emission shape read. And one thing that is invisible even here, which is why the clock is pinned: that
 * a pose already computed is <b>reused</b> rather than recomputed. Whether the deformation ran is not
 * something a picture can show, so it is asserted by holding the clock still and watching the revision
 * fail to move.</p>
 */
@LDLRegisterClient(name = "animated_gltf", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AnimatedGltfScenario implements UIScenario {

    private static final String DIR = "photon/models/";
    private static final String PREFIX = "uitest_anim_";
    private final List<File> fixtures = new ArrayList<>();

    @Override
    public void define(ScenarioBuilder s) {
        s.step("the registry offers an animated glTF source", ctx -> {
            var keys = PhotonRegistries.MODEL_SOURCES.keys();
            boolean registered = keys.contains("animated_gltf_model");
            ctx.check("animated_gltf_model is registered", registered, "present", keys.toString());
            if (!registered) return;
            var built = PhotonRegistries.MODEL_SOURCES.get("animated_gltf_model").value().get();
            ctx.check("the registry builds an AnimatedGltfModelSource",
                    built instanceof AnimatedGltfModelSource, "AnimatedGltfModelSource",
                    built == null ? "null" : built.getClass().getSimpleName());
        })

        .step("a skinned glb loads and reports itself animated", ctx -> {
            var location = write("skinned.glb", glb(skinnedGltf()));
            var source = new AnimatedGltfModelSource(location);
            source.invalidate();
            AnimatedGltfModelSource.pinClock(0f);
            try {
                var mesh = source.getMesh();
                ctx.check("the model loaded", !mesh.isEmpty(), "non-empty", "empty mesh");
                ctx.check("it is dynamic, so the renderer re-uploads instead of rebuilding",
                        source.asDynamic() != null, "dynamic", "static");
                ctx.check("two triangles arrived", mesh.triangleCount() == 2, 2, mesh.triangleCount());
            } finally {
                AnimatedGltfModelSource.pinClock(null);
            }
        })

        // The whole point of the revision: work that has already been done is not done again.
        .step("holding the clock still reuses the pose", ctx -> {
            var location = write("hold.glb", glb(skinnedGltf()));
            var source = new AnimatedGltfModelSource(location);
            source.invalidate();
            AnimatedGltfModelSource.pinClock(0.25f);
            try {
                var first = source.getMesh();
                long revision = first.geometryRevision();
                var second = source.getMesh();
                ctx.check("the same instant hands back the identical mesh", second == first,
                        "same instance", "re-derived");
                ctx.check("and did not advance the revision", second.geometryRevision() == revision,
                        revision, second.geometryRevision());
                ctx.check("a posed mesh's revision is past the topology's own 0", revision > 0,
                        "> 0", revision);
            } finally {
                AnimatedGltfModelSource.pinClock(null);
            }
        })

        .step("moving the clock moves the vertices", ctx -> {
            var location = write("move.glb", glb(skinnedGltf()));
            var source = new AnimatedGltfModelSource(location);
            source.invalidate();
            try {
                AnimatedGltfModelSource.pinClock(0f);
                var atStart = source.getMesh();
                float startY = atStart.geometry()[PhotonMesh.geometryOffset(0) + 1];
                var topology = atStart.topology();

                AnimatedGltfModelSource.pinClock(0.5f);
                var midway = source.getMesh();
                float midY = midway.geometry()[PhotonMesh.geometryOffset(0) + 1];

                ctx.check("a new instant is a new mesh", midway != atStart, "new instance", "stale");
                ctx.check("but the same topology, so the buffers survive",
                        midway.topology() == topology, "same topology", "a rebuild");
                ctx.check("the revision advanced", midway.geometryRevision() > atStart.geometryRevision(),
                        "> " + atStart.geometryRevision(), midway.geometryRevision());
                // the fixture's only joint slides +2 in y over 1 second, and every vertex is bound to it
                ctx.check("the vertex moved with its joint", Math.abs(midY - startY) > 0.5f,
                        "moved about 1", "from " + startY + " to " + midY);
            } finally {
                AnimatedGltfModelSource.pinClock(null);
            }
        })

        // An emission shape reads the same source. What must not happen is a full rebuild of the sampling
        // geometry per pose: the edges and triangles hold the vertex objects, so they move in place.
        .step("an emission shape follows the animation without rebuilding", ctx -> {
            var location = write("shape.glb", glb(skinnedGltf()));
            var source = new AnimatedGltfModelSource(location);
            source.invalidate();
            var meshData = new MeshData(source);
            try {
                AnimatedGltfModelSource.pinClock(0f);
                var triangles = meshData.getTriangles();
                int count = triangles.size();
                var firstTriangle = triangles.getFirst();
                float startY = firstTriangle.a.y;

                AnimatedGltfModelSource.pinClock(0.5f);
                var again = meshData.getTriangles();
                ctx.check("the triangle list was not rebuilt", again == triangles || again.size() == count,
                        count, again.size());
                ctx.check("the same Triangle object is still there",
                        again.getFirst() == firstTriangle, "same instance", "reallocated");
                ctx.check("and its corner moved with the pose", Math.abs(firstTriangle.a.y - startY) > 0.1f,
                        "moved", "still at " + startY);
                ctx.check("the area weight stayed the rest pose's", firstTriangle.area > 0,
                        "> 0", firstTriangle.area);
            } finally {
                AnimatedGltfModelSource.pinClock(null);
            }
        })

        // One parse serves both sources, which is only true because they share a cache key.
        .step("a static source reads the same file without a second parse", ctx -> {
            var location = write("shared.glb", glb(skinnedGltf()));
            var animated = new AnimatedGltfModelSource(location);
            animated.invalidate();
            AnimatedGltfModelSource.pinClock(0f);
            try {
                var posed = animated.getMesh();
                var stat = new GltfModelSource(location).getMesh();
                ctx.check("the static source sees the bind pose", stat == posed.topology(),
                        "the topology instance", "a different mesh");
                ctx.check("a static source is not dynamic",
                        new GltfModelSource(location).asDynamic() == null, "static", "dynamic");
            } finally {
                AnimatedGltfModelSource.pinClock(null);
            }
        })

        .step("clean up the fixtures", ctx -> {
            int deleted = 0;
            for (var file : fixtures) {
                if (!file.exists() || file.delete()) deleted++;
            }
            ctx.check("every fixture was removed", deleted == fixtures.size(), fixtures.size(), deleted);
        });
    }

    private ResourceLocation write(String name, byte[] bytes) {
        var file = new File(LDLib2.getAssetsDir(), DIR + PREFIX + name);
        fixtures.add(file);
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("could not write the glTF fixture " + name, e);
        }
        return Photon.id("models/" + PREFIX + name);
    }

    /**
     * A skinned quad (two triangles, four vertices) bound entirely to one joint that slides {@code +2} in
     * y over one second.
     */
    private static String skinnedGltf() {
        var bytes = ByteBuffer.allocate(300).order(ByteOrder.LITTLE_ENDIAN);
        // positions @0 (48)
        float[][] positions = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
        for (var p : positions) for (float f : p) bytes.putFloat(f);
        // normals @48 (48)
        for (int i = 0; i < 4; i++) bytes.putFloat(0).putFloat(0).putFloat(1);
        // uvs @96 (32)
        float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (var uv : uvs) for (float f : uv) bytes.putFloat(f);
        // JOINTS_0 @128 (16), every vertex on the skin's local joint 0
        for (int i = 0; i < 4; i++) {
            bytes.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        // WEIGHTS_0 @144 (64)
        for (int i = 0; i < 4; i++) bytes.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f);
        // indices @208 (12), two triangles
        for (int index : new int[]{0, 1, 2, 0, 2, 3}) bytes.putShort((short) index);
        // inverse bind matrix @220 (64) — identity, so the bind pose is the authored one
        float[] inverseBind = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        for (float f : inverseBind) bytes.putFloat(f);
        // animation input @284 (8) and output @292 (... 24, but only 2 keys x vec3 = 24 -> 316)
        bytes.putFloat(0f).putFloat(1f);

        // the output accessor needs 24 more bytes than the 300 allocated above, so build it separately
        var full = ByteBuffer.allocate(316).order(ByteOrder.LITTLE_ENDIAN);
        full.put(bytes.array(), 0, 292);
        full.putFloat(0).putFloat(0).putFloat(0);
        full.putFloat(0).putFloat(2).putFloat(0);

        return """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0, 1]}],
                  "nodes": [
                    {"mesh": 0, "skin": 0},
                    {"name": "bone", "translation": [0, 0, 0]}
                  ],
                  "meshes": [{"primitives": [{"indices": 5, "attributes": {
                    "POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2, "JOINTS_0": 3, "WEIGHTS_0": 4}}]}],
                  "skins": [{"joints": [1], "inverseBindMatrices": 6}],
                  "animations": [{
                    "name": "slide",
                    "channels": [{"sampler": 0, "target": {"node": 1, "path": "translation"}}],
                    "samplers": [{"input": 7, "output": 8, "interpolation": "LINEAR"}]
                  }],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,   "componentType": 5126, "count": 4, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 48,  "componentType": 5126, "count": 4, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 96,  "componentType": 5126, "count": 4, "type": "VEC2"},
                    {"bufferView": 0, "byteOffset": 128, "componentType": 5121, "count": 4, "type": "VEC4"},
                    {"bufferView": 0, "byteOffset": 144, "componentType": 5126, "count": 4, "type": "VEC4"},
                    {"bufferView": 0, "byteOffset": 208, "componentType": 5123, "count": 6, "type": "SCALAR"},
                    {"bufferView": 0, "byteOffset": 220, "componentType": 5126, "count": 1, "type": "MAT4"},
                    {"bufferView": 0, "byteOffset": 284, "componentType": 5126, "count": 2, "type": "SCALAR"},
                    {"bufferView": 0, "byteOffset": 292, "componentType": 5126, "count": 2, "type": "VEC3"}
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 316}],
                  "buffers": [{"byteLength": 316, "uri": "data:application/octet-stream;base64,%s"}]
                }
                """.formatted(Base64.getEncoder().encodeToString(full.array()));
    }

    /** Wrap JSON in a GLB container: 12-byte header, then a 4-byte-aligned JSON chunk. */
    private static byte[] glb(String json) {
        var text = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int padded = (text.length + 3) & ~3;
        var out = ByteBuffer.allocate(12 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(12 + 8 + padded);
        out.putInt(padded).putInt(0x4E4F534A).put(text);
        while (out.position() < out.capacity()) out.put((byte) ' ');
        return out.array();
    }
}
